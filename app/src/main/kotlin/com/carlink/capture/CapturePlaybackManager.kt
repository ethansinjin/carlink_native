package com.carlink.capture

import android.content.Context
import android.net.Uri
import com.carlink.CarlinkManager
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Capture Playback Manager
 *
 * Orchestrates playback of captured USB sessions by:
 * - Loading capture files via CaptureReplaySource
 * - Injecting replayed packets into CarlinkManager's existing renderers
 * - Managing playback state and progress
 *
 * This uses CarlinkManager's existing H264Renderer and DualStreamAudioManager
 * instead of creating separate instances, avoiding Surface contention issues.
 */
class CapturePlaybackManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "PLAYBACK"

        // Message types from protocol (see MessageTypes.kt)
        private const val TYPE_VIDEO_DATA = 0x06
        private const val TYPE_AUDIO_DATA = 0x07
        private const val TYPE_PLUGGED = 0x02
        private const val TYPE_COMMAND = 0x08
        private const val TYPE_MEDIA_DATA = 0x2A
        private const val TYPE_NAVI_VIDEO_DATA = 0x2C // Navigation video (iOS 13+)

        // Minimum audio payload size (PCM frame = 4 bytes for stereo 16-bit)
        // Skip tiny packets that would corrupt the PCM stream alignment
        private const val MIN_AUDIO_PAYLOAD_SIZE = 64
    }

    /**
     * Playback state.
     */
    enum class State {
        IDLE,
        LOADING,
        READY,
        PLAYING,
        COMPLETED,
        ERROR,
    }

    /**
     * Playback progress info.
     */
    data class Progress(
        val currentMs: Long = 0,
        val totalMs: Long = 0,
    )

    /**
     * Callback interface for playback events.
     */
    interface Callback {
        fun onStateChanged(state: State)
        fun onProgressChanged(progress: Progress)
        fun onError(message: String)
        fun onPlaybackComplete()
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    // State
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var callback: Callback? = null

    // Replay source
    private var replaySource: CaptureReplaySource? = null

    // Reference to CarlinkManager for data injection
    private var carlinkManager: CarlinkManager? = null

    /**
     * Set the callback for playback events.
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Set the CarlinkManager to use for data injection.
     * Must be called before startPlayback().
     */
    fun setCarlinkManager(manager: CarlinkManager) {
        this.carlinkManager = manager
    }

    /**
     * Load capture files for playback.
     *
     * @param jsonUri URI of the .json metadata file
     * @param binUri URI of the .bin data file
     * @return True if files loaded successfully
     */
    suspend fun loadCapture(
        jsonUri: Uri,
        binUri: Uri,
    ): Boolean {
        setState(State.LOADING)

        replaySource?.release()
        replaySource = CaptureReplaySource(context)

        val loaded = replaySource?.load(jsonUri, binUri) ?: false

        if (loaded) {
            _progress.value = Progress(totalMs = replaySource?.getDurationMs() ?: 0)
            setState(State.READY)
            logInfo("[$TAG] Capture loaded, duration: ${replaySource?.getDurationMs()}ms", tag = TAG)
        } else {
            setState(State.ERROR)
            callback?.onError("Failed to load capture files")
        }

        return loaded
    }

    /**
     * Start playback using CarlinkManager's existing renderers.
     */
    fun startPlayback() {
        if (_state.value != State.READY && _state.value != State.COMPLETED) {
            logInfo("[$TAG] Cannot start playback - state is ${_state.value}", tag = TAG)
            return
        }

        val manager = carlinkManager
        if (manager == null) {
            logError("[$TAG] Cannot start playback - CarlinkManager not set", tag = TAG)
            callback?.onError("CarlinkManager not configured")
            return
        }

        // Check if renderer is ready
        if (!manager.isRendererReady()) {
            logError("[$TAG] Cannot start playback - renderer not ready", tag = TAG)
            callback?.onError("Video renderer not ready")
            return
        }

        // Prepare CarlinkManager for playback (stops USB but keeps renderers)
        manager.prepareForPlayback()

        setState(State.PLAYING)

        replaySource?.start(
            object : CaptureReplaySource.ReplayCallback {
                override fun onSessionStart(session: CaptureReplaySource.SessionInfo) {
                    logInfo("[$TAG] Playback started: ${session.id}", tag = TAG)
                }

                override fun onPacket(
                    type: Int,
                    typeName: String,
                    data: ByteArray,
                ) {
                    processPacket(type, typeName, data)
                }

                override fun onProgress(
                    currentMs: Long,
                    totalMs: Long,
                ) {
                    _progress.value = Progress(currentMs, totalMs)
                    callback?.onProgressChanged(_progress.value)
                }

                override fun onComplete() {
                    logInfo("[$TAG] Playback complete", tag = TAG)
                    setState(State.COMPLETED)
                    callback?.onPlaybackComplete()
                }

                override fun onError(message: String) {
                    logError("[$TAG] Playback error: $message", tag = TAG)
                    setState(State.ERROR)
                    callback?.onError(message)
                }
            },
        )
    }

    /**
     * Stop playback.
     *
     * Sets state to IDLE (not READY) to prevent auto-restart from
     * MainScreen's LaunchedEffect that watches for READY state.
     */
    fun stopPlayback() {
        logInfo("[$TAG] Stopping playback", tag = TAG)
        replaySource?.stop()
        setState(State.IDLE)
    }

    /**
     * Check if currently playing.
     */
    fun isPlaying(): Boolean = _state.value == State.PLAYING

    /**
     * Release all resources.
     */
    fun release() {
        logInfo("[$TAG] Releasing playback manager", tag = TAG)
        replaySource?.release()
        replaySource = null
        callback = null
        setState(State.IDLE)
    }

    private fun setState(newState: State) {
        val oldState = _state.value
        if (oldState != newState) {
            _state.value = newState
            callback?.onStateChanged(newState)
            logInfo("[$TAG] State: $oldState -> $newState", tag = TAG)
        }
    }

    // Track total packets for debugging
    private var totalPacketsReceived = 0

    /**
     * Process a replayed packet.
     *
     * The packet data includes the full message (header + payload).
     * We need to extract the payload and inject it into CarlinkManager.
     */
    private fun processPacket(
        type: Int,
        typeName: String,
        data: ByteArray,
    ) {
        totalPacketsReceived++
        if (totalPacketsReceived <= 50 || totalPacketsReceived % 500 == 0) {
            logDebug("[$TAG] processPacket: type=$type ($typeName), size=${data.size}, total=$totalPacketsReceived", tag = TAG)
        }

        when (type) {
            TYPE_VIDEO_DATA -> {
                logDebug("[$TAG] Processing video type=$type (expected=$TYPE_VIDEO_DATA)", tag = TAG)
                processVideoPacket(data)
            }
            TYPE_NAVI_VIDEO_DATA -> {
                // Navigation video (iOS 13+) uses same structure as main video
                logDebug("[$TAG] Processing nav video type=$type", tag = TAG)
                processVideoPacket(data)
            }
            TYPE_AUDIO_DATA -> processAudioPacket(data)
            TYPE_PLUGGED -> {
                logInfo("[$TAG] Plugged message received (simulating connection)", tag = TAG)
            }
            TYPE_COMMAND -> {
                logDebug("[$TAG] Command message: $typeName", tag = TAG)
            }
            TYPE_MEDIA_DATA -> {
                logDebug("[$TAG] Media metadata message", tag = TAG)
            }
            else -> {
                logDebug("[$TAG] Unhandled message type: $type ($typeName)", tag = TAG)
            }
        }
    }

    // Track video packet count for logging
    private var videoPacketCount = 0

    // Protocol magic bytes (0x55AA55AA stored as little-endian bytes: 0xAA, 0x55, 0xAA, 0x55)
    private val PROTOCOL_MAGIC = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xAA.toByte(), 0x55.toByte())

    /**
     * Detect capture format based on magic byte position.
     * Returns the offset where protocol header starts.
     *
     * Both carlink_native and pi-carplay now use the same format: magic at offset 0.
     * The offset-12 check is kept for backwards compatibility with older captures.
     */
    private fun detectCapturePrefix(data: ByteArray): Int {
        if (data.size < 16) return 0

        // Check if magic is at offset 0 (standard format - both carlink_native and pi-carplay)
        if (data[0] == PROTOCOL_MAGIC[0] && data[1] == PROTOCOL_MAGIC[1] &&
            data[2] == PROTOCOL_MAGIC[2] && data[3] == PROTOCOL_MAGIC[3]
        ) {
            return 0
        }

        // Check if magic is at offset 12 (legacy format - kept for backwards compatibility)
        if (data.size >= 28 &&
            data[12] == PROTOCOL_MAGIC[0] && data[13] == PROTOCOL_MAGIC[1] &&
            data[14] == PROTOCOL_MAGIC[2] && data[15] == PROTOCOL_MAGIC[3]
        ) {
            return 12
        }

        // Default: assume standard format (no prefix)
        return 0
    }

    /**
     * Process video packet.
     *
     * Supports two capture formats:
     * 1. pi-carplay: 12-byte capture prefix + 16-byte protocol header + 20-byte video header + H.264
     * 2. carlink_native: 16-byte protocol header + 20-byte video header + H.264
     *
     * Format is auto-detected based on magic byte position.
     */
    private fun processVideoPacket(data: ByteArray) {
        val manager = carlinkManager ?: return

        // Detect format and calculate header size
        val capturePrefix = detectCapturePrefix(data)
        val protocolHeaderSize = 16
        val videoHeaderSize = 20
        val headerSize = capturePrefix + protocolHeaderSize + videoHeaderSize

        if (data.size <= headerSize) {
            logDebug("[$TAG] Video packet too small: ${data.size} (expected > $headerSize)", tag = TAG)
            return
        }

        // Extract H.264 data (skip headers)
        val h264Data = data.copyOfRange(headerSize, data.size)

        videoPacketCount++
        // Log first few packets with detailed hex dump
        if (videoPacketCount <= 5 || videoPacketCount % 100 == 0) {
            val hexDump = h264Data.take(16).joinToString(" ") { String.format("%02X", it) }
            val nalType = findNalType(h264Data)
            logInfo("[$TAG] Video packet #$videoPacketCount: ${h264Data.size} bytes, first 16: $hexDump, NAL type=$nalType", tag = TAG)
        }

        // Inject into CarlinkManager's renderer
        manager.injectVideoData(h264Data, 0)
    }

    // Track audio packet count for logging
    private var audioPacketCount = 0

    /**
     * Process audio packet.
     *
     * Supports two capture formats:
     * 1. pi-carplay: 12-byte capture prefix + 16-byte protocol header + 12-byte audio header + PCM
     * 2. carlink_native: 16-byte protocol header + 12-byte audio header + PCM
     *
     * Format is auto-detected based on magic byte position.
     */
    private fun processAudioPacket(data: ByteArray) {
        val manager = carlinkManager ?: return

        // Detect format
        val capturePrefixSize = detectCapturePrefix(data)
        val protocolHeaderSize = 16
        val audioHeaderSize = 12
        val audioHeaderOffset = capturePrefixSize + protocolHeaderSize
        val totalHeaderSize = capturePrefixSize + protocolHeaderSize + audioHeaderSize

        if (data.size <= totalHeaderSize) {
            logDebug("[$TAG] Audio packet too small: ${data.size} (expected > $totalHeaderSize)", tag = TAG)
            return
        }

        // Parse audio header to get type info (starts after capture prefix + protocol header)
        // Audio header format: decode_type (4), volume (4), audio_type (4)
        val decodeType =
            (data[audioHeaderOffset].toInt() and 0xFF) or
                ((data[audioHeaderOffset + 1].toInt() and 0xFF) shl 8) or
                ((data[audioHeaderOffset + 2].toInt() and 0xFF) shl 16) or
                ((data[audioHeaderOffset + 3].toInt() and 0xFF) shl 24)

        val audioType =
            (data[audioHeaderOffset + 8].toInt() and 0xFF) or
                ((data[audioHeaderOffset + 9].toInt() and 0xFF) shl 8) or
                ((data[audioHeaderOffset + 10].toInt() and 0xFF) shl 16) or
                ((data[audioHeaderOffset + 11].toInt() and 0xFF) shl 24)

        // Extract audio data
        val audioData = data.copyOfRange(totalHeaderSize, data.size)

        // Skip tiny packets that would corrupt PCM stream alignment
        if (audioData.size < MIN_AUDIO_PAYLOAD_SIZE) {
            logDebug("[$TAG] Skipping tiny audio packet: ${audioData.size} bytes (min: $MIN_AUDIO_PAYLOAD_SIZE)", tag = TAG)
            return
        }

        audioPacketCount++
        // Log first few packets and then periodically
        if (audioPacketCount <= 5 || audioPacketCount % 500 == 0) {
            logInfo(
                "[$TAG] Audio packet #$audioPacketCount: ${audioData.size} bytes, " +
                    "type=$audioType, decode=$decodeType",
                tag = TAG,
            )
        }

        // Inject into CarlinkManager's audio manager
        manager.injectAudioData(audioData, audioType, decodeType)
    }

    /**
     * Find NAL unit type from H.264 Annex B data.
     * Searches for start code (00 00 01 or 00 00 00 01) and returns NAL type from header byte.
     *
     * @return NAL type (1-31) or -1 if not found
     */
    private fun findNalType(data: ByteArray): Int {
        if (data.size < 4) return -1

        // Search first 16 bytes for start code
        val searchLimit = minOf(data.size - 1, 16)
        for (i in 0 until searchLimit) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
                // Check for 3-byte start code: 00 00 01
                if (i + 2 < data.size && data[i + 2] == 0x01.toByte()) {
                    if (i + 3 < data.size) {
                        return data[i + 3].toInt() and 0x1F
                    }
                }
                // Check for 4-byte start code: 00 00 00 01
                if (i + 3 < data.size && data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()) {
                    if (i + 4 < data.size) {
                        return data[i + 4].toInt() and 0x1F
                    }
                }
            }
        }
        return -1
    }
}
