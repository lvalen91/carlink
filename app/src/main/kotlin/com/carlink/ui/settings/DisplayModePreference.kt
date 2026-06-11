package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Display mode options for controlling system UI visibility.
 *
 * Controls how the app interacts with AAOS system bars (status bar and navigation bar)
 * during CarPlay/Android Auto projection.
 */
// PERSISTENCE CONTRACT: `value` ints are persisted to disk (DataStore + SharedPreferences
// sync cache). DO NOT change, reassign, or reuse existing numeric values - doing so will
// silently corrupt persisted state for existing users. Declaration order is irrelevant
// (lookup is by value via fromValue), but appending new modes must use fresh integers.
enum class DisplayMode(
    val value: Int,
) {
    /**
     * System UI Visible - Both status bar and navigation bar always visible.
     * AAOS manages display bounds. Recommended for proper GM infotainment integration.
     * This is the default mode.
     */
    SYSTEM_UI_VISIBLE(0),

    /**
     * Status Bar Hidden - Only status bar is hidden, navigation bar remains visible.
     * Provides extra vertical space while keeping navigation accessible.
     */
    STATUS_BAR_HIDDEN(1),

    /**
     * Fullscreen Immersive - Both status bar and navigation bar are hidden.
     * Maximum projection area. Swipe from edge to temporarily reveal system bars.
     */
    FULLSCREEN_IMMERSIVE(2),

    /**
     * Nav Bar Hidden - Only navigation bar/dock is hidden, status bar remains visible.
     * Maximizes width/bottom area while keeping status indicators accessible.
     */
    NAV_BAR_HIDDEN(3),
    ;

    companion object {
        /**
         * Convert an integer value to DisplayMode.
         * Returns SYSTEM_UI_VISIBLE for unknown values (safe default).
         */
        fun fromValue(value: Int): DisplayMode = entries.find { it.value == value } ?: SYSTEM_UI_VISIBLE

        /**
         * Platform-aware default display mode (used when no user preference is
         * persisted). gminfo37 defaults to FULLSCREEN_IMMERSIVE for maximum projection
         * area; every other platform — including the AAOS emulator — keeps the legacy
         * SYSTEM_UI_VISIBLE default. User can still override via the settings dialog;
         * this only affects the "no preference set yet" first-run / cleared-data path.
         */
        fun platformDefault(context: android.content.Context): DisplayMode =
            if (com.carlink.platform.PlatformDetector.detect(context).requiresImmersiveDefaults()) {
                FULLSCREEN_IMMERSIVE
            } else {
                SYSTEM_UI_VISIBLE
            }
    }
}

// PERSISTENCE CONTRACT: on-disk DataStore filename. DO NOT RENAME without a proper
// migration - renaming orphans existing users' persisted display mode. (Historical note:
// the active migration path reads the legacy boolean from the SharedPreferences sync
// cache below, NOT from this DataStore file, so this filename is retained purely to
// preserve access to the user's previously-written int value.)
private val Context.displayModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_immersive_preferences",
)

/**
 * CROSS-REFERENCE WARNING: LoggingPreferences.kt (see ~lines 33-35) points to this class
 * as a "migration template". Before copying this pattern elsewhere, be aware of the known
 * defects documented below: non-atomic DataStore + sync-cache writes in setDisplayMode,
 * incomplete DataStore-legacy-key migration coverage, non-self-healing fallback in
 * displayModeFlow, and main-thread SharedPreferences I/O in init. Propagating this pattern
 * will propagate these defects.
 *
 * Display mode preference with DataStore + SharedPreferences sync cache for ANR-free startup reads.
 *
 * Migrates from legacy boolean immersive_mode_enabled preference, but ONLY from the
 * SharedPreferences sync cache copy (see migrateFromBooleanIfNeeded). Users whose legacy
 * boolean lived only in DataStore (KEY_IMMERSIVE_MODE_ENABLED_LEGACY) are NOT covered by
 * the one-shot migration; displayModeFlow has a read-time fallback for them but it does
 * not write back (see note on that field).
 *
 * NOTE: DataStore writes and sync cache writes in setDisplayMode are NOT atomic across the
 * pair. A crash between the two leaves the caches inconsistent until the next successful
 * write. Cold starts read the (possibly stale) sync cache until displayModeFlow catches up.
 */
@Suppress("StaticFieldLeak")
class DisplayModePreference private constructor(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: DisplayModePreference? = null

        fun getInstance(context: Context): DisplayModePreference =
            instance ?: synchronized(this) {
                // `this` here refers to the companion object instance - the standard
                // DCL idiom for a process-wide singleton lock.
                instance ?: DisplayModePreference(context.applicationContext).also { instance = it }
            }

        // New int key for display mode
        private val KEY_DISPLAY_MODE = intPreferencesKey("display_mode")

        // VESTIGIAL: legacy boolean DataStore key. Read-only fallback path in
        // displayModeFlow; no active code writes it. Retained to support any historical
        // installs whose boolean lived in DataStore (not covered by sync-cache migration).
        private val KEY_IMMERSIVE_MODE_ENABLED_LEGACY = booleanPreferencesKey("immersive_mode_enabled")

        // PERSISTENCE CONTRACT: on-disk SharedPreferences filename. DO NOT RENAME without a
        // migration - renaming silently resets the migration flag (re-triggering migration
        // and defaulting to SYSTEM_UI_VISIBLE) and discards the mirrored display mode.
        private const val SYNC_CACHE_PREFS_NAME = "carlink_immersive_sync_cache"
        private const val SYNC_CACHE_KEY_DISPLAY_MODE = "display_mode"
        private const val SYNC_CACHE_KEY_MIGRATED = "display_mode_migrated"

        // Legacy key for migration detection
        private const val SYNC_CACHE_KEY_ENABLED_LEGACY = "immersive_mode_enabled"
    }

    private val dataStore = appContext.displayModeDataStore

    // SharedPreferences sync cache for instant startup reads
    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    init {
        // HAZARD: this performs SharedPreferences I/O on the calling thread. The first
        // getInstance() call on the main thread will block on disk parse of the sync cache
        // file. Accepted trade-off: the file is tiny so an ANR is unlikely in practice,
        // but StrictMode will flag this. Do NOT grow the sync cache payload.
        migrateFromBooleanIfNeeded()
    }

    /**
     * Migrate from old boolean preference to new int preference.
     *
     * Only runs once - uses SYNC_CACHE_KEY_MIGRATED in the sync cache to track status.
     *
     * COVERAGE LIMITATION: this reads the legacy boolean exclusively from the
     * SharedPreferences sync cache (SYNC_CACHE_KEY_ENABLED_LEGACY). If a pre-migration
     * install persisted the legacy boolean only in DataStore (via
     * KEY_IMMERSIVE_MODE_ENABLED_LEGACY) and never mirrored it into the sync cache,
     * this migration silently resets the user to the default SYSTEM_UI_VISIBLE.
     * displayModeFlow contains a read-time fallback for that case but never writes back.
     */
    private fun migrateFromBooleanIfNeeded() {
        if (syncCache.getBoolean(SYNC_CACHE_KEY_MIGRATED, false)) {
            return // Already migrated
        }

        // Check if old boolean preference exists
        if (syncCache.contains(SYNC_CACHE_KEY_ENABLED_LEGACY)) {
            val wasImmersive = syncCache.getBoolean(SYNC_CACHE_KEY_ENABLED_LEGACY, false)
            val newMode =
                if (wasImmersive) {
                    DisplayMode.FULLSCREEN_IMMERSIVE
                } else {
                    DisplayMode.SYSTEM_UI_VISIBLE
                }

            // Write new value to sync cache
            syncCache
                .edit()
                .putInt(SYNC_CACHE_KEY_DISPLAY_MODE, newMode.value)
                .putBoolean(SYNC_CACHE_KEY_MIGRATED, true)
                .apply()

            logInfo(
                "Migrated display mode preference: $wasImmersive -> ${newMode.name}",
                tag = "DisplayModePreference",
            )
        } else {
            // No old preference: mark migrated using the platform-aware default
            // (gminfo37 → FULLSCREEN_IMMERSIVE; everything else, including the AAOS
            // emulator → SYSTEM_UI_VISIBLE legacy default).
            syncCache
                .edit()
                .putInt(SYNC_CACHE_KEY_DISPLAY_MODE, DisplayMode.platformDefault(appContext).value)
                .putBoolean(SYNC_CACHE_KEY_MIGRATED, true)
                .apply()
        }
    }

    val displayModeFlow: Flow<DisplayMode> =
        dataStore.data.map { preferences ->
            // Try new key first, fall back to legacy boolean in DataStore.
            // NOTE: the legacy-boolean branch is NOT self-healing - it never writes the
            // migrated int back to KEY_DISPLAY_MODE. Every emission pays the lookup cost,
            // and an affected user will stay on the fallback path indefinitely (until the
            // next setDisplayMode() call writes KEY_DISPLAY_MODE explicitly).
            preferences[KEY_DISPLAY_MODE]?.let { DisplayMode.fromValue(it) }
                ?: preferences[KEY_IMMERSIVE_MODE_ENABLED_LEGACY]?.let { wasImmersive ->
                    if (wasImmersive) DisplayMode.FULLSCREEN_IMMERSIVE else DisplayMode.SYSTEM_UI_VISIBLE
                }
                ?: DisplayMode.platformDefault(appContext)
        }

    /**
     * Returns the current display mode synchronously.
     * Uses SharedPreferences sync cache to avoid ANR.
     *
     * Generally safe to call from the main thread during Activity.onCreate(), with one
     * caveat: the FIRST access to the sync cache in a process blocks on disk parse of
     * the prefs file. The file is tiny so this is usually negligible, but StrictMode
     * will still flag it on the main thread.
     */
    fun getDisplayModeSync(): DisplayMode =
        DisplayMode.fromValue(
            syncCache.getInt(SYNC_CACHE_KEY_DISPLAY_MODE, DisplayMode.platformDefault(appContext).value),
        )

    /**
     * Sets the display mode preference.
     *
     * Updates DataStore (source of truth) then the SharedPreferences sync cache. These two
     * writes are NOT atomic with respect to each other: a process crash or power loss
     * between them leaves DataStore and the sync cache inconsistent, and the next cold
     * start will read the stale sync cache value via getDisplayModeSync() until
     * displayModeFlow emits the authoritative DataStore value.
     *
     * Note: App restart required for changes to take full effect.
     */
    suspend fun setDisplayMode(mode: DisplayMode) {
        try {
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_DISPLAY_MODE] = mode.value
            }
            // Update sync cache for instant reads on next startup.
            // HAZARD: a crash between the DataStore write above and this .apply() leaves
            // the two stores disagreeing. See KDoc above.
            syncCache.edit().putInt(SYNC_CACHE_KEY_DISPLAY_MODE, mode.value).apply()
            logInfo(
                "Display mode preference saved: ${mode.name} (sync cache updated)",
                tag = "DisplayModePreference",
            )
        } catch (e: Exception) {
            logError("Failed to save display mode preference: $e", tag = "DisplayModePreference")
            throw e
        }
    }
}
