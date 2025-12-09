package com.carlink.ui.settings

import com.carlink.CarlinkManager
import com.carlink.logging.Logger
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.protocol.AdapterConfigurationMessage
import com.carlink.protocol.AudioDataMessage
import com.carlink.protocol.BluetoothDeviceNameMessage
import com.carlink.protocol.BluetoothPinMessage
import com.carlink.protocol.BoxInfoMessage
import com.carlink.protocol.ManufacturerInfoMessage
import com.carlink.protocol.Message
import com.carlink.protocol.NetworkMacAddressAltMessage
import com.carlink.protocol.NetworkMacAddressMessage
import com.carlink.protocol.PhaseMessage
import com.carlink.protocol.PhoneType
import com.carlink.protocol.PluggedMessage
import com.carlink.protocol.SoftwareVersionMessage
import com.carlink.protocol.UnpluggedMessage
import com.carlink.protocol.VideoDataMessage
import com.carlink.protocol.WifiDeviceNameMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList

/**
 * Status monitor for CPC200-CCPA adapter messages.
 * Listens for specific status messages and maintains current adapter state.
 *
 * This class follows the observer pattern and provides real-time updates
 * about the adapter's operational status, phone connections, firmware info, etc.
 *
 * Matches Flutter: status_monitor.dart AdapterStatusMonitor
 */
class AdapterStatusMonitor private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: AdapterStatusMonitor? = null

        fun getInstance(): AdapterStatusMonitor =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdapterStatusMonitor().also { INSTANCE = it }
            }

        /** Polling interval for status updates (in milliseconds) */
        private const val POLLING_INTERVAL_MS = 500L

        /** Duration to consider audio data as "recent" (in seconds) */
        private const val AUDIO_DATA_TIMEOUT_SECONDS = 5

        /** Number of recent frames to track for FPS calculation */
        private const val FRAME_TIME_WINDOW = 30
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Current adapter status information */
    private val _currentStatus =
        MutableStateFlow(
            AdapterStatusInfo(
                phoneConnection = PhoneConnectionInfo(lastUpdate = System.currentTimeMillis()),
                lastUpdated = System.currentTimeMillis(),
            ),
        )
    val currentStatus: StateFlow<AdapterStatusInfo> = _currentStatus.asStateFlow()

    /** Carlink manager instance for communication */
    private var carlinkManager: CarlinkManager? = null

    /** Whether the monitor is currently active */
    private var isMonitoring = false

    /** Polling job */
    private var pollingJob: Job? = null

    /** Timestamp of last audio data received */
    private var lastAudioDataTime: Long? = null

    /** Video frame tracking for FPS calculation */
    private val videoFrameTimes = LinkedList<Long>()
    private var totalVideoFrames = 0

    /** Cached codec name from native layer */
    private var detectedCodecName: String? = null

    /** Configured video settings */
    private var configuredWidth: Int? = null
    private var configuredHeight: Int? = null
    private var configuredFps: Int? = null

    /**
     * Starts monitoring the specified CarlinkManager instance.
     */
    fun startMonitoring(manager: CarlinkManager?) {
        if (isMonitoring) {
            stopMonitoring()
        }

        carlinkManager = manager
        if (carlinkManager == null) {
            _currentStatus.value =
                _currentStatus.value.copy(
                    phase = AdapterPhase.UNKNOWN,
                    phoneConnection =
                        PhoneConnectionInfo(
                            status = PhoneConnectionStatus.UNKNOWN,
                            lastUpdate = System.currentTimeMillis(),
                        ),
                )
            return
        }

        isMonitoring = true
        startPolling()

        logInfo("[STATUS_MONITOR] Started monitoring adapter status", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Stops monitoring and cleans up resources.
     */
    fun stopMonitoring() {
        pollingJob?.cancel()
        pollingJob = null
        isMonitoring = false

        logInfo("[STATUS_MONITOR] Stopped monitoring adapter status", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Starts the polling timer to check for status changes.
     */
    private fun startPolling() {
        pollingJob =
            scope.launch {
                while (isActive && isMonitoring) {
                    pollStatus()
                    delay(POLLING_INTERVAL_MS)
                }
            }
    }

    /**
     * Polls the current status from the CarlinkManager instance.
     */
    private fun pollStatus() {
        val manager = carlinkManager ?: return
        if (!isMonitoring) return

        try {
            // Map Carlink state to our adapter phase
            val carlinkState = manager.state
            val adapterPhase = mapCarlinkStateToPhase(carlinkState)

            if (adapterPhase != _currentStatus.value.phase) {
                _currentStatus.value =
                    _currentStatus.value.copy(
                        phase = adapterPhase,
                        lastUpdated = System.currentTimeMillis(),
                    )
                logInfo("[STATUS_MONITOR] Phase changed to: ${adapterPhase.displayName}", tag = Logger.Tags.ADAPTR)
            }

            // Check for recent audio data activity
            val hasRecentAudio = hasRecentAudioData()
            if (hasRecentAudio != _currentStatus.value.hasRecentAudioData) {
                _currentStatus.value =
                    _currentStatus.value.copy(
                        hasRecentAudioData = hasRecentAudio,
                        lastUpdated = System.currentTimeMillis(),
                    )
            }
        } catch (e: Exception) {
            logError("[STATUS_MONITOR] Error polling status: $e", tag = Logger.Tags.ADAPTR)
        }
    }

    /**
     * Maps CarlinkState to AdapterPhase.
     */
    private fun mapCarlinkStateToPhase(carlinkState: CarlinkManager.State): AdapterPhase =
        when (carlinkState) {
            CarlinkManager.State.DISCONNECTED -> AdapterPhase.IDLE
            CarlinkManager.State.CONNECTING -> AdapterPhase.INITIALIZING
            CarlinkManager.State.DEVICE_CONNECTED -> AdapterPhase.ACTIVE
            CarlinkManager.State.STREAMING -> AdapterPhase.ACTIVE
        }

    /**
     * Checks if audio data was received recently.
     */
    private fun hasRecentAudioData(): Boolean {
        val lastTime = lastAudioDataTime ?: return false
        val now = System.currentTimeMillis()
        val timeDifference = (now - lastTime) / 1000
        return timeDifference <= AUDIO_DATA_TIMEOUT_SECONDS
    }

    /**
     * Calculates current FPS from recent frame times.
     */
    private fun calculateCurrentFPS(): Double? {
        if (videoFrameTimes.size < 2) return null

        val now = System.currentTimeMillis()
        // Remove frames older than 2 seconds for more accurate real-time FPS
        videoFrameTimes.removeAll { (now - it) > 2000 }

        if (videoFrameTimes.size < 2) return null

        // Calculate FPS from the time span of recent frames
        val timeSpan = videoFrameTimes.last - videoFrameTimes.first
        if (timeSpan < 100) {
            return null // Avoid division by very small numbers
        }

        return (videoFrameTimes.size - 1) * 1000.0 / timeSpan
    }

    /**
     * Updates video stream information based on VideoData message.
     */
    private fun updateVideoStreamInfo(
        width: Int,
        height: Int,
    ) {
        val now = System.currentTimeMillis()
        totalVideoFrames++

        // Add current timestamp for FPS calculation
        videoFrameTimes.add(now)

        // Keep only recent frames for FPS calculation
        if (videoFrameTimes.size > FRAME_TIME_WINDOW) {
            videoFrameTimes.removeFirst()
        }

        // Calculate current FPS for actual streaming rate
        val actualFps = calculateCurrentFPS()

        // Preserve configured resolution but track actual received resolution separately
        val currentVideo = _currentStatus.value.videoStream
        val updatedVideo =
            if (currentVideo != null) {
                currentVideo.copy(
                    // Keep configured resolution unless not yet initialized
                    width = currentVideo.width ?: configuredWidth ?: width,
                    height = currentVideo.height ?: configuredHeight ?: height,
                    // Store the actual received resolution from VideoData messages
                    receivedWidth = width,
                    receivedHeight = height,
                    frameRate = currentVideo.frameRate ?: actualFps,
                    // Use detected codec name from native layer, or preserve existing
                    codec = detectedCodecName ?: currentVideo.codec,
                    lastVideoUpdate = now,
                    totalFrames = totalVideoFrames,
                )
            } else {
                VideoStreamInfo(
                    width = configuredWidth ?: width,
                    height = configuredHeight ?: height,
                    receivedWidth = width,
                    receivedHeight = height,
                    frameRate = actualFps,
                    codec = detectedCodecName ?: "H.264",
                    lastVideoUpdate = now,
                    totalFrames = totalVideoFrames,
                )
            }

        _currentStatus.value =
            _currentStatus.value.copy(
                videoStream = updatedVideo,
                lastUpdated = now,
            )
    }

    /**
     * Processes incoming CPC200-CCPA messages to update status.
     * This method should be called from the CarlinkManager callback.
     */
    fun processMessage(message: Message) {
        try {
            when (message) {
                is PhaseMessage -> {
                    // Process Phase message (0x03)
                    val phase = AdapterPhase.fromValue(message.phase)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            phase = phase,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Phase: ${phase.displayName}", tag = Logger.Tags.ADAPTR)
                }

                is PluggedMessage -> {
                    // Process Plugged message (0x02) with detailed phone information
                    val platform = PhonePlatform.fromPhoneType(message.phoneType)
                    val connectionType =
                        if (message.wifi != null) {
                            PhoneConnectionType.WIRELESS
                        } else {
                            PhoneConnectionType.WIRED
                        }

                    val phoneConnection =
                        PhoneConnectionInfo(
                            status = PhoneConnectionStatus.CONNECTED,
                            platform = platform,
                            connectionType = connectionType,
                            lastUpdate = System.currentTimeMillis(),
                        )

                    _currentStatus.value =
                        _currentStatus.value.copy(
                            phoneConnection = phoneConnection,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo(
                        "[STATUS_MONITOR] Phone Connected: ${platform.displayName} (${connectionType.displayName})",
                        tag = Logger.Tags.ADAPTR,
                    )
                }

                is UnpluggedMessage -> {
                    // Process Unplugged message (0x04)
                    // Keep platform info but mark as disconnected
                    val phoneConnection =
                        _currentStatus.value.phoneConnection.copy(
                            status = PhoneConnectionStatus.DISCONNECTED,
                            lastUpdate = System.currentTimeMillis(),
                        )

                    _currentStatus.value =
                        _currentStatus.value.copy(
                            phoneConnection = phoneConnection,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Phone Disconnected: ${phoneConnection.platform.displayName}", tag = Logger.Tags.ADAPTR)
                }

                is SoftwareVersionMessage -> {
                    // Process Software Version message (0xCC)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            firmwareVersion = message.version,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Firmware Version: ${message.version}", tag = Logger.Tags.ADAPTR)
                }

                is ManufacturerInfoMessage -> {
                    // Process Manufacturer Info message (0x14)
                    val info = mapOf("a" to message.a, "b" to message.b)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            manufacturerInfo = info,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Manufacturer Info", tag = Logger.Tags.ADAPTR)
                }

                is BoxInfoMessage -> {
                    // Process Box Settings message (0x19)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            boxSettings = message.settings,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Box Settings", tag = Logger.Tags.ADAPTR)
                }

                is AudioDataMessage -> {
                    // Process AudioData message (0x07)
                    if (message.data != null) {
                        lastAudioDataTime = System.currentTimeMillis()
                        _currentStatus.value =
                            _currentStatus.value.copy(
                                hasRecentAudioData = true,
                                lastUpdated = System.currentTimeMillis(),
                            )
                    }
                }

                is VideoDataMessage -> {
                    // Process VideoData message (0x06)
                    if (message.width > 0 && message.height > 0) {
                        updateVideoStreamInfo(message.width, message.height)
                    }
                }

                is BluetoothDeviceNameMessage -> {
                    // Process Bluetooth Device Name message (0x0D)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            bluetoothDeviceName = message.name,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Bluetooth Device Name: ${message.name}", tag = Logger.Tags.ADAPTR)
                }

                is BluetoothPinMessage -> {
                    // Process Bluetooth PIN message (0x0C)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            bluetoothPIN = message.pin,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Bluetooth PIN: ${message.pin}", tag = Logger.Tags.ADAPTR)
                }

                is WifiDeviceNameMessage -> {
                    // Process WiFi Device Name message (0x0E)
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            wifiDeviceName = message.name,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received WiFi Device Name: ${message.name}", tag = Logger.Tags.ADAPTR)
                }

                is NetworkMacAddressMessage -> {
                    // Process Network MAC Address message (0x23)
                    val phoneConnection =
                        _currentStatus.value.phoneConnection.copy(
                            connectedPhoneMacAddress = message.macAddress,
                            lastUpdate = System.currentTimeMillis(),
                        )
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            phoneConnection = phoneConnection,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Phone MAC Address: ${message.macAddress}", tag = Logger.Tags.ADAPTR)
                }

                is NetworkMacAddressAltMessage -> {
                    // Process Network MAC Address Alt message (0x24)
                    val phoneConnection =
                        _currentStatus.value.phoneConnection.copy(
                            connectedPhoneMacAddress = message.macAddress,
                            lastUpdate = System.currentTimeMillis(),
                        )
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            phoneConnection = phoneConnection,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo("[STATUS_MONITOR] Received Phone MAC Address (Alt): ${message.macAddress}", tag = Logger.Tags.ADAPTR)
                }

                is AdapterConfigurationMessage -> {
                    // Process Dongle Configuration message (internal)
                    // Initialize video stream info with the actual configuration sent to adapter
                    configuredWidth = message.config.width
                    configuredHeight = message.config.height
                    configuredFps = message.config.fps

                    val initialVideoStream =
                        VideoStreamInfo(
                            width = message.config.width,
                            height = message.config.height,
                            receivedWidth = null, // Will be populated when VideoData is received
                            receivedHeight = null, // Will be populated when VideoData is received
                            frameRate = message.config.fps.toDouble(),
                            codec = detectedCodecName ?: "H.264",
                            lastVideoUpdate = System.currentTimeMillis(),
                            totalFrames = 0,
                        )
                    _currentStatus.value =
                        _currentStatus.value.copy(
                            videoStream = initialVideoStream,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    logInfo(
                        "[STATUS_MONITOR] Initialized video config: ${message.config.width}x${message.config.height}@${message.config.fps}fps",
                        tag = Logger.Tags.ADAPTR,
                    )
                }

                else -> {
                    // Unknown message type - ignore
                }
            }
        } catch (e: Exception) {
            logError("[STATUS_MONITOR] Error processing message: $e", tag = Logger.Tags.ADAPTR)
        }
    }

    /**
     * Sets the detected codec name.
     */
    fun setDetectedCodecName(codecName: String) {
        detectedCodecName = codecName
        logInfo("[STATUS_MONITOR] Detected codec: $codecName", tag = Logger.Tags.ADAPTR)

        // Update video stream info with detected codec if we have video info
        val currentVideo = _currentStatus.value.videoStream
        if (currentVideo != null) {
            _currentStatus.value =
                _currentStatus.value.copy(
                    videoStream = currentVideo.copy(codec = codecName),
                    lastUpdated = System.currentTimeMillis(),
                )
        }
    }

    /**
     * Forces a manual status refresh.
     */
    fun refreshStatus() {
        if (isMonitoring && carlinkManager != null) {
            pollStatus()
        }
    }

    /**
     * Clean up resources.
     */
    fun dispose() {
        stopMonitoring()
        scope.cancel()
    }
}
