package com.carlink.logging

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Persistence contract: this name becomes the on-disk filename
// (files/datastore/carlink_logging_preferences.preferences_pb). DO NOT rename without
// a migration — renaming silently orphans every existing user's preferences.
private val Context.loggingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_logging_preferences",
)

/**
 * Persists logging preferences across sessions using AndroidX DataStore (Preferences).
 *
 * Two stored values:
 *  - carlink_log_level:       Int (LogPreset.ordinal — see hazard note in companion)
 *  - carlink_logging_enabled: Boolean (file logging on/off, opt-in; defaults to false)
 *
 * Cold-start default (no preferences yet written): LogPreset.SILENT + logging disabled.
 * Intentional — minimum noise and no disk writes until the user opts in via LogsTab.
 *
 * Concurrency: DataStore serializes edit() calls internally, so the two suspend setters
 * can race freely from different coroutines without app-side locking.
 *
 * No migration code — this file is DataStore-native. If a schema change is needed in
 * the future, see DisplayModePreference.kt for the SharedPreferences → DataStore
 * migration template.
 */
// Singleton stores appContext (applicationContext), not an Activity, so the
// static-field-leak rule's concern doesn't apply.
@Suppress("StaticFieldLeak")
class LoggingPreferences private constructor(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: LoggingPreferences? = null

        fun getInstance(context: Context): LoggingPreferences =
            instance ?: synchronized(this) {
                instance ?: LoggingPreferences(context.applicationContext).also { instance = it }
            }

        // HAZARD: stored as LogPreset.ordinal. Reordering or removing non-terminal
        // entries in LogPreset silently reinterprets every persisted user preference.
        // fromIndex() guards only out-of-range (returns SILENT); it cannot detect a
        // reorder. Freeze LogPreset ordering, or migrate to stringPreferencesKey +
        // LogPreset.name for schema stability.
        private val KEY_LOG_LEVEL = intPreferencesKey("carlink_log_level")
        private val KEY_LOGGING_ENABLED = booleanPreferencesKey("carlink_logging_enabled")
    }

    private val dataStore = appContext.loggingDataStore

    val logLevelFlow: Flow<LogPreset> =
        dataStore.data.map { preferences ->
            val levelIndex = preferences[KEY_LOG_LEVEL] ?: LogPreset.SILENT.ordinal
            LogPreset.fromIndex(levelIndex)
        }

    val loggingEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_LOGGING_ENABLED] ?: false
        }

    suspend fun setLogLevel(level: LogPreset) {
        dataStore.edit { preferences ->
            preferences[KEY_LOG_LEVEL] = level.ordinal
        }
    }

    suspend fun setLoggingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_LOGGING_ENABLED] = enabled
        }
    }
}
