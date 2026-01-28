package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.debugModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_debug_mode_preferences",
)

/**
 * Debug mode preference with DataStore + SharedPreferences sync cache.
 *
 * Controls visibility of developer tabs (Logs, Playback, Record) in Settings.
 * Default: OFF (only Control tab visible)
 */
@Suppress("StaticFieldLeak")
class DebugModePreference private constructor(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: DebugModePreference? = null

        fun getInstance(context: Context): DebugModePreference =
            instance ?: synchronized(this) {
                instance ?: DebugModePreference(context.applicationContext).also { instance = it }
            }

        private val KEY_DEBUG_MODE_ENABLED = booleanPreferencesKey("debug_mode_enabled")

        // SharedPreferences keys for sync cache (ANR prevention)
        private const val SYNC_CACHE_PREFS_NAME = "carlink_debug_mode_sync_cache"
        private const val SYNC_CACHE_KEY_DEBUG_MODE = "debug_mode_enabled"
    }

    private val dataStore = appContext.debugModeDataStore

    // SharedPreferences sync cache for instant startup reads
    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    /**
     * Flow of debug mode enabled state for reactive UI updates.
     */
    val debugModeEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_DEBUG_MODE_ENABLED] ?: false
        }

    /**
     * Returns the current debug mode state synchronously.
     * Uses SharedPreferences sync cache to avoid ANR.
     *
     * This is safe to call from the main thread.
     */
    fun isDebugModeEnabledSync(): Boolean =
        syncCache.getBoolean(SYNC_CACHE_KEY_DEBUG_MODE, false)

    /**
     * Returns the current debug mode state.
     *
     * Note: Prefer isDebugModeEnabledSync() for startup reads to avoid ANR.
     */
    suspend fun isDebugModeEnabled(): Boolean =
        try {
            val preferences = dataStore.data.first()
            preferences[KEY_DEBUG_MODE_ENABLED] ?: false
        } catch (e: Exception) {
            logError("Failed to read debug mode preference: $e", tag = "DebugModePreference")
            false
        }

    /**
     * Sets the debug mode preference.
     * Updates both DataStore and sync cache atomically.
     */
    suspend fun setDebugModeEnabled(enabled: Boolean) {
        try {
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_DEBUG_MODE_ENABLED] = enabled
            }
            // Update sync cache for instant reads
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_DEBUG_MODE, enabled).apply()
            logInfo(
                "Debug mode preference saved: $enabled",
                tag = "DebugModePreference",
            )
        } catch (e: Exception) {
            logError("Failed to save debug mode preference: $e", tag = "DebugModePreference")
            throw e
        }
    }
}
