package com.carlink.ui.settings

// SCHEMA STABILITY CONTRACT:
// All DataStore keys (KEY_*) and SharedPreferences syncCache keys (SYNC_CACHE_KEY_*) below are
// UNVERSIONED schema strings. Renaming any key silently orphans existing installs — their stored
// values become unreachable and defaults are silently used. Treat string literals in this file as
// a wire format: never rename without a migration. The literal names happen to match between
// DataStore and SharedPreferences (e.g. "audio_source_bluetooth"); keep them in sync manually —
// nothing enforces the coincidence.
//
// DUAL-WRITE ATOMICITY:
// Every setter performs `dataStore.edit {}` followed by `syncCache.edit().apply()` with no
// transactional wrapper. If the process crashes between the two writes, DataStore leads and the
// syncCache lags until the Flow observer re-syncs it on next app start. Sync getters can briefly
// report stale values after such a crash. Accepted tradeoff for ANR-free startup reads.

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import java.io.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.BuildConfig
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.platform.PlatformDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.adapterConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_adapter_config_preferences",
)

/**
 * Audio source configuration for adapter initialization.
 *
 * ENUM PERSISTENCE CONTRACT (applies to all config enums in this file):
 * These enums are persisted by their explicit semantic value (e.g. commandCode, value, delayMs,
 * fps) — NEVER by ordinal. This is reorder-safe: you may add, remove, or reorder enum entries
 * without corrupting existing stored values, as long as the semantic codes stay stable. Do NOT
 * "simplify" persistence to ordinal-based lookups (e.g. `entries[preferences[key] ?: 0]`) —
 * that would break on any enum reorder. AudioSourceConfig is the lone exception: it is stored
 * as a boolean (isBluetooth) rather than a code.
 */
enum class AudioSourceConfig {
    /**
     * Bluetooth mode - Audio plays through phone's Bluetooth to car stereo.
     * Sends AUDIO_TRANSFER_ON during initialization.
     */
    BLUETOOTH,

    /**
     * Adapter mode - Audio streams through USB to be played by this app.
     * Sends AUDIO_TRANSFER_OFF during initialization.
     * This is the default for first launch (FULL init).
     */
    ADAPTER,
    ;

    companion object {
        val DEFAULT = ADAPTER
    }
}

/**
 * Microphone source configuration.
 * Controls which microphone is used for voice input (Siri, calls).
 */
enum class MicSourceConfig(
    val commandCode: Int,
) {
    /** App/Device mic - this device (Android/Pi running the app) captures microphone input. */
    APP(7),

    /** Phone/Adapter mic - phone uses its own mic, or adapter's mic if physically present. */
    PHONE(15),
    ;

    companion object {
        val DEFAULT = APP
    }
}

/**
 * WiFi band configuration for wireless CarPlay connection.
 * 5GHz offers better performance, 2.4GHz offers better range/compatibility.
 */
enum class WiFiBandConfig(
    val commandCode: Int,
) {
    /** 5GHz band - better speed and less interference (recommended). */
    BAND_5GHZ(25),

    /** 2.4GHz band - better range but more interference. */
    BAND_24GHZ(24),
    ;

    companion object {
        val DEFAULT = BAND_5GHZ
    }
}

/**
 * Call quality configuration for phone calls.
 * May affect audio bitrate or quality during calls (needs testing).
 *
 * VESTIGIAL / UI-DEAD (do NOT delete):
 * The Call Quality control was removed from AdapterConfigurationDialog.kt (see comment at
 * AdapterConfigurationDialog.kt:394 — "Call Quality removed — firmware bug"). This entire
 * surface (enum, callQualityFlow, setCallQuality, getCallQualitySync, UserConfig.callQuality,
 * ConfigKey.CALL_QUALITY, and the MessageSerializer.addChangedSettings branch) is still fully
 * plumbed but no UI path sets it. Kept so persisted values from older builds round-trip and so
 * the firmware protocol surface stays intact for a future re-enable. Do not delete.
 */
enum class CallQualityConfig(
    val value: Int,
) {
    /** Normal quality. */
    NORMAL(0),

    /** Clear quality - enhanced clarity. */
    CLEAR(1),

    /** HD quality - highest quality. */
    HD(2),
    ;

    companion object {
        val DEFAULT = HD
    }
}

/**
 * Media delay configuration for adapter audio buffer size.
 * Controls tinyalsa PCM buffer size (pcm_open period_size) on the adapter firmware.
 * Lower values = less audio latency but more susceptible to USB jitter.
 * Higher values = more stable but noticeable audio lag.
 */
enum class MediaDelayConfig(
    val delayMs: Int,
) {
    LOW(300),
    MEDIUM(500),
    STANDARD(1000),
    HIGH(2000),
    ;

    companion object {
        val DEFAULT = STANDARD
    }
}

/**
 * Frame rate configuration for adapter initialization.
 * Controls the FPS value sent in the OPEN message to the adapter firmware.
 * 30 FPS is default — sufficient for CarPlay UI and reduces thermal/power load.
 * 60 FPS available for smoother animations if the display supports it.
 */
enum class FpsConfig(
    val fps: Int,
) {
    FPS_30(30),
    FPS_60(60),
    ;

    companion object {
        val DEFAULT = FPS_60
    }
}

/**
 * Hand drive mode configuration for CarPlay/Android Auto UI layout.
 * Controls which side the UI elements are positioned for the driver.
 * Written to /tmp/hand_drive_mode on the adapter (0=LHD, 1=RHD).
 */
enum class HandDriveConfig(
    val value: Int,
) {
    /** Left Hand Drive — dock on left, for countries that drive on the right (US, Europe). */
    LEFT(0),

    /** Right Hand Drive — dock on right, for countries that drive on the left (UK, Japan, AU). */
    RIGHT(1),
    ;

    companion object {
        val DEFAULT = LEFT
    }
}

/**
 * Video resolution configuration for adapter initialization.
 *
 * AUTO uses the detected display resolution (usable area after system UI).
 * Other options are calculated based on the display's aspect ratio to maintain
 * proper proportions while reducing GPU load.
 *
 * Storage format: "WIDTHxHEIGHT" or "AUTO".
 * Parsing edge cases (see fromStorageString):
 *  - `NumberFormatException` on either part → falls back to AUTO silently.
 *  - Wrong number of "x"-separated parts (0, 1, >2) → AUTO.
 *  - `isAuto` is the exact `(0, 0)` pair. A one-sided zero like `"0x720"` or `"1920x0"` parses
 *    successfully and survives as a non-auto config with a zero dimension — ignored in practice
 *    because the picker never produces such values, but worth noting if anyone hand-edits prefs.
 */
data class VideoResolutionConfig(
    val width: Int,
    val height: Int,
) {
    val isAuto: Boolean get() = width == 0 && height == 0

    fun toStorageString(): String = if (isAuto) "AUTO" else "${width}x$height"

    companion object {
        val AUTO = VideoResolutionConfig(0, 0)

        fun fromStorageString(value: String?): VideoResolutionConfig {
            if (value == null || value == "AUTO") return AUTO
            val parts = value.split("x")
            return if (parts.size == 2) {
                try {
                    VideoResolutionConfig(parts[0].toInt(), parts[1].toInt())
                } catch (e: NumberFormatException) {
                    AUTO
                }
            } else {
                AUTO
            }
        }

        /**
         * Calculate resolution options based on display aspect ratio.
         *
         * Returns 4 resolution options that maintain the display's aspect ratio,
         * scaled down from the base resolution. All dimensions are rounded to
         * even numbers for H.264 compatibility.
         *
         * @param displayWidth Native display width in pixels
         * @param displayHeight Native display height in pixels
         * @return List of 4 resolution options, largest to smallest
         */
        fun calculateOptions(
            displayWidth: Int,
            displayHeight: Int,
        ): List<VideoResolutionConfig> {
            // Calculate scale factors to generate 4 options
            // Start from ~93.75% of original, then 83.3%, 72.9%, 62.5%
            val scaleFactors = listOf(0.9375, 0.833, 0.729, 0.625)

            return scaleFactors.map { scale ->
                // Scale from display dimensions and round to even
                val w = ((displayWidth * scale).toInt() and 1.inv())
                val h = ((displayHeight * scale).toInt() and 1.inv())
                VideoResolutionConfig(w, h)
            }
        }
    }
}

/**
 * Adapter config preferences with DataStore + SharedPreferences sync cache for ANR-free startup reads.
 * Tracks pending changes for minimal re-initialization on reconnect.
 *
 * Two-tier storage:
 *  - DataStore (source of truth, async) — authoritative for Flow observers.
 *  - SharedPreferences sync cache — mirrors DataStore, readable from main thread without
 *    suspending. Kept in sync by every setter (dual-write, no transaction: see top-of-file
 *    DUAL-WRITE ATOMICITY note).
 *
 * `@Suppress("StaticFieldLeak")` is safe because `appContext` below is always the application
 * context — no Activity/Fragment reference is held.
 */
@Suppress("StaticFieldLeak")
class AdapterConfigPreference private constructor(
    context: Context,
) {
    // appContext is the application Context — safe to hold from a singleton (see class KDoc).
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: AdapterConfigPreference? = null

        /**
         * Double-checked-locking singleton accessor. Safe to call from any thread; the first
         * call initializes with `context.applicationContext` so no Activity reference leaks.
         */
        fun getInstance(context: Context): AdapterConfigPreference =
            instance ?: synchronized(this) {
                instance ?: AdapterConfigPreference(context.applicationContext).also { instance = it }
            }

        // Preference keys
        private val KEY_AUDIO_SOURCE_BLUETOOTH = booleanPreferencesKey("audio_source_bluetooth")

        // Mic source: stored as command code (7=phone, 15=adapter)
        private val KEY_MIC_SOURCE = intPreferencesKey("mic_source")

        // WiFi band: stored as command code (25=5GHz, 24=2.4GHz)
        private val KEY_WIFI_BAND = intPreferencesKey("wifi_band")

        // Call quality: stored as value (0=normal, 1=clear, 2=HD)
        private val KEY_CALL_QUALITY = intPreferencesKey("call_quality")

        // Media delay: stored as delay in ms (300, 500, 1000, 2000)
        private val KEY_MEDIA_DELAY = intPreferencesKey("media_delay")

        // Video resolution: stored as string "WIDTHxHEIGHT" or "AUTO"
        private val KEY_VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")

        // FPS: stored as int (30 or 60)
        private val KEY_FPS = intPreferencesKey("fps")

        // Hand drive mode: stored as int (0=LHD, 1=RHD)
        private val KEY_HAND_DRIVE = intPreferencesKey("hand_drive_mode")

        // GPS forwarding: true = forward vehicle GPS to CarPlay, false = phone uses own GPS
        private val KEY_GPS_FORWARDING = booleanPreferencesKey("gps_forwarding")

        // Cluster navigation: true = show CarPlay turn-by-turn on instrument cluster
        private val KEY_CLUSTER_NAVIGATION = booleanPreferencesKey("cluster_navigation_enabled")

        // Initialization tracking
        private val KEY_HAS_COMPLETED_FIRST_INIT = booleanPreferencesKey("has_completed_first_init")
        private val KEY_LAST_INIT_VERSION_CODE = longPreferencesKey("last_init_version_code")
        private val KEY_PENDING_CHANGES = stringSetPreferencesKey("pending_changes")

        // SharedPreferences keys for sync cache (ANR prevention)
        private const val SYNC_CACHE_PREFS_NAME = "carlink_adapter_config_sync_cache"
        private const val SYNC_CACHE_KEY_AUDIO_BLUETOOTH = "audio_source_bluetooth"
        private const val SYNC_CACHE_KEY_MIC_SOURCE = "mic_source"
        private const val SYNC_CACHE_KEY_WIFI_BAND = "wifi_band"
        private const val SYNC_CACHE_KEY_CALL_QUALITY = "call_quality"
        private const val SYNC_CACHE_KEY_MEDIA_DELAY = "media_delay"
        private const val SYNC_CACHE_KEY_VIDEO_RESOLUTION = "video_resolution"
        private const val SYNC_CACHE_KEY_FPS = "fps"
        private const val SYNC_CACHE_KEY_HAND_DRIVE = "hand_drive_mode"
        private const val SYNC_CACHE_KEY_GPS_FORWARDING = "gps_forwarding"
        private const val SYNC_CACHE_KEY_CLUSTER_NAVIGATION = "cluster_navigation_enabled"
        private const val SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT = "has_completed_first_init"
        private const val SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE = "last_init_version_code"
        private const val SYNC_CACHE_KEY_PENDING_CHANGES = "pending_changes"

        /**
         * Configuration keys for tracking pending changes.
         * Used to identify which settings need to be sent on next initialization.
         *
         * HAZARD — partial consumer coverage in MessageSerializer.addChangedSettings
         * (MessageSerializer.kt:518-579): only AUDIO_SOURCE, MIC_SOURCE, WIFI_BAND,
         * CALL_QUALITY, MEDIA_DELAY, HAND_DRIVE, and GPS_FORWARDING are emitted on a
         * MINIMAL_PLUS_CHANGES reconnect. VIDEO_RESOLUTION and FPS are added to pendingChanges
         * by their setters but silently NO-OP on the wire under MINIMAL_PLUS_CHANGES. They
         * only actually propagate to the adapter on a FULL init (serializeOpen reads
         * width/height/fps from getUserConfigSync) or on a version-code bump that forces FULL.
         * See also the ConfigKey.VIDEO_RESOLUTION / ConfigKey.FPS hazard notes below.
         *
         * PERSISTENCE CONTRACT: these string constants are the set-of-strings persisted in
         * KEY_PENDING_CHANGES. Renaming any value is a schema break — old installs would carry
         * stale entries that no consumer recognizes. See top-of-file SCHEMA STABILITY CONTRACT.
         */
        object ConfigKey {
            const val AUDIO_SOURCE = "audio_source"
            const val MIC_SOURCE = "mic_source"
            const val WIFI_BAND = "wifi_band"
            const val CALL_QUALITY = "call_quality"
            const val MEDIA_DELAY = "media_delay"

            // HAZARD: not consumed by MessageSerializer.addChangedSettings. Adding this key to
            // pendingChanges is effectively a no-op on a MINIMAL_PLUS_CHANGES reconnect — the
            // new resolution only reaches the adapter on FULL init. Fixing requires extending
            // MessageSerializer (out of scope here).
            const val VIDEO_RESOLUTION = "video_resolution"

            // HAZARD: not consumed by MessageSerializer.addChangedSettings (same as
            // VIDEO_RESOLUTION above). FPS changes only propagate on FULL init.
            const val FPS = "fps"

            const val HAND_DRIVE = "hand_drive_mode"
            const val GPS_FORWARDING = "gps_forwarding"
        }
    }

    private val dataStore = appContext.adapterConfigDataStore

    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    val audioSourceFlow: Flow<AudioSourceConfig> =
        dataStore.data.map { preferences ->
            val isBluetooth = preferences[KEY_AUDIO_SOURCE_BLUETOOTH] ?: false
            if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
        }

    /**
     * Get current audio source configuration synchronously.
     * Uses SharedPreferences cache to avoid ANR.
     *
     * Safe to call from the main thread during Activity.onCreate(). Caveat: the very first
     * getSharedPreferences access in a process blocks on a disk load; subsequent reads hit the
     * in-memory map. The tradeoff is accepted because a suspend DataStore read would force
     * onCreate to hop off the main thread. Applies equally to all `get*Sync()` methods below.
     */
    fun getAudioSourceSync(): AudioSourceConfig {
        val isBluetooth = syncCache.getBoolean(SYNC_CACHE_KEY_AUDIO_BLUETOOTH, false)
        return if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
    }

    /**
     * Set audio source configuration.
     * Updates both DataStore and sync cache.
     *
     * NOTE ON "atomically": the two writes below are NOT transactional. If the process dies
     * between `dataStore.edit {}` and `syncCache.edit().apply()`, DataStore leads and the sync
     * cache trails — `getAudioSourceSync()` will return the stale value until the Flow
     * observer in the app re-mirrors DataStore into the cache on next run. Representative of
     * every setter in this class; see top-of-file DUAL-WRITE ATOMICITY note.
     */
    suspend fun setAudioSource(config: AudioSourceConfig) {
        try {
            val isBluetooth = config == AudioSourceConfig.BLUETOOTH
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_AUDIO_SOURCE_BLUETOOTH] = isBluetooth
            }
            // Update sync cache for instant reads on next startup
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_AUDIO_BLUETOOTH, isBluetooth).apply()
            // Track as pending change for next initialization
            addPendingChange(ConfigKey.AUDIO_SOURCE)
            logInfo("Audio source preference saved: $config (sync cache updated)", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save audio source preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val micSourceFlow: Flow<MicSourceConfig> =
        dataStore.data.map { preferences ->
            val code = preferences[KEY_MIC_SOURCE] ?: MicSourceConfig.DEFAULT.commandCode
            MicSourceConfig.entries.find { it.commandCode == code } ?: MicSourceConfig.DEFAULT
        }

    /**
     * Get current mic source configuration synchronously.
     */
    fun getMicSourceSync(): MicSourceConfig {
        val code = syncCache.getInt(SYNC_CACHE_KEY_MIC_SOURCE, MicSourceConfig.DEFAULT.commandCode)
        return MicSourceConfig.entries.find { it.commandCode == code } ?: MicSourceConfig.DEFAULT
    }

    /**
     * Set mic source configuration.
     */
    suspend fun setMicSource(config: MicSourceConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_MIC_SOURCE] = config.commandCode
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_MIC_SOURCE, config.commandCode).apply()
            addPendingChange(ConfigKey.MIC_SOURCE)
            logInfo("Mic source preference saved: $config", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save mic source preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val wifiBandFlow: Flow<WiFiBandConfig> =
        dataStore.data.map { preferences ->
            val code = preferences[KEY_WIFI_BAND] ?: WiFiBandConfig.DEFAULT.commandCode
            WiFiBandConfig.entries.find { it.commandCode == code } ?: WiFiBandConfig.DEFAULT
        }

    /**
     * Get current WiFi band configuration synchronously.
     */
    fun getWifiBandSync(): WiFiBandConfig {
        val code = syncCache.getInt(SYNC_CACHE_KEY_WIFI_BAND, WiFiBandConfig.DEFAULT.commandCode)
        return WiFiBandConfig.entries.find { it.commandCode == code } ?: WiFiBandConfig.DEFAULT
    }

    /**
     * Set WiFi band configuration.
     */
    suspend fun setWifiBand(config: WiFiBandConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_WIFI_BAND] = config.commandCode
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_WIFI_BAND, config.commandCode).apply()
            addPendingChange(ConfigKey.WIFI_BAND)
            logInfo("WiFi band preference saved: $config", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save WiFi band preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val callQualityFlow: Flow<CallQualityConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_CALL_QUALITY] ?: CallQualityConfig.DEFAULT.value
            CallQualityConfig.entries.find { it.value == value } ?: CallQualityConfig.DEFAULT
        }

    /**
     * Get current call quality configuration synchronously.
     */
    fun getCallQualitySync(): CallQualityConfig {
        val value = syncCache.getInt(SYNC_CACHE_KEY_CALL_QUALITY, CallQualityConfig.DEFAULT.value)
        return CallQualityConfig.entries.find { it.value == value } ?: CallQualityConfig.DEFAULT
    }

    /**
     * Set call quality configuration.
     */
    suspend fun setCallQuality(config: CallQualityConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_CALL_QUALITY] = config.value
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_CALL_QUALITY, config.value).apply()
            addPendingChange(ConfigKey.CALL_QUALITY)
            logInfo("Call quality preference saved: $config", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save call quality preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val mediaDelayFlow: Flow<MediaDelayConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_MEDIA_DELAY] ?: MediaDelayConfig.DEFAULT.delayMs
            MediaDelayConfig.entries.find { it.delayMs == value } ?: MediaDelayConfig.DEFAULT
        }

    /**
     * Get current media delay configuration synchronously.
     */
    fun getMediaDelaySync(): MediaDelayConfig {
        val value = syncCache.getInt(SYNC_CACHE_KEY_MEDIA_DELAY, MediaDelayConfig.DEFAULT.delayMs)
        return MediaDelayConfig.entries.find { it.delayMs == value } ?: MediaDelayConfig.DEFAULT
    }

    /**
     * Set media delay configuration.
     */
    suspend fun setMediaDelay(config: MediaDelayConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_MEDIA_DELAY] = config.delayMs
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_MEDIA_DELAY, config.delayMs).apply()
            addPendingChange(ConfigKey.MEDIA_DELAY)
            logInfo("Media delay preference saved: $config (${config.delayMs}ms)", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save media delay preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val videoResolutionFlow: Flow<VideoResolutionConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_VIDEO_RESOLUTION]
            VideoResolutionConfig.fromStorageString(value)
        }

    /**
     * Get current video resolution configuration synchronously.
     * Returns AUTO if not configured.
     */
    fun getVideoResolutionSync(): VideoResolutionConfig {
        val value = syncCache.getString(SYNC_CACHE_KEY_VIDEO_RESOLUTION, "AUTO")
        return VideoResolutionConfig.fromStorageString(value)
    }

    /**
     * Set video resolution configuration.
     */
    suspend fun setVideoResolution(config: VideoResolutionConfig) {
        try {
            val storageValue = config.toStorageString()
            dataStore.edit { preferences ->
                preferences[KEY_VIDEO_RESOLUTION] = storageValue
            }
            syncCache.edit().putString(SYNC_CACHE_KEY_VIDEO_RESOLUTION, storageValue).apply()
            // HAZARD: MessageSerializer.addChangedSettings (MessageSerializer.kt:518-579) does
            // NOT consume ConfigKey.VIDEO_RESOLUTION. On a MINIMAL_PLUS_CHANGES reconnect the
            // entry sits in pendingChanges but is never emitted. The new resolution only
            // actually reaches the adapter on a FULL init (serializeOpen uses width/height
            // from getUserConfigSync) or on a version-code bump forcing FULL.
            addPendingChange(ConfigKey.VIDEO_RESOLUTION)
            logInfo("Video resolution preference saved: $storageValue", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save video resolution preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    /**
     * Set video resolution synchronously (sync cache only).
     * Used during display mode reinit where the 200ms handler must read the updated value
     * immediately from getUserConfigSync().
     *
     * KDoc corrections (the older phrasing "DataStore write is deferred" was misleading):
     *  1. The KEY_VIDEO_RESOLUTION value is NEVER written to DataStore by this function. It is
     *     only written to the syncCache. `videoResolutionFlow` (backed by DataStore) therefore
     *     stays stale until something calls the suspend `setVideoResolution()`.
     *  2. What IS written to DataStore by this function is KEY_PENDING_CHANGES — via the
     *     unscoped `CoroutineScope(Dispatchers.IO).launch { addPendingChange(...) }` below.
     *  3. HAZARD: `ConfigKey.VIDEO_RESOLUTION` is not consumed by
     *     MessageSerializer.addChangedSettings (see ConfigKey KDoc and call-site note in
     *     setVideoResolution above) — so the pending-change entry is effectively a no-op on
     *     the next MINIMAL_PLUS_CHANGES reconnect. The resolution only propagates on FULL init.
     *  4. KNOWN TRADEOFF: the `CoroutineScope(Dispatchers.IO)` below is unscoped — it is not
     *     tied to a lifecycle and will not be cancelled. Accepted because the launched block
     *     is a single short DataStore write with its own try/catch. Do not promote this
     *     pattern; prefer injecting a scoped CoroutineScope if/when this class grows more
     *     fire-and-forget writes.
     */
    fun setVideoResolutionSync(config: VideoResolutionConfig) {
        val storageValue = config.toStorageString()
        syncCache.edit().putString(SYNC_CACHE_KEY_VIDEO_RESOLUTION, storageValue).apply()
        // Unscoped fire-and-forget — see point 4 in the KDoc above.
        CoroutineScope(Dispatchers.IO).launch {
            addPendingChange(ConfigKey.VIDEO_RESOLUTION)
        }
        logInfo("Video resolution sync cache updated: $storageValue", tag = "AdapterConfig")
    }

    val fpsFlow: Flow<FpsConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_FPS] ?: FpsConfig.DEFAULT.fps
            FpsConfig.entries.find { it.fps == value } ?: FpsConfig.DEFAULT
        }

    /**
     * Get current FPS configuration synchronously.
     */
    fun getFpsSync(): FpsConfig {
        val value = syncCache.getInt(SYNC_CACHE_KEY_FPS, FpsConfig.DEFAULT.fps)
        return FpsConfig.entries.find { it.fps == value } ?: FpsConfig.DEFAULT
    }

    /**
     * Set FPS configuration.
     */
    suspend fun setFps(config: FpsConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_FPS] = config.fps
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_FPS, config.fps).apply()
            // HAZARD: MessageSerializer.addChangedSettings (MessageSerializer.kt:518-579) does
            // NOT consume ConfigKey.FPS. On MINIMAL_PLUS_CHANGES reconnects the pending entry
            // is silently ignored; FPS only reaches the adapter on FULL init (serializeOpen
            // reads fps from getUserConfigSync) or on a version-code bump forcing FULL.
            addPendingChange(ConfigKey.FPS)
            logInfo("FPS preference saved: ${config.fps}", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save FPS preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val handDriveFlow: Flow<HandDriveConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_HAND_DRIVE] ?: HandDriveConfig.DEFAULT.value
            HandDriveConfig.entries.find { it.value == value } ?: HandDriveConfig.DEFAULT
        }

    /**
     * Get current hand drive configuration synchronously.
     */
    fun getHandDriveSync(): HandDriveConfig {
        val value = syncCache.getInt(SYNC_CACHE_KEY_HAND_DRIVE, HandDriveConfig.DEFAULT.value)
        return HandDriveConfig.entries.find { it.value == value } ?: HandDriveConfig.DEFAULT
    }

    /**
     * Set hand drive configuration.
     */
    suspend fun setHandDrive(config: HandDriveConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_HAND_DRIVE] = config.value
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_HAND_DRIVE, config.value).apply()
            addPendingChange(ConfigKey.HAND_DRIVE)
            logInfo("Hand drive preference saved: $config (${config.value})", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save hand drive preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val gpsForwardingFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_GPS_FORWARDING] ?: false
        }

    /**
     * Get current GPS forwarding configuration synchronously.
     */
    fun getGpsForwardingSync(): Boolean = syncCache.getBoolean(SYNC_CACHE_KEY_GPS_FORWARDING, false)

    /**
     * Set GPS forwarding configuration.
     */
    suspend fun setGpsForwarding(enabled: Boolean) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_GPS_FORWARDING] = enabled
            }
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_GPS_FORWARDING, enabled).apply()
            addPendingChange(ConfigKey.GPS_FORWARDING)
            logInfo("GPS forwarding preference saved: $enabled", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save GPS forwarding preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    /**
     * Default for the Cluster Integration toggle when the user has NOT made an explicit choice.
     *
     * Dev convenience that mirrors the AltVideo/NavVideo gate (CarlinkManager "[NAVI_GATE]":
     * BuildConfig.DEBUG && isAaosEmulator()) and the immersive defaults
     * ([PlatformDetector.PlatformInfo.requiresImmersiveDefaults]): on a DEBUG APK running on the
     * AAOS emulator, Cluster Integration defaults ON so the Templates Host binding is exercised
     * without re-toggling it after every reinstall (a fresh install wipes the persisted
     * component-enable state). Production APKs and all real hardware default OFF — cluster
     * integration there stays an explicit user opt-in.
     *
     * An explicit user choice in EITHER direction is always honored over this default, because
     * the DataStore key / sync-cache key is only present once a setter has run (no init-time
     * re-sync writes it). Cached with `lazy` because getClusterNavigationSync() is hot (called
     * per-NaviJSON on the USB read thread) and platform/build-type never change at runtime.
     */
    private val clusterNavigationDefault: Boolean by lazy {
        BuildConfig.DEBUG && PlatformDetector.detect(appContext).isAaosEmulator()
    }

    val clusterNavigationFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_CLUSTER_NAVIGATION] ?: clusterNavigationDefault
        }

    /**
     * Get current cluster navigation configuration synchronously.
     */
    fun getClusterNavigationSync(): Boolean =
        syncCache.getBoolean(SYNC_CACHE_KEY_CLUSTER_NAVIGATION, clusterNavigationDefault)

    /**
     * Set cluster navigation configuration.
     * This is a local app setting — does NOT call addPendingChange() (not an adapter config).
     */
    suspend fun setClusterNavigation(enabled: Boolean) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_CLUSTER_NAVIGATION] = enabled
            }
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_CLUSTER_NAVIGATION, enabled).apply()
            logInfo("Cluster navigation preference saved: $enabled", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to save cluster navigation preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    /**
     * Apply the cluster service component enabled/disabled state based on preference.
     * Call this early in Activity.onCreate() so the state is set before Templates Host discovers it.
     *
     * Uses PackageManager.setComponentEnabledSetting with DONT_KILL_APP: the flip takes effect
     * for future component lookups but does NOT force a process restart. Existing in-process
     * bindings to CarlinkClusterService remain active until they are otherwise torn down.
     */
    fun applyClusterComponentState(context: Context) {
        val enabled = getClusterNavigationSync()
        val newState =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "com.carlink.cluster.CarlinkClusterService"),
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Snapshot of every user-configured adapter setting.
     *
     * Assembled on demand by getUserConfigSync() from the SharedPreferences sync cache (for
     * ANR-free main-thread reads) and handed to the message serializer / OPEN-message builder.
     * This is a value type — mutating it does not write anything back to storage; use the
     * individual `set*` suspend methods on AdapterConfigPreference to persist changes.
     * `UserConfig.DEFAULT` mirrors each per-enum `DEFAULT`.
     *
     * Note: UserConfig.callQuality is still populated but currently has no UI (see
     * CallQualityConfig KDoc — "VESTIGIAL / UI-DEAD").
     */
    data class UserConfig(
        /** Audio transfer mode: true = bluetooth, false = adapter (default) */
        val audioTransferMode: Boolean,
        /** Microphone source configuration */
        val micSource: MicSourceConfig,
        /** WiFi band configuration */
        val wifiBand: WiFiBandConfig,
        /** Call quality configuration */
        val callQuality: CallQualityConfig,
        /** Media delay configuration */
        val mediaDelay: MediaDelayConfig,
        /** Video resolution configuration */
        val videoResolution: VideoResolutionConfig,
        /** Frame rate configuration */
        val fps: FpsConfig,
        /** Hand drive mode configuration */
        val handDrive: HandDriveConfig,
        /** GPS forwarding: true = forward vehicle GPS to CarPlay, false = phone uses own GPS */
        val gpsForwarding: Boolean = false,
    ) {
        companion object {
            val DEFAULT =
                UserConfig(
                    audioTransferMode = false, // ADAPTER is default
                    micSource = MicSourceConfig.DEFAULT,
                    wifiBand = WiFiBandConfig.DEFAULT,
                    callQuality = CallQualityConfig.DEFAULT,
                    mediaDelay = MediaDelayConfig.DEFAULT,
                    videoResolution = VideoResolutionConfig.AUTO,
                    fps = FpsConfig.DEFAULT,
                    handDrive = HandDriveConfig.DEFAULT,
                    gpsForwarding = false,
                )
        }
    }

    /**
     * Get all user-configured settings synchronously.
     * Uses SharedPreferences cache to avoid ANR.
     *
     * This is safe to call from the main thread during Activity.onCreate().
     */
    fun getUserConfigSync(): UserConfig {
        val audioSource = getAudioSourceSync()
        val micSource = getMicSourceSync()
        val wifiBand = getWifiBandSync()
        val callQuality = getCallQualitySync()
        val mediaDelay = getMediaDelaySync()
        val videoResolution = getVideoResolutionSync()
        val fps = getFpsSync()
        val handDrive = getHandDriveSync()
        val gpsForwarding = getGpsForwardingSync()
        return UserConfig(
            audioTransferMode = audioSource == AudioSourceConfig.BLUETOOTH,
            micSource = micSource,
            wifiBand = wifiBand,
            callQuality = callQuality,
            mediaDelay = mediaDelay,
            videoResolution = videoResolution,
            fps = fps,
            handDrive = handDrive,
            gpsForwarding = gpsForwarding,
        )
    }

    /**
     * Reset all configuration to defaults.
     * Clears both DataStore and sync cache.
     */
    suspend fun resetToDefaults() {
        try {
            // Clear DataStore
            dataStore.edit { preferences ->
                preferences.remove(KEY_AUDIO_SOURCE_BLUETOOTH)
                preferences.remove(KEY_MIC_SOURCE)
                preferences.remove(KEY_WIFI_BAND)
                preferences.remove(KEY_CALL_QUALITY)
                preferences.remove(KEY_MEDIA_DELAY)
                preferences.remove(KEY_VIDEO_RESOLUTION)
                preferences.remove(KEY_FPS)
                preferences.remove(KEY_HAND_DRIVE)
                preferences.remove(KEY_GPS_FORWARDING)
                preferences.remove(KEY_CLUSTER_NAVIGATION)
                preferences.remove(KEY_HAS_COMPLETED_FIRST_INIT)
                preferences.remove(KEY_LAST_INIT_VERSION_CODE)
                preferences.remove(KEY_PENDING_CHANGES)
            }
            // Clear sync cache
            syncCache
                .edit()
                .apply {
                    remove(SYNC_CACHE_KEY_AUDIO_BLUETOOTH)
                    remove(SYNC_CACHE_KEY_MIC_SOURCE)
                    remove(SYNC_CACHE_KEY_WIFI_BAND)
                    remove(SYNC_CACHE_KEY_CALL_QUALITY)
                    remove(SYNC_CACHE_KEY_MEDIA_DELAY)
                    remove(SYNC_CACHE_KEY_VIDEO_RESOLUTION)
                    remove(SYNC_CACHE_KEY_FPS)
                    remove(SYNC_CACHE_KEY_HAND_DRIVE)
                    remove(SYNC_CACHE_KEY_GPS_FORWARDING)
                    remove(SYNC_CACHE_KEY_CLUSTER_NAVIGATION)
                    remove(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT)
                    remove(SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE)
                    remove(SYNC_CACHE_KEY_PENDING_CHANGES)
                }.apply()
            logInfo(
                "Adapter config preferences reset to defaults" +
                    " (sync cache cleared, next session will run FULL init)",
                tag = "AdapterConfig",
            )
        } catch (e: IOException) {
            logError("Failed to reset adapter config preferences: $e", tag = "AdapterConfig")
            throw e
        }
    }

    /**
     * Adapter initialization mode selected per-connect by [getInitializationMode].
     *
     * Decision order (see getInitializationMode):
     *  1. `!hasCompletedFirstInit`            → FULL
     *  2. `lastInitVersion != currentVersion` → FULL (schema/behavior changes across app upgrades)
     *  3. `pendingChanges.isNotEmpty()`       → MINIMAL_PLUS_CHANGES
     *  4. otherwise                           → MINIMAL_ONLY
     *
     * Consumer surface: MessageSerializer branches on this to decide what goes on the wire.
     * Under MINIMAL_PLUS_CHANGES only a partial set of ConfigKeys is actually consumed — see
     * the HAZARD note on the ConfigKey object for the list of keys that silently no-op here.
     */
    enum class InitMode {
        /** First launch - send full configuration */
        FULL,

        /** Subsequent launch with pending changes - send minimal + changed settings */
        MINIMAL_PLUS_CHANGES,

        /** Subsequent launch with no changes - send minimal only */
        MINIMAL_ONLY,
    }

    /**
     * Check if first initialization has been completed.
     */
    fun hasCompletedFirstInitSync(): Boolean = syncCache.getBoolean(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT, false)

    /**
     * Mark first initialization as completed.
     */
    suspend fun markFirstInitCompleted() {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_HAS_COMPLETED_FIRST_INIT] = true
            }
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT, true).apply()
            logInfo("First initialization marked as completed", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to mark first init completed: $e", tag = "AdapterConfig")
        }
    }

    /**
     * Version code of the app the last time a FULL init was successfully completed.
     * Returns 0 if never recorded. Compared against the current versionCode in
     * [getInitializationMode] to force a FULL init after app upgrades.
     */
    fun getLastInitVersionCode(): Long = syncCache.getLong(SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE, 0L)

    /**
     * Record the app's versionCode as the last successful FULL init. Call only after FULL
     * init actually completes, so that a crash mid-init does not suppress the version-bump
     * re-init on next launch.
     */
    suspend fun updateLastInitVersionCode(versionCode: Long) {
        dataStore.edit { it[KEY_LAST_INIT_VERSION_CODE] = versionCode }
        syncCache.edit().putLong(SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE, versionCode).apply()
    }

    /**
     * Get pending configuration changes synchronously.
     */
    fun getPendingChangesSync(): Set<String> = syncCache.getStringSet(SYNC_CACHE_KEY_PENDING_CHANGES, emptySet()) ?: emptySet()

    /**
     * Add a configuration key to pending changes.
     * Called when user modifies a setting.
     */
    private suspend fun addPendingChange(configKey: String) {
        try {
            dataStore.edit { preferences ->
                val current = preferences[KEY_PENDING_CHANGES] ?: emptySet()
                preferences[KEY_PENDING_CHANGES] = current + configKey
            }
            val current = syncCache.getStringSet(SYNC_CACHE_KEY_PENDING_CHANGES, emptySet()) ?: emptySet()
            syncCache.edit().putStringSet(SYNC_CACHE_KEY_PENDING_CHANGES, current + configKey).apply()
            logInfo("Added pending change: $configKey", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to add pending change: $e", tag = "AdapterConfig")
        }
    }

    /**
     * Clear all pending changes.
     * Called after successful initialization with changes.
     */
    suspend fun clearPendingChanges() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_PENDING_CHANGES)
            }
            syncCache.edit().remove(SYNC_CACHE_KEY_PENDING_CHANGES).apply()
            logInfo("Pending changes cleared", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to clear pending changes: $e", tag = "AdapterConfig")
        }
    }

    /**
     * Determine the initialization mode based on current state.
     *
     * @param currentVersionCode The app's current versionCode for version-triggered full init
     * @return InitMode indicating what configuration to send
     */
    fun getInitializationMode(currentVersionCode: Long): InitMode {
        val hasCompletedFirstInit = hasCompletedFirstInitSync()
        val pendingChanges = getPendingChangesSync()
        val lastInitVersion = getLastInitVersionCode()

        return when {
            !hasCompletedFirstInit -> InitMode.FULL
            lastInitVersion != currentVersionCode -> InitMode.FULL
            pendingChanges.isNotEmpty() -> InitMode.MINIMAL_PLUS_CHANGES
            else -> InitMode.MINIMAL_ONLY
        }
    }

    /**
     * Get initialization info for logging.
     */
    fun getInitializationInfo(currentVersionCode: Long): String {
        val mode = getInitializationMode(currentVersionCode)
        val pendingChanges = getPendingChangesSync()
        val lastInitVersion = getLastInitVersionCode()
        return when (mode) {
            InitMode.FULL -> {
                if (!hasCompletedFirstInitSync()) {
                    "FULL (first launch)"
                } else {
                    "FULL (version $lastInitVersion → $currentVersionCode)"
                }
            }

            InitMode.MINIMAL_PLUS_CHANGES -> {
                "MINIMAL + changes: $pendingChanges"
            }

            InitMode.MINIMAL_ONLY -> {
                "MINIMAL (no changes)"
            }
        }
    }
}
