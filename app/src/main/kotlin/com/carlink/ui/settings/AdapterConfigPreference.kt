package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import java.io.IOException

private val Context.adapterConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_adapter_config_preferences",
)

/**
 * Slim adapter-init bookkeeping for the personal CarPlay-only (cp-stripped) build.
 *
 * All per-setting user configuration (audio/mic/wifi/resolution/fps/hand-drive/gps/cluster)
 * was removed in this variant — the adapter config is hardcoded (see [com.carlink.protocol.AdapterConfig]
 * defaults + MainActivity). This class now only tracks whether a FULL init has run for the
 * current app version, so each connection sends a FULL init on first install / version bump
 * and a MINIMAL init on every subsequent session (the adapter persists the rest in flash).
 *
 * Two-tier storage: DataStore (source of truth) + a SharedPreferences sync cache for
 * ANR-free main-thread reads during Activity.onCreate. The two writes are not transactional;
 * a crash between them leaves the sync cache trailing until the next successful write.
 */
@Suppress("StaticFieldLeak")
class AdapterConfigPreference private constructor(
    context: Context,
) {
    // appContext is the application Context — safe to hold from a singleton.
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: AdapterConfigPreference? = null

        fun getInstance(context: Context): AdapterConfigPreference =
            instance ?: synchronized(this) {
                instance ?: AdapterConfigPreference(context.applicationContext).also { instance = it }
            }

        private val KEY_HAS_COMPLETED_FIRST_INIT = booleanPreferencesKey("has_completed_first_init")
        private val KEY_LAST_INIT_VERSION_CODE = longPreferencesKey("last_init_version_code")

        // Hardware uuid of the adapter we last FULL-staged. A different uuid means a new/swapped
        // adapter that needs a FULL init to be staged (see getInitializationMode).
        private val KEY_LAST_STAGED_UUID = stringPreferencesKey("last_staged_adapter_uuid")

        private const val SYNC_CACHE_PREFS_NAME = "carlink_adapter_config_sync_cache"
        private const val SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT = "has_completed_first_init"
        private const val SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE = "last_init_version_code"
        private const val SYNC_CACHE_KEY_LAST_STAGED_UUID = "last_staged_adapter_uuid"
    }

    private val dataStore = appContext.adapterConfigDataStore

    private val syncCache =
        appContext.getSharedPreferences(SYNC_CACHE_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Adapter initialization mode selected per-connect by [getInitializationMode].
     *
     *  1. `!hasCompletedFirstInit`            → FULL
     *  2. `lastInitVersion != currentVersion` → FULL (after an app upgrade)
     *  3. otherwise                           → MINIMAL_ONLY
     */
    enum class InitMode {
        /** First launch / version bump — send full configuration. */
        FULL,

        /** Subsequent launch — adapter retains its persisted settings; send minimal only. */
        MINIMAL_ONLY,
    }

    fun hasCompletedFirstInitSync(): Boolean =
        syncCache.getBoolean(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT, false)

    suspend fun markFirstInitCompleted() {
        try {
            dataStore.edit { it[KEY_HAS_COMPLETED_FIRST_INIT] = true }
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT, true).apply()
            logInfo("First initialization marked as completed", tag = "AdapterConfig")
        } catch (e: IOException) {
            logError("Failed to mark first init completed: $e", tag = "AdapterConfig")
        }
    }

    /** Version code of the app the last time a FULL init was successfully completed (0 if never). */
    fun getLastInitVersionCode(): Long =
        syncCache.getLong(SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE, 0L)

    /** Record the app's versionCode as the last successful FULL init. Call only after FULL completes. */
    suspend fun updateLastInitVersionCode(versionCode: Long) {
        dataStore.edit { it[KEY_LAST_INIT_VERSION_CODE] = versionCode }
        syncCache.edit().putLong(SYNC_CACHE_KEY_LAST_INIT_VERSION_CODE, versionCode).apply()
    }

    /** Hardware uuid of the adapter last FULL-staged (null if never). */
    fun getLastStagedUuid(): String? = syncCache.getString(SYNC_CACHE_KEY_LAST_STAGED_UUID, null)

    /** Record the adapter uuid just FULL-staged. Call only after a successful FULL init. */
    suspend fun updateLastStagedUuid(uuid: String) {
        dataStore.edit { it[KEY_LAST_STAGED_UUID] = uuid }
        syncCache.edit().putString(SYNC_CACHE_KEY_LAST_STAGED_UUID, uuid).apply()
    }

    /**
     * Decide the init mode for this connection. [adapterUuid] is the hardware uuid from the
     * adapter's boxInfo (null if not yet known / timed out — then only install/version drive it).
     *
     *  1. `!hasCompletedFirstInit`              → FULL (first install)
     *  2. `lastInitVersion != currentVersion`   → FULL (app upgrade)
     *  3. `adapterUuid != lastStagedUuid`        → FULL (new/swapped/unrecognized adapter)
     *  4. otherwise                             → MINIMAL_ONLY
     */
    fun getInitializationMode(
        currentVersionCode: Long,
        adapterUuid: String? = null,
    ): InitMode =
        when {
            !hasCompletedFirstInitSync() -> InitMode.FULL
            getLastInitVersionCode() != currentVersionCode -> InitMode.FULL
            adapterUuid != null && adapterUuid != getLastStagedUuid() -> InitMode.FULL
            else -> InitMode.MINIMAL_ONLY
        }

    fun getInitializationInfo(
        currentVersionCode: Long,
        adapterUuid: String? = null,
    ): String =
        when (getInitializationMode(currentVersionCode, adapterUuid)) {
            InitMode.FULL ->
                when {
                    !hasCompletedFirstInitSync() -> "FULL (first launch)"
                    getLastInitVersionCode() != currentVersionCode ->
                        "FULL (version ${getLastInitVersionCode()} → $currentVersionCode)"
                    else -> "FULL (new/unrecognized adapter uuid=$adapterUuid, staged=${getLastStagedUuid()})"
                }

            InitMode.MINIMAL_ONLY -> "MINIMAL (no changes)"
        }
}
