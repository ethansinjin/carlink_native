package com.carlink.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.carlink.protocol.KnownDevices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.carlink.USB_PERMISSION"

/**
 * USB Device Wrapper for Carlinkit Adapter Communication
 *
 * Provides a high-level interface for USB device operations including:
 * - Device discovery and permission handling
 * - Connection lifecycle management
 * - Bulk transfer operations for data exchange
 * - Interface claiming and endpoint configuration
 *
 * Ported from: lib/driver/usb/usb_device_wrapper.dart
 */
class UsbDeviceWrapper(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val logCallback: (String) -> Unit,
) {
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val _isOpened = AtomicBoolean(false)
    private val _isReadingLoopActive = AtomicBoolean(false)

    // Recording callback for capturing all USB traffic (set before adapter init)
    @Volatile
    private var recordingCallback: RecordingCallback? = null

    val isOpened: Boolean get() = _isOpened.get()
    val isReadingLoopActive: Boolean get() = _isReadingLoopActive.get()
    val isRecording: Boolean get() = recordingCallback != null

    val vendorId: Int get() = device.vendorId
    val productId: Int get() = device.productId
    val deviceName: String get() = device.deviceName

    // Performance tracking
    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private var sendCount: Int = 0
    private var receiveCount: Int = 0
    private var sendErrors: Int = 0
    private var receiveErrors: Int = 0

    /**
     * Set the recording callback for capturing USB traffic.
     * Call this BEFORE adapter initialization to capture all communication.
     *
     * @param callback The recording callback, or null to disable recording
     */
    fun setRecordingCallback(callback: RecordingCallback?) {
        this.recordingCallback = callback
        log("Recording ${if (callback != null) "enabled" else "disabled"}")
    }

    /**
     * Check if we have permission to access the USB device.
     */
    fun hasPermission(): Boolean = usbManager.hasPermission(device)

    /**
     * Request USB permission from the user.
     * This will show a system dialog asking the user to grant permission.
     *
     * @param timeoutMs Timeout in milliseconds to wait for user response
     * @return true if permission was granted, false if denied or timeout
     */
    suspend fun requestPermission(timeoutMs: Long = 30_000L): Boolean {
        if (usbManager.hasPermission(device)) {
            log("Permission already granted for ${device.deviceName}")
            return true
        }

        log("Requesting USB permission for ${device.deviceName}...")

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            ctx: Context,
                            intent: Intent,
                        ) {
                            if (ACTION_USB_PERMISSION == intent.action) {
                                try {
                                    context.unregisterReceiver(this)
                                } catch (_: IllegalArgumentException) {
                                    // Already unregistered
                                }

                                val granted =
                                    intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false,
                                    )
                                log("USB permission ${if (granted) "granted" else "denied"}")

                                if (continuation.isActive) {
                                    continuation.resume(granted)
                                }
                            }
                        }
                    }

                // Register receiver using ContextCompat for API compatibility
                // RECEIVER_NOT_EXPORTED ensures only this app can send permission broadcasts
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                // Create pending intent with explicit Intent (package set) for Android 12+ security
                // FLAG_MUTABLE is required because UsbManager adds extras to the intent
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                usbManager.requestPermission(device, pendingIntent)

                // Cleanup on cancellation
                continuation.invokeOnCancellation {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: IllegalArgumentException) {
                        // Already unregistered
                    }
                }
            }
        } ?: run {
            log("USB permission request timed out")
            false
        }
    }

    /**
     * Open the USB device and claim the interface.
     *
     * @return true if device was opened successfully
     */
    fun open(): Boolean {
        if (_isOpened.get()) {
            log("Device already opened")
            return true
        }

        // Check permission
        if (!usbManager.hasPermission(device)) {
            log("No permission for device ${device.deviceName}")
            return false
        }

        // Open connection
        connection = usbManager.openDevice(device)
        if (connection == null) {
            log("Failed to open device connection")
            return false
        }

        // Find and claim the bulk transfer interface
        if (!claimBulkInterface()) {
            connection?.close()
            connection = null
            return false
        }

        _isOpened.set(true)
        log("Device opened: VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)}")
        return true
    }

    /**
     * Request permission if needed and open the USB device.
     * This is a convenience suspend function that combines requestPermission() and open().
     *
     * @return true if device was opened successfully
     */
    suspend fun openWithPermission(): Boolean {
        if (!hasPermission()) {
            if (!requestPermission()) {
                return false
            }
        }
        return open()
    }

    /**
     * Close the USB device and release resources.
     */
    fun close() {
        stopReadingLoop()

        claimedInterface?.let { iface ->
            try {
                connection?.releaseInterface(iface)
            } catch (e: Exception) {
                log("Error releasing interface: ${e.message}")
            }
        }
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null

        connection?.close()
        connection = null
        _isOpened.set(false)

        log("Device closed (sent: $sendCount/$bytesSent bytes, received: $receiveCount/$bytesReceived bytes)")
    }

    /**
     * Reset the USB device.
     *
     * @return true if reset was successful
     */
    fun reset(): Boolean {
        connection ?: return false

        return try {
            // Note: controlTransfer with USB_DEVICE_RESET is not directly available
            // Using close/reopen pattern for reset
            close()
            Thread.sleep(500)
            open()
        } catch (e: Exception) {
            log("Device reset failed: ${e.message}")
            false
        }
    }

    /**
     * Write data to the USB device.
     *
     * @param data Data to send
     * @param timeout Timeout in milliseconds
     * @return Number of bytes actually sent, or -1 on error
     */
    fun write(
        data: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot write: device not open")
                return -1
            }

        val endpoint =
            outEndpoint ?: run {
                log("Cannot write: no OUT endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, data, data.size, timeout)
            if (result >= 0) {
                bytesSent += result
                sendCount++

                // Record outgoing packet if recording is active
                recordingCallback?.let { recorder ->
                    try {
                        // Record complete packet (like pi-carplay)
                        if (data.size >= 16) {
                            // Parse type from header (bytes 8-11, little-endian)
                            val type = (data[8].toInt() and 0xFF) or
                                ((data[9].toInt() and 0xFF) shl 8) or
                                ((data[10].toInt() and 0xFF) shl 16) or
                                ((data[11].toInt() and 0xFF) shl 24)
                            recorder.onPacket("OUT", type, data.copyOf())
                        }
                    } catch (e: Exception) {
                        // Don't let recording errors affect write operation
                    }
                }
            } else {
                sendErrors++
            }
            result
        } catch (e: Exception) {
            sendErrors++
            log("Write error: ${e.message}")
            -1
        }
    }

    /**
     * Read data from the USB device.
     *
     * @param buffer Buffer to receive data
     * @param timeout Timeout in milliseconds
     * @return Number of bytes read, or -1 on error/timeout
     */
    fun read(
        buffer: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot read: device not open")
                return -1
            }

        val endpoint =
            inEndpoint ?: run {
                log("Cannot read: no IN endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, buffer, buffer.size, timeout)
            if (result >= 0) {
                bytesReceived += result
                receiveCount++
            } else if (result != -1) {
                // -1 is timeout, not an error
                receiveErrors++
            }
            result
        } catch (e: Exception) {
            receiveErrors++
            log("Read error: ${e.message}")
            -1
        }
    }

    /**
     * Callback interface for direct video data processing.
     * This enables zero-copy video data handling similar to Flutter's processDataDirect pattern.
     */
    interface VideoDataProcessor {
        /**
         * Process video data directly from USB.
         *
         * @param payloadLength Total payload length (including 20-byte video header)
         * @param readCallback Callback to read data into the provided buffer
         */
        fun processVideoDirect(
            payloadLength: Int,
            readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int,
        )
    }

    /**
     * Callback interface for reading loop events.
     */
    interface ReadingLoopCallback {
        fun onMessage(
            type: Int,
            data: ByteArray?,
        )

        fun onError(error: String)
    }

    /**
     * Callback interface for recording raw USB packets.
     * Called for EVERY packet (including video) with full raw data.
     *
     * Data is captured at the USB boundary BEFORE any processing (ring buffer, parsing, etc.)
     * This matches pi-carplay's capture approach for reliable, uncorrupted recordings.
     */
    interface RecordingCallback {
        /**
         * Record a complete packet (header + payload as single buffer).
         * Called immediately after USB read, before any processing.
         *
         * @param direction "IN" for adapter->app, "OUT" for app->adapter
         * @param type Message type ID
         * @param data Complete packet data (16-byte header + payload)
         */
        fun onPacket(
            direction: String,
            type: Int,
            data: ByteArray,
        )
    }

    /**
     * Start the continuous reading loop.
     *
     * @param callback Callback for received messages
     * @param timeout Read timeout in milliseconds
     * @param videoProcessor Optional processor for direct video data handling (bypasses message parsing)
     * @param recordingCallback Optional callback for recording raw packets (deprecated, use setRecordingCallback)
     */
    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
        @Suppress("UNUSED_PARAMETER") recordingCallback: RecordingCallback? = null,
    ) {
        if (_isReadingLoopActive.getAndSet(true)) {
            log("Reading loop already active")
            return
        }

        Thread {
            // Use class member recording callback
            val activeRecordingCallback = this.recordingCallback
            log("Reading loop started" + if (activeRecordingCallback != null) " (recording enabled)" else "")
            val headerBuffer = ByteArray(16)

            // Pre-allocate raw capture buffer for video packets (reused to avoid allocation per frame)
            // This buffer captures raw USB data BEFORE any processing - matching pi-carplay approach
            var rawCaptureBuffer: ByteArray? = if (activeRecordingCallback != null) ByteArray(512 * 1024) else null

            try {
                while (_isReadingLoopActive.get() && _isOpened.get()) {
                    // Read header
                    val headerResult = read(headerBuffer, timeout)
                    if (headerResult != 16) {
                        if (_isReadingLoopActive.get()) {
                            if (headerResult == -1) {
                                // Timeout - continue loop
                                continue
                            }
                            log("Incomplete header read: $headerResult bytes")
                        }
                        continue
                    }

                    // Parse header
                    val header =
                        try {
                            com.carlink.protocol.MessageParser
                                .parseHeader(headerBuffer)
                        } catch (e: com.carlink.protocol.HeaderParseException) {
                            log("Header parse error: ${e.message}")
                            continue
                        }

                    // Handle VIDEO_DATA with zero-copy processing
                    // Key change: When recording, we read into raw buffer FIRST, record it,
                    // THEN copy to ring buffer. This eliminates race conditions.
                    if (header.type == com.carlink.protocol.MessageType.VIDEO_DATA &&
                        header.length > 0 && videoProcessor != null
                    ) {
                        val conn = connection
                        val endpoint = inEndpoint
                        if (conn != null && endpoint != null) {
                            try {
                                if (activeRecordingCallback != null) {
                                    // RECORDING MODE: Read into raw buffer first, then process
                                    // This matches pi-carplay's capture-before-process approach

                                    // Ensure raw capture buffer is large enough
                                    if (rawCaptureBuffer == null || rawCaptureBuffer!!.size < header.length) {
                                        rawCaptureBuffer = ByteArray(maxOf(header.length, 512 * 1024))
                                    }

                                    // Step 1: Read raw USB data into capture buffer
                                    var totalRead = 0
                                    while (totalRead < header.length && _isReadingLoopActive.get()) {
                                        val remaining = header.length - totalRead
                                        val chunkSize = minOf(remaining, 16384)
                                        val chunkRead = conn.bulkTransfer(
                                            endpoint,
                                            rawCaptureBuffer,
                                            totalRead,
                                            chunkSize,
                                            timeout,
                                        )
                                        if (chunkRead > 0) {
                                            totalRead += chunkRead
                                            bytesReceived += chunkRead
                                            receiveCount++
                                        } else if (chunkRead <= 0) {
                                            break
                                        }
                                    }

                                    // Step 2: Record the raw packet IMMEDIATELY (before any processing)
                                    if (totalRead > 0) {
                                        try {
                                            // Combine header + payload into single buffer (like pi-carplay)
                                            val completePacket = ByteArray(16 + totalRead)
                                            System.arraycopy(headerBuffer, 0, completePacket, 0, 16)
                                            System.arraycopy(rawCaptureBuffer!!, 0, completePacket, 16, totalRead)
                                            activeRecordingCallback.onPacket("IN", header.type.id, completePacket)
                                        } catch (e: Exception) {
                                            log("Recording callback error (video): ${e.message}")
                                        }
                                    }

                                    // Step 3: Now process for video rendering (copy to ring buffer)
                                    if (totalRead == header.length) {
                                        videoProcessor.processVideoDirect(header.length) { buffer, offset, _ ->
                                            // Copy from raw capture buffer to ring buffer
                                            System.arraycopy(rawCaptureBuffer!!, 0, buffer, offset, totalRead)
                                            totalRead
                                        }
                                    }
                                } else {
                                    // NO RECORDING: Use zero-copy direct read into ring buffer
                                    videoProcessor.processVideoDirect(header.length) { buffer, offset, length ->
                                        var totalRead = 0
                                        while (totalRead < length && _isReadingLoopActive.get()) {
                                            val remaining = length - totalRead
                                            val chunkSize = minOf(remaining, 16384)
                                            val chunkRead = conn.bulkTransfer(
                                                endpoint,
                                                buffer,
                                                offset + totalRead,
                                                chunkSize,
                                                timeout,
                                            )
                                            if (chunkRead > 0) {
                                                totalRead += chunkRead
                                                bytesReceived += chunkRead
                                                receiveCount++
                                            } else if (chunkRead <= 0) {
                                                break
                                            }
                                        }
                                        totalRead
                                    }
                                }

                                // Notify callback that video data was received
                                callback.onMessage(header.type.id, ByteArray(0))
                            } catch (e: Exception) {
                                log("Video processing error (non-fatal): ${e.message}")
                                receiveErrors++
                            }
                        }
                        continue
                    }

                    // Read payload for non-video messages
                    val payload =
                        if (header.length > 0) {
                            val payloadBuffer = ByteArray(header.length)
                            var totalRead = 0
                            while (totalRead < header.length && _isReadingLoopActive.get()) {
                                val remaining = header.length - totalRead
                                val chunk = ByteArray(minOf(remaining, 16384))
                                val chunkRead = read(chunk, timeout)
                                if (chunkRead > 0) {
                                    System.arraycopy(chunk, 0, payloadBuffer, totalRead, chunkRead)
                                    totalRead += chunkRead
                                } else if (chunkRead == -1) {
                                    // Timeout
                                    break
                                }
                            }
                            if (totalRead == header.length) payloadBuffer else null
                        } else {
                            null
                        }

                    // Record packet IMMEDIATELY after reading (before any processing)
                    if (activeRecordingCallback != null && payload != null) {
                        try {
                            // Combine header + payload into single buffer (like pi-carplay)
                            val completePacket = ByteArray(16 + payload.size)
                            System.arraycopy(headerBuffer, 0, completePacket, 0, 16)
                            System.arraycopy(payload, 0, completePacket, 16, payload.size)
                            activeRecordingCallback.onPacket("IN", header.type.id, completePacket)
                        } catch (e: Exception) {
                            log("Recording callback error: ${e.message}")
                        }
                    }

                    // Deliver message to callback
                    try {
                        callback.onMessage(header.type.id, payload)
                    } catch (e: Exception) {
                        log("Message callback error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (_isReadingLoopActive.get()) {
                    log("Reading loop error: ${e.message}")
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                _isReadingLoopActive.set(false)
                log("Reading loop stopped")
            }
        }.apply {
            name = "USB-ReadLoop"
            isDaemon = true
            start()
        }
    }

    /**
     * Stop the reading loop.
     */
    fun stopReadingLoop() {
        _isReadingLoopActive.set(false)
    }

    /**
     * Get performance statistics.
     */
    fun getPerformanceStats(): Map<String, Any> =
        mapOf(
            "bytesSent" to bytesSent,
            "bytesReceived" to bytesReceived,
            "sendCount" to sendCount,
            "receiveCount" to receiveCount,
            "sendErrors" to sendErrors,
            "receiveErrors" to receiveErrors,
        )

    // ==================== Private Methods ====================

    private fun claimBulkInterface(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)

            // Look for bulk endpoints
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        inEp = endpoint
                    } else {
                        outEp = endpoint
                    }
                }
            }

            // If we found both endpoints, claim this interface
            if (inEp != null && outEp != null) {
                val claimed = connection?.claimInterface(iface, true) ?: false
                if (claimed) {
                    claimedInterface = iface
                    inEndpoint = inEp
                    outEndpoint = outEp
                    log("Claimed interface $i: IN=${inEp.address.toString(16)} OUT=${outEp.address.toString(16)}")
                    return true
                }
            }
        }

        log("No suitable bulk interface found")
        return false
    }

    private fun log(message: String) {
        logCallback("[USB] $message")
    }

    companion object {
        /**
         * Find all connected Carlinkit devices.
         */
        fun findDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                KnownDevices.isKnownDevice(device.vendorId, device.productId)
            }

        /**
         * Create a wrapper for the first available Carlinkit device.
         */
        fun findFirst(
            context: Context,
            usbManager: UsbManager,
            logCallback: (String) -> Unit,
        ): UsbDeviceWrapper? {
            val device = findDevices(usbManager).firstOrNull() ?: return null
            return UsbDeviceWrapper(context, usbManager, device, logCallback)
        }
    }
}
