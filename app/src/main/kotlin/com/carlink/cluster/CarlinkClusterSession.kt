package com.carlink.cluster

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.carlink.logging.Logger
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import com.carlink.navigation.DistanceFormatter
import com.carlink.navigation.ManeuverMapper
import com.carlink.navigation.NavigationState
import com.carlink.navigation.NavigationStateManager
import com.carlink.navigation.TripBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Standard Car App Library cluster session — renders NavigationTemplate directly via onGetTemplate().
 *
 * NOT currently active. CarlinkClusterService unconditionally returns [ClusterMainSession]
 * (no SessionInfo-based routing yet). However, this is NOT dead code — it is the correct
 * implementation for non-GM AAOS platforms and will be selected once the platform switch lands.
 *
 * Two rendering paths exist in AAOS:
 * - **GM AAOS**: Has internal OnStarTurnByTurnManager that consumes NavigationManager.updateTrip()
 *   data and renders it on the cluster. [ClusterMainSession] targets this path — it never renders
 *   a Screen, just relays Trip data. Works on gminfo37.
 * - **Standard AAOS** (Volvo, Polestar, Renesas, etc.): Templates Host renders the Screen's
 *   onGetTemplate() directly on the cluster display. This class targets that path — its
 *   [CarlinkClusterScreen] returns NavigationTemplate with RoutingInfo (maneuver, distance, road).
 *
 * TODO: Add platform-aware switch in [CarlinkClusterService] gated on
 *   PlatformDetector.PlatformInfo.isGmAaos (already available). 2026-04-20 POTATO
 *   evidence shows GM's cluster is owned by VMSClusterService + OnStarTurnByTurnManager;
 *   the GM branch may end up being "return null / don't register" rather than
 *   "use ClusterMainSession" — NavigationManager.updateTrip() reaching GM's cluster is
 *   unverified.
 * TODO: Port primary/secondary multiplexing from [ClusterMainSession] to handle emulator
 *   dual-session creation (DISPLAY_TYPE_MAIN + DISPLAY_TYPE_CLUSTER).
 */
class CarlinkClusterSession : Session() {
    private var screen: CarlinkClusterScreen? = null
    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false

    // Suppresses spurious navigationEnded() before any active state has been observed:
    // the initial NavigationState emitted on cold start is idle, and calling navigationEnded()
    // without a prior navigationStarted() is a protocol violation that the Host rejects.
    private var hasSeenActiveNav = false

    override fun onCreateScreen(intent: Intent): Screen {
        logInfo("[CLUSTER] Cluster session screen created", tag = Logger.Tags.NAVI)

        val clusterScreen = CarlinkClusterScreen(carContext)
        screen = clusterScreen

        // Get NavigationManager from CarContext
        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            logNavi { "[CLUSTER] NavigationManager obtained successfully" }
        } catch (e: Exception) {
            logError("[CLUSTER] Failed to get NavigationManager: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
        }

        navigationManager?.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    logInfo("[CLUSTER] onStopNavigation callback — ending navigation", tag = Logger.Tags.NAVI)
                    isNavigating = false
                }

                // Auto-drive (hands-free) is not supported by the upstream NaviJSON protocol;
                // log only so the host-level event is still observable in traces.
                override fun onAutoDriveEnabled() {
                    logNavi { "[CLUSTER] Auto drive enabled" }
                }
            },
        )

        // Start observing navigation state
        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    logInfo("[CLUSTER] Session destroyed — cleaning up", tag = Logger.Tags.NAVI)
                    if (isNavigating) {
                        try {
                            navigationManager?.navigationEnded()
                            logNavi { "[CLUSTER] navigationEnded() called on session destroy" }
                        } catch (e: Exception) {
                            logError(
                                "[CLUSTER] navigationEnded() failed on destroy: ${e.message}",
                                tag = Logger.Tags.NAVI,
                                throwable = e,
                            )
                        }
                        isNavigating = false
                    }
                    scope?.cancel()
                    scope = null
                    screen = null
                    navigationManager = null
                }
            },
        )

        return clusterScreen
    }

    /**
     * Collect navigation state with debounce for rapid partial updates.
     *
     * NaviJSON arrives in bursts (1-5 fields per message, ~100-500ms apart — see
     * NavigationStateManager KDoc). Debouncing coalesces the burst into a single
     * NavigationManager.updateTrip() relay per stable state.
     */
    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        NavigationStateManager.state.collectLatest { state ->
            // Cancel pending debounce — new state arrived
            debounceJob?.cancel()

            // Debounce: wait 200ms for state to stabilize before pushing
            debounceJob =
                scope?.launch {
                    delay(200)
                    processStateUpdate(state)
                }
        }
    }

    private fun processStateUpdate(state: NavigationState) {
        val navManager = navigationManager
        if (navManager == null) {
            logWarn("[CLUSTER] NavigationManager is null — cannot push state update", tag = Logger.Tags.NAVI)
            return
        }

        if (state.isActive) {
            hasSeenActiveNav = true

            // Start navigation if not already started
            if (!isNavigating) {
                logInfo("[CLUSTER] Navigation started", tag = Logger.Tags.NAVI)
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                } catch (e: Exception) {
                    logError(
                        "[CLUSTER] navigationStarted() failed: ${e.message}",
                        tag = Logger.Tags.NAVI,
                        throwable = e,
                    )
                    return
                }
            }

            // Build and push Trip
            try {
                val trip = TripBuilder.buildTrip(state, carContext)
                navManager.updateTrip(trip)
                logNavi {
                    "[CLUSTER] Trip updated: maneuver=${state.maneuverType}, " +
                        "dist=${state.remainDistance}m, road=${state.roadName}, " +
                        "dest=${state.destinationName}, eta=${state.timeToDestination}s"
                }
            } catch (e: Exception) {
                logError("[CLUSTER] updateTrip() failed: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
            }

            // Update cluster screen
            screen?.updateState(state)
        } else if (state.isIdle && isNavigating && hasSeenActiveNav) {
            // Navigation ended
            logInfo("[CLUSTER] Navigation ended (NaviStatus=0)", tag = Logger.Tags.NAVI)
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                logError("[CLUSTER] navigationEnded() failed: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
            }
            isNavigating = false
            screen?.updateState(state)
        }
    }
}

/**
 * Screen rendered on the instrument cluster via Templates Host.
 *
 * Returns a NavigationTemplate with RoutingInfo showing:
 * - Current maneuver arrow (via Maneuver type)
 * - Distance to next maneuver
 * - Road name (via Step cue)
 */
class CarlinkClusterScreen(
    carContext: CarContext,
) : Screen(carContext) {
    private var currentState: NavigationState? = null

    private val actionStrip =
        ActionStrip
            .Builder()
            .addAction(Action.APP_ICON)
            .build()

    fun updateState(state: NavigationState) {
        currentState = state
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val state = currentState
        if (state == null || !state.isActive) {
            logNavi { "[CLUSTER_SCREEN] Returning empty NavigationTemplate (state=${state?.status ?: "null"})" }
            return NavigationTemplate
                .Builder()
                .setActionStrip(actionStrip)
                .build()
        }

        return try {
            val maneuver = ManeuverMapper.buildManeuver(state, carContext)

            val stepBuilder = Step.Builder()
            stepBuilder.setManeuver(maneuver)
            state.roadName?.let { stepBuilder.setCue(it) }

            val distance = DistanceFormatter.toDistance(state.remainDistance)

            val routingInfo =
                RoutingInfo
                    .Builder()
                    .setCurrentStep(stepBuilder.build(), distance)
                    .build()

            logNavi {
                "[CLUSTER_SCREEN] Template built: maneuver=${state.maneuverType}, " +
                    "dist=${state.remainDistance}m, road=${state.roadName}"
            }

            NavigationTemplate
                .Builder()
                .setActionStrip(actionStrip)
                .setNavigationInfo(routingInfo)
                .build()
        } catch (e: Exception) {
            // Car App Library builders throw on malformed RoutingInfo or unknown Maneuver
            // types from adapter data; fall back to an empty template so the Host doesn't crash.
            logError(
                "[CLUSTER_SCREEN] Failed to build NavigationTemplate: ${e.message}",
                tag = Logger.Tags.NAVI,
                throwable = e,
            )
            NavigationTemplate
                .Builder()
                .setActionStrip(actionStrip)
                .build()
        }
    }
}
