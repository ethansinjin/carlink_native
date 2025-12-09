package com.carlink.ui

import android.content.pm.PackageManager
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.CarlinkManager
import com.carlink.logging.FileLogManager
import com.carlink.logging.LogPreset
import com.carlink.logging.LoggingPreferences
import com.carlink.logging.apply
import com.carlink.logging.logInfo
import com.carlink.ui.settings.AdapterStatusInfo
import com.carlink.ui.settings.AdapterStatusMonitor
import com.carlink.ui.settings.ImmersivePreference
import com.carlink.ui.settings.PhoneConnectionInfo
import com.carlink.ui.settings.PhoneConnectionStatus
import com.carlink.ui.settings.PhoneConnectionType
import com.carlink.ui.settings.PhonePlatform
import com.carlink.ui.settings.SettingsTab
import com.carlink.ui.settings.VideoStreamInfo
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings Screen - NavigationRail Settings Interface
 *
 * Uses Material 3 NavigationRail for tablet/automotive landscape layout.
 * Matches the Flutter implementation with vertical sidebar navigation.
 *
 * Provides access to:
 * - Status: Real-time adapter status from protocol messages
 * - Control: Device control commands (reset, disconnect, etc.) and Display Control
 * - Logs: Log file management, log level selection, and export
 *
 * Ported from: example/lib/settings_page.dart
 */
@Composable
fun SettingsScreen(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    onNavigateBack: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.STATUS) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // Log settings screen entry and tab changes
    LaunchedEffect(Unit) {
        logInfo("[UI_STATE] SettingsScreen opened - user is in app settings (NOT viewing CarPlay projection)", tag = "UI")
    }

    LaunchedEffect(selectedTab) {
        logInfo("[UI_STATE] Settings tab changed: $selectedTab", tag = "UI")
    }

    // Get app version
    val appVersion =
        remember {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${packageInfo.versionName}+${packageInfo.longVersionCode}"
            } catch (e: PackageManager.NameNotFoundException) {
                "Unknown"
            }
        }

    // Initialize status monitor
    val statusMonitor = remember { AdapterStatusMonitor.getInstance() }
    LaunchedEffect(carlinkManager) {
        statusMonitor.startMonitoring(carlinkManager)
    }

    // Get view for haptic feedback - Matches Flutter HapticFeedback.lightImpact()
    val view = LocalView.current

    // Matches Flutter SafeArea - Apply system bar insets
    // Surface provides opaque background when used as overlay
    // Use colorScheme.surface to match NavigationRail containerColor
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surface,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
        ) {
        // Left sidebar with NavigationRail
        Column(
            modifier = Modifier.fillMaxHeight(),
        ) {
            // Back button at top - Matches Flutter _buildBackButton with haptic feedback
            Box(
                modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onNavigateBack()
                    },
                    modifier = Modifier.size(AutomotiveDimens.ButtonMinHeight),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(AutomotiveDimens.IconSize),
                    )
                }
            }

            // NavigationRail with tabs - Expanded to fill available space
            // Items centered vertically to match Flutter NavigationRail behavior
            NavigationRail(
                modifier = Modifier.weight(1f),
                containerColor = colorScheme.surface,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                SettingsTab.visibleTabs.forEach { tab ->
                    NavigationRailItem(
                        selected = selectedTab == tab,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            selectedTab = tab
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                            )
                        },
                        label = { Text(tab.title) },
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            // App version at bottom - always visible after NavigationRail
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Version: ",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (appVersion.isEmpty()) "- - -" else appVersion,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    color = if (appVersion.isEmpty()) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                )
            }
        }

        // Tab content - fills remaining space
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .weight(1f),
        ) {
            when (selectedTab) {
                SettingsTab.STATUS -> StatusTabContent(statusMonitor)
                SettingsTab.CONTROL -> ControlTabContent(carlinkManager)
                SettingsTab.LOGS -> LogsTabContent(context, fileLogManager)
            }
        }
        }
    }
}

// ==================== STATUS TAB ====================
// Matches Flutter: status_tab_content.dart

/**
 * Status Tab - Real-time adapter status display from protocol messages
 * Shows: Adapter Status, Phone Connection, Video Stream, Manufacturer Info
 * Matches Flutter status_tab_content.dart
 */
@Composable
private fun StatusTabContent(statusMonitor: AdapterStatusMonitor) {
    val adapterStatus by statusMonitor.currentStatus.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme

    // Centered scrollable content with max width constraint
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Adapter Status Card (full width) - Matches Flutter _buildAdapterStatusCard
            AdapterStatusCard(adapterStatus, colorScheme)

            // Two cards side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Phone Connection Card - Matches Flutter _buildPhoneConnectionCard
                PhoneConnectionCard(
                    modifier = Modifier.weight(1f),
                    phoneConnection = adapterStatus.phoneConnection,
                    colorScheme = colorScheme,
                )

                // Video Stream Card - Matches Flutter _buildVideoStreamCard
                VideoStreamCard(
                    modifier = Modifier.weight(1f),
                    videoStream = adapterStatus.videoStream,
                    colorScheme = colorScheme,
                )
            }

            // Manufacturer Info Card (conditionally shown) - Matches Flutter _buildManufacturerInfoCard
            if (adapterStatus.manufacturerInfo != null) {
                ManufacturerInfoCard(
                    manufacturerInfo = adapterStatus.manufacturerInfo!!,
                    colorScheme = colorScheme,
                )
            }
        }
    }
}

/**
 * Adapter Status Card - Shows phase, firmware, BT/WiFi names
 * Matches Flutter _buildAdapterStatusCard
 */
@Composable
private fun AdapterStatusCard(
    adapterStatus: AdapterStatusInfo,
    colorScheme: ColorScheme,
) {
    val phaseColor = adapterStatus.phase.getColor(colorScheme)

    StatusInfoCard(
        title = "Adapter Status",
        icon = Icons.Default.SystemUpdate,
        iconColor = phaseColor,
        statusText = adapterStatus.phase.displayName,
        statusColor = phaseColor,
    ) {
        StatusDetailRow(
            label = "Firmware Version",
            value = adapterStatus.firmwareVersion ?: "- - -",
            hasValue = adapterStatus.firmwareVersion != null,
        )
        StatusDetailRow(
            label = "BT Name",
            value = adapterStatus.bluetoothDeviceName ?: "- - -",
            hasValue = adapterStatus.bluetoothDeviceName != null,
        )
        StatusDetailRow(
            label = "WiFi Name",
            value = adapterStatus.wifiDeviceName ?: "- - -",
            hasValue = adapterStatus.wifiDeviceName != null,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Message type annotation - matches Flutter exactly
        Text(
            text = "Message Types: 0x03, 0xCC, 0x0D, 0x0E, 0x07, 0x16, 0x19, 0x3E8, 0x3EA",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Phone Connection Card - Shows platform, connection type, BT MAC
 * Matches Flutter _buildPhoneConnectionCard
 */
@Composable
private fun PhoneConnectionCard(
    modifier: Modifier = Modifier,
    phoneConnection: PhoneConnectionInfo,
    colorScheme: ColorScheme,
) {
    val statusColor = phoneConnection.displayColor(colorScheme)

    StatusInfoCard(
        modifier = modifier,
        title = "Phone Connection",
        icon = phoneConnection.displayIcon,
        iconColor = statusColor,
        statusText = phoneConnection.status.displayName,
        statusColor = statusColor,
    ) {
        StatusDetailRow(
            label = "Platform",
            value = phoneConnection.platformDisplay,
            hasValue = phoneConnection.platform != PhonePlatform.UNKNOWN,
        )
        StatusDetailRow(
            label = "Connection Type",
            value = phoneConnection.connectionTypeDisplay,
            hasValue = phoneConnection.connectionType != PhoneConnectionType.UNKNOWN,
        )
        StatusDetailRow(
            label = "BT MAC Address",
            value = phoneConnection.connectedPhoneMacDisplay,
            hasValue = phoneConnection.connectedPhoneMacAddress != null,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Message type annotation - matches Flutter
        Text(
            text = "Message Types: 0x02, 0x04, 0x23, 0x24",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Video Stream Card - Shows resolution, FPS, codec
 * Matches Flutter _buildVideoStreamCard
 */
@Composable
private fun VideoStreamCard(
    modifier: Modifier = Modifier,
    videoStream: VideoStreamInfo?,
    colorScheme: ColorScheme,
) {
    val hasVideo = videoStream != null && (videoStream.receivedWidth != null || videoStream.width != null)
    val statusColor = if (hasVideo) colorScheme.primary else colorScheme.onSurfaceVariant

    StatusInfoCard(
        modifier = modifier,
        title = "Video Stream",
        icon = Icons.Default.Videocam,
        iconColor = statusColor,
        statusText = if (hasVideo) "Streaming" else "Inactive", // Matches Flutter exactly
        statusColor = statusColor,
    ) {
        StatusDetailRow(
            label = "Resolution",
            value = videoStream?.receivedResolutionDisplay ?: "- - -",
            hasValue = videoStream?.receivedWidth != null,
        )
        StatusDetailRow(
            label = "Frame Rate",
            value = videoStream?.frameRateDisplay ?: "- - -",
            hasValue = videoStream?.frameRate != null,
        )
        StatusDetailRow(
            label = "Codec",
            value = videoStream?.codecDisplay ?: "- - -",
            hasValue = videoStream?.codec != null,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Message type annotation - matches Flutter exactly
        Text(
            text = "Message Types: 0x06, Internal Config",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Manufacturer Info Card - Shows hardware version, serial number
 * Matches Flutter _buildManufacturerInfoCard
 */
@Composable
private fun ManufacturerInfoCard(
    manufacturerInfo: Map<String, Any>,
    colorScheme: ColorScheme,
) {
    StatusInfoCard(
        title = "Manufacturer Info",
        icon = Icons.Default.Factory,
        iconColor = colorScheme.secondary,
        statusText = "Available",
        statusColor = colorScheme.secondary,
    ) {
        StatusDetailRow(
            label = "Hardware Version",
            value = manufacturerInfo["a"]?.toString() ?: "- - -",
            hasValue = manufacturerInfo.containsKey("a"),
        )
        StatusDetailRow(
            label = "Serial Number",
            value = manufacturerInfo["b"]?.toString() ?: "- - -",
            hasValue = manufacturerInfo.containsKey("b"),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Message type annotation - matches Flutter
        Text(
            text = "Message Types: 0x14",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Material 3 Status Info Card - matches Flutter implementation
 * Features: icon + title header, colored status text, detail rows
 */
@Composable
private fun StatusInfoCard(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
        ) {
            // Header row with icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status text
            Text(
                text = statusText,
                style =
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = statusColor,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Detail rows
            content()
        }
    }
}

/**
 * Material 3 detail row for status cards
 */
@Composable
private fun StatusDetailRow(
    label: String,
    value: String,
    hasValue: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (hasValue) FontWeight.SemiBold else FontWeight.Normal,
                ),
            color = if (hasValue) colorScheme.onSurface else colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== CONTROL TAB ====================
// Matches Flutter: control_tab_content.dart

/**
 * Button severity levels for semantic color mapping (matches Flutter)
 */
private enum class ButtonSeverity {
    NORMAL, // Primary action (blue/primary)
    WARNING, // Warning action (tertiary/amber)
    DESTRUCTIVE, // Destructive action (error/red)
}

/**
 * Control Tab - Device control commands and Display Control
 * Matches Flutter control_tab_content.dart
 * NOTE: Media Controls are NOT in Flutter Settings - they belong on main screen
 */
@Composable
private fun ControlTabContent(carlinkManager: CarlinkManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    // Check device connection state - Matches Flutter _isDeviceConnected()
    val isDeviceConnected = carlinkManager.state != CarlinkManager.State.DISCONNECTED

    // Immersive mode preference
    val immersivePreference = remember { ImmersivePreference.getInstance(context) }
    val isImmersiveEnabled by immersivePreference.isEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    var showRestartDialog by remember { mutableStateOf(false) }

    // Centered scrollable content with max width constraint
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Two cards side by side (Device Control + System Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Device Control Card - Matches Flutter _buildDeviceControlCard
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = "Device Control",
                    icon = Icons.Default.Devices,
                ) {
                    ControlButton(
                        label = "Disconnect Phone",
                        icon = Icons.Default.PhoneDisabled,
                        severity = ButtonSeverity.WARNING,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            carlinkManager.stop()
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = "Close Adapter",
                        icon = Icons.Default.PowerOff,
                        severity = ButtonSeverity.DESTRUCTIVE,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            carlinkManager.stop()
                        },
                    )
                }

                // System Reset Card - Matches Flutter _buildSystemResetCard
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = "System Reset",
                    icon = Icons.Default.RestartAlt,
                ) {
                    ControlButton(
                        label = "Reset Video Decoder",
                        icon = Icons.Default.VideoSettings,
                        severity = ButtonSeverity.NORMAL,
                        enabled = !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            carlinkManager.resetVideoDecoder()
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = "Reset USB Device",
                        icon = Icons.Default.Usb,
                        severity = ButtonSeverity.DESTRUCTIVE,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            isProcessing = true
                            scope.launch {
                                try {
                                    carlinkManager.restart()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                    )
                }
            }

            // Display Control Card - Matches Flutter _buildDisplayControlCard
            ControlCard(
                title = "Display Control",
                icon = Icons.Default.DisplaySettings, // Matches Flutter Icons.display_settings
            ) {
                // Immersive Mode Toggle - Matches Flutter SwitchListTile exactly
                // Using Row instead of ListItem to avoid dark background
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Immersive Fullscreen Mode",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Dynamic subtitle matching Flutter exactly
                        Text(
                            text =
                                if (isImmersiveEnabled) {
                                    "Immersive Mode, Active"
                                } else {
                                    "AAOS System UI restricting render area"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isImmersiveEnabled,
                        enabled = !isProcessing,
                        onCheckedChange = { enabled ->
                            showRestartDialog = true
                        },
                    )
                }
            }
        }
    }

    // Restart Required Dialog - Matches Flutter _handleImmersiveModeToggle exactly
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = {
                Text(
                    "Restart Required",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    "App must restart to apply immersive mode changes.\n\nThe app will close and must be relaunched manually.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartDialog = false
                        scope.launch {
                            // Toggle the preference
                            immersivePreference.setEnabled(!isImmersiveEnabled)
                            // Stop adapter and exit
                            carlinkManager.stop()
                            kotlinx.coroutines.delay(500)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                ) {
                    Text("Restart Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Material 3 Control Card container
 */
@Composable
private fun ControlCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            content()
        }
    }
}

/**
 * Material 3 Control Button
 */
@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    severity: ButtonSeverity,
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    when (severity) {
        ButtonSeverity.DESTRUCTIVE -> {
            Button(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = colorScheme.onError,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }

        ButtonSeverity.WARNING -> {
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.tertiaryContainer,
                        contentColor = colorScheme.onTertiaryContainer,
                    ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }

        ButtonSeverity.NORMAL -> {
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ==================== LOGS TAB ====================
// Matches Flutter: logs_tab_content.dart

/**
 * Logs Tab - Log file management with log level selector
 * Matches Flutter logs_tab_content.dart
 */
@Composable
private fun LogsTabContent(
    context: android.content.Context,
    fileLogManager: FileLogManager?,
) {
    val scope = rememberCoroutineScope()
    var logFiles by remember { mutableStateOf(emptyList<File>()) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showDebugWarningDialog by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    // File export state - stores the file to export when SAF picker returns
    var pendingExportFile by remember { mutableStateOf<File?>(null) }

    // SAF Document Creator launcher - matches Flutter ACTION_CREATE_DOCUMENT
    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            if (uri != null && pendingExportFile != null) {
                // Write file contents to selected URI
                scope.launch {
                    try {
                        val file = pendingExportFile!!
                        val bytes = file.readBytes()
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(bytes)
                            outputStream.flush()
                        }
                        logInfo("[FILE_EXPORT] Successfully exported ${file.name} (${bytes.size} bytes)", tag = "FILE_LOG")
                        Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        logInfo("[FILE_EXPORT] Export failed: ${e.message}", tag = "FILE_LOG")
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        pendingExportFile = null
                    }
                }
            } else {
                logInfo("[FILE_EXPORT] User cancelled document picker", tag = "FILE_LOG")
                pendingExportFile = null
            }
        }

    // Logging preferences
    val loggingPreferences = remember { LoggingPreferences.getInstance(context) }
    val isLoggingEnabled by loggingPreferences.loggingEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val currentLogLevel by loggingPreferences.logLevelFlow.collectAsStateWithLifecycle(initialValue = LogPreset.NORMAL)

    // Check if debug build
    val isDebugBuild =
        remember {
            try {
                val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } catch (e: Exception) {
                false
            }
        }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    LaunchedEffect(fileLogManager) {
        logFiles = fileLogManager?.getLogFiles() ?: emptyList()
    }

    if (fileLogManager == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "File logging not available",
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Centered scrollable content with max width constraint
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // File Logging Card - Matches Flutter _buildFileLoggingCard
            LoggingControlCard(
                title = "File Logging",
                icon = Icons.Filled.Article,
            ) {
                // Toggle row - Matches Flutter SwitchListTile
                // Using Row instead of ListItem to avoid dark background
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Save logs to file",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Store app logs in private storage for debugging",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isLoggingEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                // Check for debug build warning
                                if (enabled && isDebugBuild) {
                                    showDebugWarningDialog = true
                                } else {
                                    loggingPreferences.setLoggingEnabled(enabled)
                                    if (enabled) {
                                        fileLogManager.enable()
                                    } else {
                                        fileLogManager.disable()
                                    }
                                    logFiles = fileLogManager.getLogFiles()
                                }
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Log Level Selector - Matches Flutter _buildLogLevelSelector exactly
                // Uses a tappable container box, not a button
                Column {
                    Text(
                        text = "Log Level",
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = { showLogLevelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, colorScheme.outline),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentLogLevel.displayName,
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    color = currentLogLevel.color,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentLogLevel.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // File Logging Status - Embedded inside File Logging card
                // Matches Flutter _buildFileLoggingStatusRows (inside same card)
                val totalSize = fileLogManager.getTotalLogSize()
                val currentFileSize = fileLogManager.getCurrentLogFileSize()

                if (isLoggingEnabled || logFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "File Logging Status",
                                style =
                                    MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FileLoggingStatusRow(
                                label = "Status",
                                value = if (isLoggingEnabled) "Active" else "Disabled",
                            )
                            FileLoggingStatusRow(
                                label = "Current file size",
                                value = formatBytes(currentFileSize),
                            )
                            FileLoggingStatusRow(
                                label = "Total files",
                                value = "${logFiles.size}",
                            )
                            FileLoggingStatusRow(
                                label = "Total size",
                                value = "${String.format(Locale.US, "%.2f", totalSize / (1024.0 * 1024.0))} MB",
                            )
                        }
                    }
                }
            }

            // Log Files Card - Matches Flutter _buildLogFilesCardWithActions
            LoggingControlCard(
                title = "Log Files",
                icon = Icons.Default.Folder,
            ) {
                // File count text - Matches Flutter
                Text(
                    text = "${logFiles.size} log file${if (logFiles.size != 1) "s" else ""} available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Log file items with export button - Matches Flutter _buildLogFileItem
                logFiles.forEach { file ->
                    LogFileItem(
                        file = file,
                        dateFormat = dateFormat,
                        onDelete = { showDeleteDialog = file },
                        onExport = {
                            // Launch SAF document picker with suggested filename
                            // Matches Flutter: FileExportService.createDocument()
                            logInfo("[FILE_EXPORT] Starting export for: ${file.name}", tag = "FILE_LOG")
                            pendingExportFile = file
                            createDocumentLauncher.launch(file.name)
                        },
                    )
                }

                // Help text - Matches Flutter
                if (logFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Use the save icon to export a log file to your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    // Log Level Selector Dialog - Matches Flutter _LogLevelSelectorDialog
    if (showLogLevelDialog) {
        LogLevelSelectorDialog(
            currentLevel = currentLogLevel,
            onDismiss = { showLogLevelDialog = false },
            onSelectLevel = { level ->
                scope.launch {
                    loggingPreferences.setLogLevel(level)
                    level.apply()
                    showLogLevelDialog = false
                }
            },
        )
    }

    // Debug APK Warning Dialog - Matches Flutter _DebugApkWarningDialog
    if (showDebugWarningDialog) {
        AlertDialog(
            onDismissRequest = { showDebugWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colorScheme.error,
                )
            },
            title = {
                Text(
                    "Debug Build Detected",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    "File logging is disabled in debug builds to prevent excessive log file growth. Use release builds for persistent logging.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { showDebugWarningDialog = false }) {
                    Text("OK")
                }
            },
        )
    }

    // Delete confirmation dialog - Matches Flutter DeleteConfirmationDialog
    showDeleteDialog?.let { file ->
        val fileSize =
            try {
                file.length()
            } catch (e: Exception) {
                0L
            }
        val fileSizeStr = formatBytes(fileSize)

        Dialog(onDismissRequest = { showDeleteDialog = null }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Circular icon with error background - Matches Flutter ResponsiveDialog
                    Box(
                        modifier =
                            Modifier
                                .size(64.dp)
                                .background(
                                    color = colorScheme.error.copy(alpha = 0.15f),
                                    shape = CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title
                    Text(
                        text = "Delete Log File?",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // File information box - Matches Flutter buildContentBox
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainer,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "File to delete:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "1 file",
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Total size:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = fileSizeStr,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // File name box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Warning box - Matches Flutter buildWarningBox
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.errorContainer.copy(alpha = 0.4f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Permanent Deletion",
                                    style =
                                        MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    color = colorScheme.onErrorContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "This file will be permanently deleted from your device. This action cannot be undone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onErrorContainer,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action buttons - Matches Flutter layout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = { showDeleteDialog = null },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                fileLogManager.deleteLogFile(file)
                                logFiles = fileLogManager.getLogFiles()
                                showDeleteDialog = null
                            },
                            modifier = Modifier.weight(2f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colorScheme.error,
                                    contentColor = colorScheme.onError,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete File")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Log Level Selector Dialog - 2-column layout
 * Matches Flutter _LogLevelSelectorDialog exactly with:
 * - Icon in title row
 * - Two hardcoded columns with specific preset order
 * - Left: Silent, Minimal, Normal, Performance
 * - Right: RX Messages, Video Only, Audio Only, Debug
 */
@Composable
private fun LogLevelSelectorDialog(
    currentLevel: LogPreset,
    onDismiss: () -> Unit,
    onSelectLevel: (LogPreset) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Define preset order matching Flutter exactly
    val leftColumn =
        listOf(
            LogPreset.SILENT,
            LogPreset.MINIMAL,
            LogPreset.NORMAL,
            LogPreset.PERFORMANCE,
        )
    val rightColumn =
        listOf(
            LogPreset.RX_MESSAGES,
            LogPreset.VIDEO_ONLY,
            LogPreset.AUDIO_ONLY,
            LogPreset.DEBUG,
        )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(28.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                // Title row with icon - Matches Flutter exactly
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Select Log Level",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Two columns with specific preset order
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Left column
                    Column(modifier = Modifier.weight(1f)) {
                        leftColumn.forEach { preset ->
                            LogPresetChip(
                                preset = preset,
                                isSelected = preset == currentLevel,
                                onClick = { onSelectLevel(preset) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    // Right column
                    Column(modifier = Modifier.weight(1f)) {
                        rightColumn.forEach { preset ->
                            LogPresetChip(
                                preset = preset,
                                isSelected = preset == currentLevel,
                                onClick = { onSelectLevel(preset) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cancel button aligned right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Log Preset Chip for selector dialog
 * Matches Flutter _buildLogLevelOption exactly
 */
@Composable
private fun LogPresetChip(
    preset: LogPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) preset.color.copy(alpha = 0.15f) else colorScheme.surfaceContainerHighest,
        border =
            androidx.compose.foundation.BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) preset.color else colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Circular selection indicator - matches Flutter
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .then(
                            if (isSelected) {
                                Modifier.background(preset.color, shape = androidx.compose.foundation.shape.CircleShape)
                            } else {
                                Modifier.background(Color.Transparent, shape = androidx.compose.foundation.shape.CircleShape)
                            },
                        ).border(
                            width = 2.dp,
                            color = preset.color,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colorScheme.surface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Level info
            Column(
                modifier = Modifier.weight(1f),
            ) {
                // Title is ALWAYS the preset color - matches Flutter line 904
                Text(
                    text = preset.displayName,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    color = preset.color,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Material 3 Logging Control Card container
 */
@Composable
private fun LoggingControlCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

/**
 * Material 3 Log file item with export button
 * Matches Flutter _buildLogFileItem
 */
@Composable
private fun LogFileItem(
    file: File,
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File icon
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatFileSize(file.length())} • ${dateFormat.format(Date(file.lastModified()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            // Export button - Matches Flutter save_alt icon
            IconButton(onClick = onExport) {
                Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = "Export",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * File Logging Status Row - for embedded status display
 * Matches Flutter _buildStatusRow exactly
 */
@Composable
private fun FileLoggingStatusRow(
    label: String,
    value: String,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

// ==================== UTILITY FUNCTIONS ====================

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return "${String.format(Locale.US, "%.1f", kb)} KB"
}
