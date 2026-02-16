package com.carlink.cluster

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.carlink.logging.Logger
import com.carlink.logging.logInfo

/**
 * Headless CarAppService for cluster navigation display only.
 *
 * This service is bound by Templates Host and provides:
 * - DISPLAY_TYPE_CLUSTER → CarlinkClusterSession (observes NavigationStateManager)
 * - DISPLAY_TYPE_MAIN → ClusterMainSession (zombie — empty template, no functionality)
 *
 * MainActivity remains the sole LAUNCHER and owns all USB/video/audio pipelines.
 * This service does NOT initialize CarlinkManager, video, audio, or USB.
 */
class CarlinkClusterService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        logInfo("[CLUSTER_SVC] Creating session (no SessionInfo — fallback)", tag = Logger.Tags.CLUSTER)
        return ClusterMainSession()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        logInfo("[CLUSTER_SVC] Creating session: displayType=${sessionInfo.displayType}", tag = Logger.Tags.CLUSTER)
        return when (sessionInfo.displayType) {
            SessionInfo.DISPLAY_TYPE_CLUSTER -> CarlinkClusterSession()
            else -> ClusterMainSession()
        }
    }
}
