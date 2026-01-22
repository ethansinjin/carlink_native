package com.carlink.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Capture Recording Manager - Records USB communication to .bin/.json files.
 *
 * Creates capture files compatible with CaptureReplaySource for playback.
 * Records ALL communication from the adapter in real-time.
 *
 * Output format (matching pi-carplay capture format):
 * - .json: Session metadata and packet index (sequence, type, timestamp, offset, length)
 * - .bin: Raw packet data (concatenated, offsets in JSON)
 *
 * Design principles:
 * - Recording starts BEFORE adapter initialization
 * - Transparent to rest of app (adapter and renderers unaware)
 * - Real-time writing (no buffering entire capture in memory)
 * - Thread-safe for concurrent packet recording
 *
 * Usage:
 * 1. Create manager and set output directory
 * 2. Call startRecording() before adapter connects
 * 3. Call recordPacket() from USB layer for each packet
 * 4. Call stopRecording() when done - writes JSON metadata
 */
class CaptureRecordingManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "CAPTURE_REC"

        // Capture file prefix format
        private const val FILE_PREFIX = "carlink_capture"

        // Buffer size for binary output (64KB)
        private const val BUFFER_SIZE = 64 * 1024
    }

    /**
     * Recording state.
     */
    enum class State {
        IDLE,
        READY,      // Output configured, waiting for start
        RECORDING,
        STOPPING,
        ERROR,
    }

    /**
     * Recording statistics.
     */
    data class Stats(
        val packetsIn: Int = 0,
        val packetsOut: Int = 0,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        val durationMs: Long = 0,
    )

    /**
     * Packet metadata for JSON output.
     */
    private data class PacketInfo(
        val seq: Int,
        val direction: String,
        val type: Int,
        val typeName: String,
        val timestampMs: Long,
        val offset: Long,
        val length: Int,
    )

    // State
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    // Recording state (thread-safe)
    private val isRecording = AtomicBoolean(false)
    private val sequenceNumber = AtomicInteger(0)
    private val currentOffset = AtomicLong(0)
    private val packetsInCount = AtomicInteger(0)
    private val packetsOutCount = AtomicInteger(0)
    private val bytesInCount = AtomicLong(0)
    private val bytesOutCount = AtomicLong(0)

    // Session info
    private var sessionId: String = ""
    private var sessionStartTime: Long = 0
    private var sessionStartIso: String = ""

    // Output streams and files
    private var outputDir: Uri? = null
    private var binOutputStream: OutputStream? = null
    private var binUri: Uri? = null
    private var jsonUri: Uri? = null

    // Packet metadata list (written to JSON at end)
    private val packets = mutableListOf<PacketInfo>()
    private val packetsLock = Object()

    // Scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Configure output directory for capture files.
     * Must be called before startRecording().
     *
     * @param directoryUri SAF URI for output directory (must have write permission)
     * @return True if directory is valid and writable
     */
    suspend fun setOutputDirectory(directoryUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val docFile = DocumentFile.fromTreeUri(context, directoryUri)
            if (docFile == null || !docFile.canWrite()) {
                logError("[$TAG] Directory not writable: $directoryUri", tag = TAG)
                return@withContext false
            }

            outputDir = directoryUri
            _state.value = State.READY
            logInfo("[$TAG] Output directory set: $directoryUri", tag = TAG)
            true
        } catch (e: Exception) {
            logError("[$TAG] Failed to set output directory: ${e.message}", tag = TAG)
            false
        }
    }

    /**
     * Start recording.
     * Creates timestamped .bin file and begins accepting packets.
     * Call this BEFORE adapter initialization to capture everything.
     *
     * @return True if recording started successfully
     */
    suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        if (isRecording.get()) {
            logInfo("[$TAG] Already recording", tag = TAG)
            return@withContext true
        }

        val dir = outputDir
        if (dir == null) {
            logError("[$TAG] Output directory not set", tag = TAG)
            _state.value = State.ERROR
            return@withContext false
        }

        try {
            // Generate session ID and filenames
            val now = Date()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sessionId = isoFormat.format(now)
            sessionStartTime = System.currentTimeMillis()
            sessionStartIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(now)

            val binFileName = "${FILE_PREFIX}-${sessionId}.bin"
            val jsonFileName = "${FILE_PREFIX}-${sessionId}.json"

            // Create files in output directory
            val docDir = DocumentFile.fromTreeUri(context, dir)
                ?: throw IllegalStateException("Cannot access output directory")

            val binDoc = docDir.createFile("application/octet-stream", binFileName)
                ?: throw IllegalStateException("Cannot create binary file")
            binUri = binDoc.uri

            val jsonDoc = docDir.createFile("application/json", jsonFileName)
                ?: throw IllegalStateException("Cannot create JSON file")
            jsonUri = jsonDoc.uri

            // Open binary output stream with buffering
            binOutputStream = context.contentResolver.openOutputStream(binDoc.uri)?.let {
                BufferedOutputStream(it, BUFFER_SIZE)
            } ?: throw IllegalStateException("Cannot open binary output stream")

            // Reset counters
            sequenceNumber.set(0)
            currentOffset.set(0)
            packetsInCount.set(0)
            packetsOutCount.set(0)
            bytesInCount.set(0)
            bytesOutCount.set(0)

            synchronized(packetsLock) {
                packets.clear()
            }

            isRecording.set(true)
            _state.value = State.RECORDING
            updateStats()

            logInfo("[$TAG] Recording started: $sessionId", tag = TAG)
            logInfo("[$TAG] Binary: $binFileName", tag = TAG)
            logInfo("[$TAG] JSON: $jsonFileName", tag = TAG)

            true
        } catch (e: Exception) {
            logError("[$TAG] Failed to start recording: ${e.message}", tag = TAG)
            _state.value = State.ERROR
            cleanup()
            false
        }
    }

    /**
     * Record a packet from USB communication.
     * Thread-safe - can be called from USB reading thread.
     *
     * @param direction "IN" for adapter->app, "OUT" for app->adapter
     * @param type Message type ID
     * @param data Raw packet data (including protocol header)
     */
    fun recordPacket(direction: String, type: Int, data: ByteArray) {
        if (!isRecording.get()) return

        val stream = binOutputStream ?: return

        try {
            val seq = sequenceNumber.getAndIncrement()
            val length = data.size
            val timestamp = System.currentTimeMillis() - sessionStartTime
            val typeName = MessageType.fromId(type).name

            // Write binary data (synchronized for thread safety)
            // CRITICAL: Capture offset INSIDE sync block to prevent race condition
            // between concurrent IN (read thread) and OUT (write thread) packets.
            // The offset must match the actual stream position when write occurs.
            val offset: Long
            synchronized(stream) {
                offset = currentOffset.getAndAdd(length.toLong())
                stream.write(data)
            }

            // Add packet metadata
            val packetInfo = PacketInfo(
                seq = seq,
                direction = direction,
                type = type,
                typeName = typeName,
                timestampMs = timestamp,
                offset = offset,
                length = length,
            )

            synchronized(packetsLock) {
                packets.add(packetInfo)
            }

            // Update counters
            if (direction == "IN") {
                packetsInCount.incrementAndGet()
                bytesInCount.addAndGet(length.toLong())
            } else {
                packetsOutCount.incrementAndGet()
                bytesOutCount.addAndGet(length.toLong())
            }

            // Log periodically
            if (seq < 10 || seq % 500 == 0) {
                logDebug("[$TAG] Packet #$seq: $direction $typeName ($length bytes)", tag = TAG)
            }

            // Update stats periodically
            if (seq % 100 == 0) {
                updateStats()
            }
        } catch (e: Exception) {
            logError("[$TAG] Failed to record packet: ${e.message}", tag = TAG)
        }
    }

    /**
     * Record raw bytes with custom header (for video data with protocol header).
     * Used when packet data includes custom capture prefix.
     *
     * @param direction "IN" or "OUT"
     * @param type Message type ID
     * @param header Protocol header bytes (16 bytes)
     * @param payload Payload bytes
     */
    fun recordPacketWithHeader(direction: String, type: Int, header: ByteArray, payload: ByteArray) {
        if (!isRecording.get()) return

        // Combine header + payload
        val combined = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, combined, 0, header.size)
        System.arraycopy(payload, 0, combined, header.size, payload.size)

        recordPacket(direction, type, combined)
    }

    /**
     * Stop recording and write JSON metadata.
     *
     * @return True if stopped successfully and files written
     */
    suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        if (!isRecording.getAndSet(false)) {
            logInfo("[$TAG] Not recording", tag = TAG)
            return@withContext true
        }

        _state.value = State.STOPPING

        try {
            // Flush and close binary stream
            binOutputStream?.let { stream ->
                synchronized(stream) {
                    stream.flush()
                    stream.close()
                }
            }
            binOutputStream = null

            // Write JSON metadata
            writeJsonMetadata()

            // Final stats
            updateStats()

            _state.value = State.READY

            val finalStats = _stats.value
            logInfo("[$TAG] Recording stopped: $sessionId", tag = TAG)
            logInfo("[$TAG] Packets: ${finalStats.packetsIn} IN, ${finalStats.packetsOut} OUT", tag = TAG)
            logInfo("[$TAG] Bytes: ${finalStats.bytesIn} IN, ${finalStats.bytesOut} OUT", tag = TAG)
            logInfo("[$TAG] Duration: ${finalStats.durationMs}ms", tag = TAG)

            true
        } catch (e: Exception) {
            logError("[$TAG] Failed to stop recording: ${e.message}", tag = TAG)
            _state.value = State.ERROR
            false
        }
    }

    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean = isRecording.get()

    /**
     * Get current session ID (for display).
     */
    fun getSessionId(): String = sessionId

    /**
     * Get URIs of current capture files.
     */
    fun getCaptureFiles(): Pair<Uri?, Uri?> = Pair(jsonUri, binUri)

    /**
     * Release resources.
     */
    fun release() {
        scope.launch {
            if (isRecording.get()) {
                stopRecording()
            }
            cleanup()
        }
    }

    private fun writeJsonMetadata() {
        val jsonUriLocal = jsonUri ?: return

        try {
            val sessionEndTime = System.currentTimeMillis()
            val durationMs = sessionEndTime - sessionStartTime

            val endIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(sessionEndTime))

            // Build JSON structure matching pi-carplay format
            val json = JSONObject().apply {
                put("version", "1.0")

                put("session", JSONObject().apply {
                    put("id", sessionId)
                    put("started", sessionStartIso)
                    put("ended", endIso)
                    put("durationMs", durationMs)
                })

                put("config", JSONObject().apply {
                    put("includeVideoData", true)
                    put("includeAudioData", true)
                    put("includeMicData", true)
                    put("includeSpeakerData", true)
                })

                // Build packets array
                val packetsArray = JSONArray()
                synchronized(packetsLock) {
                    for (pkt in packets) {
                        packetsArray.put(JSONObject().apply {
                            put("seq", pkt.seq)
                            put("dir", pkt.direction)
                            put("type", pkt.type)
                            put("typeName", pkt.typeName)
                            put("timestampMs", pkt.timestampMs)
                            put("offset", pkt.offset)
                            put("length", pkt.length)
                        })
                    }
                }
                put("packets", packetsArray)

                put("stats", JSONObject().apply {
                    put("packetsIn", packetsInCount.get())
                    put("packetsOut", packetsOutCount.get())
                    put("bytesIn", bytesInCount.get())
                    put("bytesOut", bytesOutCount.get())
                })
            }

            // Write JSON file
            context.contentResolver.openOutputStream(jsonUriLocal)?.use { stream ->
                stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }

            logInfo("[$TAG] JSON metadata written: ${packets.size} packets", tag = TAG)
        } catch (e: Exception) {
            logError("[$TAG] Failed to write JSON metadata: ${e.message}", tag = TAG)
        }
    }

    private fun updateStats() {
        val durationMs = if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0
        }

        _stats.value = Stats(
            packetsIn = packetsInCount.get(),
            packetsOut = packetsOutCount.get(),
            bytesIn = bytesInCount.get(),
            bytesOut = bytesOutCount.get(),
            durationMs = durationMs,
        )
    }

    private fun cleanup() {
        try {
            binOutputStream?.close()
        } catch (e: Exception) {
            // Ignore
        }
        binOutputStream = null
        synchronized(packetsLock) {
            packets.clear()
        }
    }
}
