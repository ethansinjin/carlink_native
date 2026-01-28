package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
    val requiresDebugMode: Boolean = false,
) {
    CONTROL("Control", Icons.Default.Settings, requiresDebugMode = false),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article, requiresDebugMode = true),
    PLAYBACK("Playback", Icons.Default.PlayCircle, requiresDebugMode = true),
    RECORD("Record", Icons.Default.FiberManualRecord, requiresDebugMode = true),
    ;

    companion object {
        /**
         * Returns the list of visible tabs based on debug mode state.
         *
         * @param debugModeEnabled If true, all tabs are visible. If false, only non-debug tabs are shown.
         */
        fun getVisibleTabs(debugModeEnabled: Boolean): List<SettingsTab> =
            if (debugModeEnabled) {
                entries.toList()
            } else {
                entries.filter { !it.requiresDebugMode }
            }

        /**
         * Legacy property for backward compatibility.
         * @deprecated Use getVisibleTabs(debugModeEnabled) instead.
         */
        @Deprecated(
            message = "Use getVisibleTabs(debugModeEnabled) instead",
            replaceWith = ReplaceWith("getVisibleTabs(true)"),
        )
        val visibleTabs: List<SettingsTab> = entries.toList()
    }
}
