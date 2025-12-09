package com.carlink.logging

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Service for exporting files using Android's Storage Access Framework (SAF).
 *
 * This service provides reliable file export functionality by using Android's
 * built-in Documents UI (Intent.ACTION_CREATE_DOCUMENT). Unlike the share sheet
 * approach, this allows:
 * - User to select exact save location
 * - Direct file write verification
 * - No dependency on third-party apps
 *
 * Matches Flutter: file_export_service.dart
 *
 * Usage in Compose:
 * ```kotlin
 * val fileExportService = remember { FileExportService() }
 * val exportLauncher = fileExportService.registerForActivityResult(activity)
 *
 * // To export:
 * scope.launch {
 *     val success = fileExportService.exportFile(context, file, exportLauncher)
 * }
 * ```
 */
class FileExportService {
    private var pendingExportData: ByteArray? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null

    /**
     * Registers the activity result launcher for document creation.
     * Must be called during Activity/Fragment initialization (before onStart).
     *
     * @param activity The ComponentActivity to register with
     * @return ActivityResultLauncher for creating documents
     */
    fun registerForActivityResult(activity: ComponentActivity): ActivityResultLauncher<String> =
        activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            handleDocumentCreated(activity, uri)
        }

    /**
     * Handles the result from the document picker.
     */
    private fun handleDocumentCreated(
        context: Context,
        uri: Uri?,
    ) {
        val callback = pendingCallback
        val data = pendingExportData

        // Clear pending state
        pendingCallback = null
        pendingExportData = null

        if (uri == null) {
            logInfo("[FILE_EXPORT] User cancelled document picker", tag = Logger.Tags.FILE_LOG)
            callback?.invoke(false)
            return
        }

        if (data == null) {
            logError("[FILE_EXPORT] No pending data to write", tag = Logger.Tags.FILE_LOG)
            callback?.invoke(false)
            return
        }

        // Write data to the selected URI
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            } ?: throw Exception("Failed to open output stream")

            logInfo("[FILE_EXPORT] Successfully wrote ${data.size} bytes to $uri", tag = Logger.Tags.FILE_LOG)
            callback?.invoke(true)
        } catch (e: Exception) {
            logError("[FILE_EXPORT] Write failed: ${e.message}", tag = Logger.Tags.FILE_LOG)
            callback?.invoke(false)
        }
    }

    /**
     * Exports a file to a user-selected location.
     *
     * @param file The file to export
     * @param launcher The ActivityResultLauncher from registerForActivityResult
     * @param callback Called with true on success, false on cancel/failure
     */
    fun exportFile(
        file: File,
        launcher: ActivityResultLauncher<String>,
        callback: (Boolean) -> Unit,
    ) {
        try {
            // Read file bytes
            val bytes = file.readBytes()
            logInfo("[FILE_EXPORT] Read ${bytes.size} bytes from ${file.name}", tag = Logger.Tags.FILE_LOG)

            // Store pending data and callback
            pendingExportData = bytes
            pendingCallback = callback

            // Launch document picker with suggested filename
            logInfo("[FILE_EXPORT] Launching document picker for: ${file.name}", tag = Logger.Tags.FILE_LOG)
            launcher.launch(file.name)
        } catch (e: Exception) {
            logError("[FILE_EXPORT] Failed to read file: ${e.message}", tag = Logger.Tags.FILE_LOG)
            callback(false)
        }
    }

    /**
     * Exports raw bytes to a user-selected location.
     *
     * @param fileName Suggested filename for the document
     * @param bytes The data to export
     * @param launcher The ActivityResultLauncher from registerForActivityResult
     * @param callback Called with true on success, false on cancel/failure
     */
    fun exportBytes(
        fileName: String,
        bytes: ByteArray,
        launcher: ActivityResultLauncher<String>,
        callback: (Boolean) -> Unit,
    ) {
        logInfo("[FILE_EXPORT] Preparing to export $fileName (${bytes.size} bytes)", tag = Logger.Tags.FILE_LOG)

        // Store pending data and callback
        pendingExportData = bytes
        pendingCallback = callback

        // Launch document picker with suggested filename
        logInfo("[FILE_EXPORT] Launching document picker for: $fileName", tag = Logger.Tags.FILE_LOG)
        launcher.launch(fileName)
    }

    companion object {
        @Volatile
        private var INSTANCE: FileExportService? = null

        fun getInstance(): FileExportService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FileExportService().also { INSTANCE = it }
            }
    }
}
