package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import java.util.Locale
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlink.protocol.PhoneType

/**
 * Settings Tab Enum with title and icon.
 * Matches Flutter: settings_enums.dart SettingsTab
 */
enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    STATUS("Status", Icons.Default.Info),
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.Filled.Article),
    ;

    companion object {
        val visibleTabs: List<SettingsTab> = entries
    }
}

/**
 * Enum for CPC200-CCPA adapter operational phase states.
 * Based on message type 0x03 from the firmware documentation.
 * Matches Flutter: settings_enums.dart AdapterPhase
 */
enum class AdapterPhase(
    val value: Int,
    val displayName: String,
) {
    IDLE(0x00, "Idle/Standby"),
    INITIALIZING(0x01, "Searching"),
    ACTIVE(0x02, "Active/Connected"),
    ERROR(0x03, "Error State"),
    SHUTTING_DOWN(0x04, "Shutting Down"),
    UNKNOWN(-1, "Unknown"),
    ;

    /**
     * Get theme-aware color for UI display
     */
    fun getColor(colorScheme: ColorScheme): Color =
        when (this) {
            IDLE, UNKNOWN -> colorScheme.onSurfaceVariant
            INITIALIZING, SHUTTING_DOWN -> colorScheme.tertiary
            ACTIVE -> colorScheme.primary
            ERROR -> colorScheme.error
        }

    companion object {
        private val valueMap = entries.associateBy { it.value }

        fun fromValue(value: Int): AdapterPhase = valueMap[value] ?: UNKNOWN
    }
}

/**
 * Enum for phone connection status.
 * Based on message type 0x02 from the firmware documentation.
 * Matches Flutter: settings_enums.dart PhoneConnectionStatus
 */
enum class PhoneConnectionStatus(
    val value: Int,
    val displayName: String,
    val icon: ImageVector,
) {
    DISCONNECTED(0, "Disconnected", Icons.Default.Smartphone),
    CONNECTED(1, "Connected", Icons.Default.Smartphone),
    UNKNOWN(-1, "Unknown", Icons.Default.HelpOutline),
    ;

    /**
     * Get theme-aware color for UI display
     */
    fun getColor(colorScheme: ColorScheme): Color =
        when (this) {
            DISCONNECTED -> colorScheme.error
            CONNECTED -> colorScheme.primary
            UNKNOWN -> colorScheme.onSurfaceVariant
        }

    companion object {
        private val valueMap = entries.associateBy { it.value }

        fun fromValue(value: Int): PhoneConnectionStatus = valueMap[value] ?: UNKNOWN
    }
}

/**
 * Phone platform types supported by CPC200-CCPA adapter.
 * Matches Flutter: settings_enums.dart PhonePlatform
 */
enum class PhonePlatform(
    val id: Int,
    val displayName: String,
    val icon: ImageVector,
) {
    ANDROID_MIRROR(1, "Android Mirror", Icons.Default.Android),
    CARPLAY(3, "CarPlay", Icons.Default.PhoneIphone),
    IPHONE_MIRROR(4, "iPhone Mirror", Icons.Default.PhoneIphone),
    ANDROID_AUTO(5, "Android Auto", Icons.Default.Android),
    HI_CAR(6, "HiCar", Icons.Default.DirectionsCar),
    UNKNOWN(-1, "Unknown", Icons.Default.HelpOutline),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): PhonePlatform = idMap[id] ?: UNKNOWN

        /**
         * Convert from protocol PhoneType to PhonePlatform
         */
        fun fromPhoneType(phoneType: PhoneType): PhonePlatform =
            when (phoneType) {
                PhoneType.ANDROID_MIRROR -> ANDROID_MIRROR
                PhoneType.CARPLAY -> CARPLAY
                PhoneType.IPHONE_MIRROR -> IPHONE_MIRROR
                PhoneType.ANDROID_AUTO -> ANDROID_AUTO
                PhoneType.HI_CAR -> HI_CAR
                PhoneType.UNKNOWN -> UNKNOWN
            }
    }
}

/**
 * Phone connection type (wired vs wireless).
 * Matches Flutter: settings_enums.dart PhoneConnectionType
 */
enum class PhoneConnectionType(
    val displayName: String,
    val icon: ImageVector,
) {
    WIRED("Wired", Icons.Default.Usb),
    WIRELESS("Wireless", Icons.Default.Wifi),
    UNKNOWN("Unknown", Icons.Default.HelpOutline),
}

/**
 * Detailed phone connection information container.
 * Tracks connection status, platform type, and connection method.
 * Matches Flutter: settings_enums.dart PhoneConnectionInfo
 */
data class PhoneConnectionInfo(
    val status: PhoneConnectionStatus = PhoneConnectionStatus.UNKNOWN,
    val platform: PhonePlatform = PhonePlatform.UNKNOWN,
    val connectionType: PhoneConnectionType = PhoneConnectionType.UNKNOWN,
    val connectedPhoneMacAddress: String? = null,
    val lastUpdate: Long = System.currentTimeMillis(),
) {
    /** Whether phone is currently connected */
    val isConnected: Boolean get() = status == PhoneConnectionStatus.CONNECTED

    /** Display color based on connection status */
    fun displayColor(colorScheme: ColorScheme): Color = status.getColor(colorScheme)

    /** Display icon based on connection status */
    val displayIcon: ImageVector get() = status.icon

    /** Platform display text or "- - -" if unknown */
    val platformDisplay: String
        get() = if (platform != PhonePlatform.UNKNOWN) platform.displayName else "- - -"

    /** Connection type display text or "- - -" if unknown */
    val connectionTypeDisplay: String
        get() = if (connectionType != PhoneConnectionType.UNKNOWN) connectionType.displayName else "- - -"

    /** BT MAC address display text or "- - -" if unknown */
    val connectedPhoneMacDisplay: String
        get() = connectedPhoneMacAddress ?: "- - -"
}

/**
 * Video stream information container.
 * Tracks resolution, frame rate, and codec information from VideoData messages.
 * Matches Flutter: settings_enums.dart VideoStreamInfo
 */
data class VideoStreamInfo(
    /** Video resolution width in pixels (configured/requested) */
    val width: Int? = null,
    /** Video resolution height in pixels (configured/requested) */
    val height: Int? = null,
    /** Actual received video frame width in pixels (from VideoData messages) */
    val receivedWidth: Int? = null,
    /** Actual received video frame height in pixels (from VideoData messages) */
    val receivedHeight: Int? = null,
    /** Frames per second calculated from message frequency */
    val frameRate: Double? = null,
    /** Codec information (e.g., "Intel Quick Sync", "Generic H.264") */
    val codec: String? = null,
    /** Timestamp of last video data received */
    val lastVideoUpdate: Long = System.currentTimeMillis(),
    /** Total number of video frames processed */
    val totalFrames: Int = 0,
) {
    /** Configured/requested resolution string for display (e.g., "2400×960" or "- - -") */
    val resolutionDisplay: String
        get() = if (width != null && height != null) "$width×$height" else "- - -"

    /** Received resolution string for display (e.g., "2400×960" or "- - -") */
    val receivedResolutionDisplay: String
        get() = if (receivedWidth != null && receivedHeight != null) "$receivedWidth×$receivedHeight" else "- - -"

    /** Frame rate string for display (e.g., "58.3 fps" or "- - -") */
    val frameRateDisplay: String
        get() = frameRate?.let { String.format(Locale.US, "%.1f fps", it) } ?: "- - -"

    /** Codec string for display (e.g., "Intel Quick Sync" or "- - -") */
    val codecDisplay: String get() = codec ?: "- - -"
}

/**
 * Status information container for the CPC200-CCPA adapter.
 * Aggregates various status messages received from the adapter.
 * Matches Flutter: settings_enums.dart AdapterStatusInfo
 */
data class AdapterStatusInfo(
    /** Operational phase (from message 0x03) */
    val phase: AdapterPhase = AdapterPhase.UNKNOWN,
    /** Phone connection information (from message 0x02) */
    val phoneConnection: PhoneConnectionInfo = PhoneConnectionInfo(),
    /** Software/firmware version (from message 0xCC) */
    val firmwareVersion: String? = null,
    /** Bluetooth device name (from message 0x0D) */
    val bluetoothDeviceName: String? = null,
    /** Bluetooth PIN (from message 0x0C) */
    val bluetoothPIN: String? = null,
    /** WiFi device name (from message 0x0E) */
    val wifiDeviceName: String? = null,
    /** Manufacturer information (from message 0x14) */
    val manufacturerInfo: Map<String, Any>? = null,
    /** Box settings/configuration (from message 0x19) */
    val boxSettings: Map<String, Any>? = null,
    /** Network metadata (from messages 0x0A-0x0E) */
    val networkInfo: Map<String, Any>? = null,
    /** Whether audio packets have been detected recently */
    val hasRecentAudioData: Boolean = false,
    /** Video stream information (from message 0x06) */
    val videoStream: VideoStreamInfo? = null,
    /** Timestamp of last status update */
    val lastUpdated: Long = System.currentTimeMillis(),
) {
    /** Whether the adapter is in a healthy operational state */
    val isHealthy: Boolean
        get() = phase == AdapterPhase.ACTIVE && phoneConnection.isConnected

    /** Whether the adapter is currently operational */
    val isOperational: Boolean
        get() = phase == AdapterPhase.ACTIVE || phase == AdapterPhase.INITIALIZING
}
