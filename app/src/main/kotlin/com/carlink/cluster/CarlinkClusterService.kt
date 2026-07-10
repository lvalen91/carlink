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
 * Currently always returns [ClusterMainSession] (GM AAOS path — NavigationManager relay
 * feeding GM's internal OnStarTurnByTurnManager). For non-GM AAOS platforms,
 * [CarlinkClusterSession] (direct NavigationTemplate rendering) should be returned instead.
 * TODO: gate session choice on PlatformInfo.isGmAaos. NOTE: 2026-04-20 POTATO GM runs
 * show the cluster is driven entirely by VMSClusterService/NavigationClusterService +
 * OnStarTurnByTurnManager — our CarAppService may not be bound on GM at all. Verify
 * NavigationManager.updateTrip() actually reaches GM's cluster before treating
 * ClusterMainSession as the authoritative "GM path".
 *
 * MainActivity remains the sole LAUNCHER and owns all USB/video/audio pipelines.
 * This service does NOT initialize CarlinkManager, video, audio, or USB.
 *
 * Ships android:enabled="false" in the manifest; the component is toggled on at runtime
 * by AdapterConfigPreference.applyClusterComponentState() when cluster support is enabled.
 */
class CarlinkClusterService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    // Deprecated pre-SessionInfo overload; kept for hosts that bind without SessionInfo.
    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        logInfo("[CLUSTER_SVC] Creating session (no SessionInfo — fallback)", tag = Logger.Tags.CLUSTER)
        return ClusterMainSession()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        logInfo("[CLUSTER_SVC] Creating session: displayType=${sessionInfo.displayType}", tag = Logger.Tags.CLUSTER)
        return ClusterMainSession()
    }
}
