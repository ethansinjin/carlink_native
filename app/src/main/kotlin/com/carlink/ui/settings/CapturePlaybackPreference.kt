package com.carlink.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Context.capturePlaybackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "capture_playback_prefs",
)

/**
 * Metadata extracted from capture files for display before playback.
 */
data class CaptureMetadata(
    val jsonFileSize: Long,
    val binFileSize: Long,
    val durationMs: Long,
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
) {
    val totalFileSize: Long get() = jsonFileSize + binFileSize
}

/**
 * Preference store for Capture Playback settings.
 *
 * Manages:
 * - Playback mode enabled/disabled
 * - Selected capture file URI
 */
class CapturePlaybackPreference private constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_PLAYBACK_ENABLED = booleanPreferencesKey("playback_enabled")
        private val KEY_CAPTURE_JSON_URI = stringPreferencesKey("capture_json_uri")
        private val KEY_CAPTURE_BIN_URI = stringPreferencesKey("capture_bin_uri")

        @Volatile
        private var instance: CapturePlaybackPreference? = null

        fun getInstance(context: Context): CapturePlaybackPreference =
            instance ?: synchronized(this) {
                instance ?: CapturePlaybackPreference(context.applicationContext).also {
                    instance = it
                }
            }
    }

    val playbackEnabledFlow: Flow<Boolean> =
        context.capturePlaybackDataStore.data.map { prefs ->
            prefs[KEY_PLAYBACK_ENABLED] ?: false
        }

    val captureJsonUriFlow: Flow<Uri?> =
        context.capturePlaybackDataStore.data.map { prefs ->
            prefs[KEY_CAPTURE_JSON_URI]?.let { it.toUri() }
        }

    val captureBinUriFlow: Flow<Uri?> =
        context.capturePlaybackDataStore.data.map { prefs ->
            prefs[KEY_CAPTURE_BIN_URI]?.let { it.toUri() }
        }

    suspend fun setPlaybackEnabled(enabled: Boolean) {
        context.capturePlaybackDataStore.edit { prefs ->
            prefs[KEY_PLAYBACK_ENABLED] = enabled
        }
    }

    suspend fun setCaptureFiles(
        jsonUri: Uri?,
        binUri: Uri?,
    ) {
        context.capturePlaybackDataStore.edit { prefs ->
            if (jsonUri != null) {
                prefs[KEY_CAPTURE_JSON_URI] = jsonUri.toString()
            } else {
                prefs.remove(KEY_CAPTURE_JSON_URI)
            }
            if (binUri != null) {
                prefs[KEY_CAPTURE_BIN_URI] = binUri.toString()
            } else {
                prefs.remove(KEY_CAPTURE_BIN_URI)
            }
        }
    }

    suspend fun clearCaptureFiles() {
        context.capturePlaybackDataStore.edit { prefs ->
            prefs.remove(KEY_CAPTURE_JSON_URI)
            prefs.remove(KEY_CAPTURE_BIN_URI)
        }
    }

    /**
     * Synchronous read for initialization (uses runBlocking - only use on init).
     */
    fun getPlaybackEnabledSync(): Boolean =
        runBlocking {
            context.capturePlaybackDataStore.data.first()[KEY_PLAYBACK_ENABLED] ?: false
        }

    fun getCaptureJsonUriSync(): Uri? =
        runBlocking {
            context.capturePlaybackDataStore.data.first()[KEY_CAPTURE_JSON_URI]?.let { it.toUri() }
        }

    fun getCaptureBinUriSync(): Uri? =
        runBlocking {
            context.capturePlaybackDataStore.data.first()[KEY_CAPTURE_BIN_URI]?.let { it.toUri() }
        }

    /**
     * Read capture metadata from selected files.
     *
     * Extracts:
     * - File sizes for both JSON and BIN files
     * - Session duration from JSON metadata
     * - Session ID and timestamps
     *
     * @param jsonUri URI of the .json metadata file
     * @param binUri URI of the .bin data file
     * @return CaptureMetadata if successful, null if files cannot be read
     */
    suspend fun readCaptureMetadata(
        jsonUri: Uri,
        binUri: Uri,
    ): CaptureMetadata? = withContext(Dispatchers.IO) {
        try {
            // Get file sizes
            val jsonFileSize = getFileSize(jsonUri)
            val binFileSize = getFileSize(binUri)

            // Read JSON to get session metadata (only read the session object, not all packets)
            val jsonContent = context.contentResolver.openInputStream(jsonUri)?.use { stream ->
                // Read only first 2KB to get session info (avoid loading all packets)
                val buffer = ByteArray(2048)
                val bytesRead = stream.read(buffer)
                if (bytesRead > 0) String(buffer, 0, bytesRead) else null
            } ?: return@withContext null

            // Parse session info from JSON
            // The JSON starts with { "version": "1.0", "session": { ... }, "config": ..., "packets": [...] }
            // We only need the session object
            val sessionStart = jsonContent.indexOf("\"session\"")
            if (sessionStart == -1) return@withContext null

            // Find the session object bounds
            val sessionObjStart = jsonContent.indexOf('{', sessionStart)
            if (sessionObjStart == -1) return@withContext null

            // Count braces to find end of session object
            var braceCount = 0
            var sessionObjEnd = sessionObjStart
            for (i in sessionObjStart until jsonContent.length) {
                when (jsonContent[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            sessionObjEnd = i + 1
                            break
                        }
                    }
                }
            }

            val sessionJson = jsonContent.substring(sessionObjStart, sessionObjEnd)
            val sessionObj = JSONObject(sessionJson)

            CaptureMetadata(
                jsonFileSize = jsonFileSize,
                binFileSize = binFileSize,
                durationMs = sessionObj.optLong("durationMs", 0L),
                sessionId = sessionObj.optString("id", ""),
                startedAt = sessionObj.optString("started", ""),
                endedAt = sessionObj.optString("ended", ""),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get file size from URI using ContentResolver.
     */
    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
