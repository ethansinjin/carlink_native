package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.Logger
import com.carlink.logging.logError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore instance for immersive mode preference.
 * Following Android best practices: singleton instance at top level.
 */
private val Context.immersiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_immersive_preferences",
)

/**
 * Manages the immersive mode preference for the Carlink app.
 *
 * Overview:
 * Controls display behavior when projecting to GM AAOS infotainment systems.
 * Determines whether the app takes full-screen control (immersive) or defers
 * to the Android Automotive OS for display area management (non-immersive).
 *
 * Display Modes:
 * - Immersive (true): Fullscreen with hidden system UI bars. App controls
 *   entire display surface. Useful for maximum projection area.
 *
 * - Non-Immersive (false, default): AAOS manages display bounds, status bars,
 *   and navigation areas. Recommended for proper GM infotainment integration.
 *
 * Usage:
 * Setting changes persist across app sessions but require restart to apply.
 * Users toggle this via Settings UI when projection display issues occur or
 * when full-screen projection is desired.
 *
 * Technical Details:
 * - Storage: DataStore Preferences (key: 'immersive_mode_enabled')
 * - Default: false (AAOS-managed for compatibility)
 * - Target: Android API 32+ (GM AAOS RPO: IOK)
 * - Restart required: Changes affect MainActivity window flags at launch
 *
 * Matches Flutter: immersive_preference.dart
 */
@Suppress("StaticFieldLeak") // Uses applicationContext, not Activity context - no leak
class ImmersivePreference private constructor(
    context: Context,
) {
    // Store applicationContext to avoid Activity leaks
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: ImmersivePreference? = null

        fun getInstance(context: Context): ImmersivePreference =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImmersivePreference(context.applicationContext).also { INSTANCE = it }
            }

        private val KEY_IMMERSIVE_MODE_ENABLED = booleanPreferencesKey("immersive_mode_enabled")
    }

    private val dataStore = appContext.immersiveDataStore

    /**
     * Flow for observing immersive mode state changes.
     */
    val isEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_IMMERSIVE_MODE_ENABLED] ?: false
        }

    /**
     * Returns whether immersive fullscreen mode is enabled.
     * Returns false by default, allowing AAOS to manage app scaling.
     */
    suspend fun isEnabled(): Boolean =
        try {
            val preferences = dataStore.data.first()
            preferences[KEY_IMMERSIVE_MODE_ENABLED] ?: false
        } catch (e: Exception) {
            logError("Failed to read immersive preference: $e", tag = "ImmersivePreference")
            false
        }

    /**
     * Sets the immersive mode preference.
     * Note: App restart required for changes to take effect.
     */
    suspend fun setEnabled(enabled: Boolean) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_IMMERSIVE_MODE_ENABLED] = enabled
            }
        } catch (e: Exception) {
            logError("Failed to save immersive preference: $e", tag = "ImmersivePreference")
            throw e
        }
    }
}
