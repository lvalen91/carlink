package com.carlink.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accumulated navigation state from incremental NaviJSON updates.
 *
 * The adapter sends partial JSON updates (1-5 fields per message, ~100-500ms apart).
 * This data class holds the merged state across all updates until a flush (NaviStatus=0).
 *
 * Next-step fields are populated when the adapter firmware sends a double-maneuver burst
 * (two maneuver messages within ~50ms). The first message is the current maneuver, the
 * second is the upcoming maneuver preview. Without burst detection the second would
 * overwrite the first, showing the wrong road name on the cluster.
 */
data class NavigationState(
    val status: Int = 0, // 0=idle, 1=active, 2=calculating
    val maneuverType: Int = 0, // CPManeuverType 0-53
    val orderType: Int = 0, // 0=continue, 1=turn, 2=exit, 3=roundabout, 4=uturn, 5=keepLeft, 6=keepRight
    val roadName: String? = null,
    val remainDistance: Int = 0, // Meters to next maneuver
    val distanceToDestination: Int = 0, // Total meters remaining
    val timeToDestination: Int = 0, // Seconds to destination
    val destinationName: String? = null,
    val appName: String? = null, // "Apple Maps" etc.
    val turnAngle: Int = 0, // Turn angle in degrees
    val turnSide: Int = 0, // 0=right-hand driving, 1=left-hand driving
    val junctionType: Int = 0, // 0=intersection, 1=roundabout
    val roundaboutExit: Int = 0, // NaviRoundaboutExit (1-19, 0=not roundabout)
    // Driver-relative roundabout exit angle (iAP2 0x000b for CarPlay, NaviTurnAngle for AA),
    // signed degrees. Null when unavailable — drives the directional `..._WITH_ANGLE` Maneuver
    // for the VCUNH1 cluster (see ManeuverMapper.roundaboutExitAngleAndroidx). Distinct from
    // [turnAngle]: nullable so "absent" is not confused with a genuine 0° (straight) exit.
    val exitAngle: Int? = null,
    // Next-step fields — from firmware double-maneuver burst
    val nextManeuverType: Int? = null,
    val nextOrderType: Int? = null,
    val nextRoadName: String? = null,
    val nextTurnAngle: Int? = null,
    val nextJunctionType: Int? = null,
    val nextRoundaboutExit: Int? = null,
    val nextExitAngle: Int? = null,
) {
    val isActive: Boolean get() = status == 1
    val isIdle: Boolean get() = status == 0
    val hasNextStep: Boolean get() = nextManeuverType != null
}

/**
 * Manages navigation state from CarPlay NaviJSON messages.
 *
 * Thread safety: Called from USB read thread, publishes via StateFlow (thread-safe).
 * Merge strategy:
 * - Incremental: new fields merge into existing state
 * - Flush: NaviStatus=0 clears all state
 *
 * Burst detection: The CPC200-CCPA adapter firmware sends a double-maneuver burst
 * (two maneuver messages within 0.3–4ms) for transient maneuvers like depart and
 * merge. The first message is the current maneuver, the second is the upcoming
 * preview. Without detection the second overwrites the first, causing the cluster
 * to display the wrong road name. Bursts are detected by timestamp — a maneuver
 * message arriving within [BURST_THRESHOLD_MS] of a previous one is routed to the
 * next-step fields instead of overwriting current.
 */
object NavigationStateManager {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    /** Burst window: two maneuver messages within this threshold are current + next. */
    private const val BURST_THRESHOLD_MS = 50L

    // Intentionally a literal — DO NOT swap to BuildConfig.CLUSTER_ICON_AUTHORITY for
    // symmetry with ClusterIconShimProvider. This constant is the probe target for the
    // runtime guard below, and the probe is meaningful only against the authority
    // Templates Host actually calls (the GM literal hardcoded in its dex). On the play
    // flavor our shim is registered under a different (applicationId-suffixed) authority
    // to satisfy Play Console; templating this would make resolveContentProvider() find
    // our own shim and incorrectly enable the AA bitmap path when Templates Host's call
    // still goes nowhere — silent icon failure instead of the documented graceful fallback.
    private const val CLUSTER_ICON_PROVIDER_AUTHORITY =
        "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"

    /** Timestamp (elapsedRealtime) of the last maneuver-bearing message. */
    private var lastManeuverMs = 0L

    // ─── Tier C v5 (timeline-driven cursor, Apple as verification) ───
    /** Cursor into ComposedIconStore.currentRoute(). Self-advances on physical crossing signals. */
    @Volatile
    private var localCursor: Int = 0

    /** Reference identity of the last seen route; reset cursor on change. */
    @Volatile
    private var lastRouteRef: com.carlink.navigation.Iap2RouteData? = null

    /**
     * Previous NaviRemainDistance. -1 means unseeded (first event or post-flush).
     * Cursor self-advances when (lastDistance in 0..49 AND incomingDist >= 50) — the
     * pattern of "distance was near a maneuver point, now reset to next maneuver's distance."
     * Empirically verified: NaviRemainDistance = distance to NEXT maneuver, jumps up by
     * hundreds-to-thousands of meters when user crosses each maneuver point.
     */
    @Volatile
    private var lastDistance: Int = -1

    /**
     * True once the cursor has been bootstrapped to Apple's first valid step match after
     * a route load. Prevents subsequent Apple previews from forward-snapping the cursor.
     * Reset on flush, clear(), and route change.
     */
    @Volatile
    private var bootstrapDone: Boolean = false

    // ─── Tier C v6.1 (authoritative cursor from iAP2 0x5201 field 0x000d) ───
    /**
     * Last parsed iAP2 0x5201 RouteGuidanceUpdate state.
     *
     * Set by [CarlinkManager.processMediaMetadata] via [setLastIap2State] BEFORE
     * [onNaviJson] runs (same USB read thread). Null when the most recent NaviJSON
     * lacked `_iap2`, when parsing failed, or after flush / clear. v6.1's cursor body
     * reads this — never preserves a stale value across a tick that didn't carry `_iap2`.
     */
    @Volatile
    private var lastIap2State: Iap2RouteGuidanceState? = null

    /**
     * Set the latest parsed 0x5201 state. Pass null on missing `_iap2` or parse failure
     * to prevent stale-cursor reuse. Called from the USB read thread.
     */
    fun setLastIap2State(state: Iap2RouteGuidanceState?) {
        lastIap2State = state
    }

    /**
     * Latest AA maneuver icon received via MEDIA_DATA sub-type 201.
     * Null when no icon available (CarPlay mode, or before first icon arrives).
     * The adapter sends the icon PNG ~1s before/with each NaviJSON maneuver update.
     */
    @Volatile
    var currentManeuverIcon: Bitmap? = null
        private set

    /** Content hash of the current icon to avoid redundant BitmapFactory decodes. */
    private var currentIconHash = 0

    /** Null until checked; then true when the cluster icon provider can be resolved by this app. */
    @Volatile
    private var isClusterIconProviderAvailable: Boolean? = null

    /**
     * True on the GM VCU/VCUNH1 (CT5, AAOS 14) cluster, where the QNX safety-domain VM renders a
     * native Altia sprite from the Maneuver enum and MASKS the app bitmap/URI. When set, the
     * AA-bitmap cluster-icon path is dead, so [canUseAaManeuverIcon] returns false and any pending
     * icon is dropped. Set once from [com.carlink.CarlinkManager] init via [setClusterEnumOnly].
     * BEST-EFFORT / UNTESTED — keyed on [com.carlink.platform.PlatformDetector.PlatformInfo.isVcuCluster].
     */
    @Volatile
    private var clusterEnumOnly: Boolean = false

    /**
     * Resolve whether Templates Host's ClusterIconContentProvider authority is actually
     * available to this app. Play builds cannot ship our shim with that authority, so AA
     * bitmap icons must be disabled when the authority cannot be resolved at runtime.
     *
     * Called once from [com.carlink.CarlinkManager] init.
     */
    fun initialize(context: Context) {
        if (isClusterIconProviderAvailable != null) return

        synchronized(this) {
            if (isClusterIconProviderAvailable != null) return

            val appContext = context.applicationContext
            val providerInfo =
                try {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.resolveContentProvider(CLUSTER_ICON_PROVIDER_AUTHORITY, 0)
                } catch (e: Exception) {
                    logWarn(
                        "[NAVI_ICON] Failed to resolve $CLUSTER_ICON_PROVIDER_AUTHORITY: ${e.message}",
                        tag = Logger.Tags.NAVI,
                    )
                    null
                }

            val isAccessible =
                providerInfo != null &&
                    (providerInfo.packageName == appContext.packageName || providerInfo.exported)

            isClusterIconProviderAvailable = isAccessible

            if (isAccessible) {
                logInfo(
                    "[NAVI_ICON] Cluster icon provider available via ${providerInfo.packageName} " +
                        "(exported=${providerInfo.exported}) — AA maneuver bitmaps enabled",
                    tag = Logger.Tags.NAVI,
                )
            } else {
                dropCurrentManeuverIcon()
                logWarn(
                    "[NAVI_ICON] Cluster icon provider unavailable — AA maneuver bitmaps disabled; " +
                        "falling back to type-based maneuver icons",
                    tag = Logger.Tags.NAVI,
                )
            }
        }
    }

    /**
     * Declare whether this is an enum-only cluster (VCU/VCUNH1) where app maneuver bitmaps are
     * masked. Called once from [com.carlink.CarlinkManager] init. Drops any current icon so the
     * dead AA-bitmap path goes inert immediately.
     */
    fun setClusterEnumOnly(value: Boolean) {
        clusterEnumOnly = value
        if (value) dropCurrentManeuverIcon()
    }

    /** True unless this is an enum-only cluster OR we confirmed the provider is unavailable. */
    fun canUseAaManeuverIcon(): Boolean = !clusterEnumOnly && isClusterIconProviderAvailable != false

    private fun dropCurrentManeuverIcon() {
        val hadIcon = currentManeuverIcon != null || currentIconHash != 0
        currentManeuverIcon = null
        currentIconHash = 0
        if (hadIcon) {
            ManeuverMapper.clearCache()
        }
    }

    private fun orderTypeToCpManeuverType(orderType: Int, turnSide: Int, roundaboutExit: Int): Int {
        // Firmware bug: the AA NaviOrderType lookup table is lossy — several roundabout
        // maneuvers collapse to orderType=16 (STRAIGHT) or other non-roundabout types.
        // When roundaboutExit > 0, the phone IS navigating a roundabout regardless of
        // what orderType says. Override to the correct roundabout-exit cpType.
        if (roundaboutExit > 0 && orderType !in intArrayOf(13, 14, 15)) {
            return 27 + roundaboutExit.coerceIn(1, 19)
        }

        return when (orderType) {
            0  -> 5                                          // MERGE → followRoad
            1  -> 11                                         // DEPART → proceedToRoute
            2  -> when (turnSide) {                          // DESTINATION → arrived (live: proto 19 → orderType=2)
                1 -> 24                                      //   turnSide=1 (left) → arrivedLeft
                2 -> 25                                      //   turnSide=2 (right) → arrivedRight
                else -> 12                                   //   unspecified → arrived
            }
            4  -> 3                                          // NAME_CHANGE → straight
            5  -> if (turnSide == 1) 49 else 50              // SLIGHT_TURN → slightLeft/Right
            6  -> if (turnSide == 1) 1 else 2                // TURN → left/right
            7  -> if (turnSide == 1) 47 else 48              // SHARP_TURN → sharpLeft/Right
            8  -> 4                                          // U_TURN → uTurn
            9  -> 9                                          // ON_RAMP → rampOn
            10 -> if (turnSide == 1) 22 else 23              // OFF_RAMP → rampOffLeft/Right
            11 -> if (turnSide == 1) 52 else 53              // FORK → changeHwyLeft/Right
            13 -> 6                                          // ROUNDABOUT_ENTER
            14 -> 7                                          // ROUNDABOUT_EXIT
            15 -> 27 + roundaboutExit.coerceIn(1, 19)       // ROUNDABOUT_E&E → exit 1-19
            16 -> 3                                          // STRAIGHT
            18 -> 15                                         // FERRY_BOAT → enterFerry
            19 -> 15                                         // FERRY_TRAIN → enterFerry
            21 -> 12                                         // DESTINATION → arrived
            else -> {
                logWarn("[NAVI] Unknown NaviOrderType=$orderType turnSide=$turnSide", tag = Logger.Tags.NAVI)
                5
            }
        }
    }

    /**
     * Process an incoming NaviJSON payload (incremental update).
     *
     * @param payload Parsed JSON fields from MEDIA_DATA subtype 200
     */
    fun onNaviJson(payload: Map<String, Any>) {
        if (payload.isEmpty()) {
            logNavi { "[NAVI] Empty NaviJSON payload received — ignoring" }
            return
        }

        logNavi {
            "[NAVI] Received NaviJSON: keys=${payload.keys}, " +
                "values=${payload.entries.joinToString { "${it.key}=${it.value}" }}"
        }

        val naviStatus = (payload["NaviStatus"] as? Number)?.toInt()

        // Flush signal: NaviStatus=0 or 2 → clear entire state including next-step.
        // CarPlay sends 0; Android Auto sends 2 (inactive) when navigation stops.
        if (naviStatus == 0 || naviStatus == 2) {
            logInfo("[NAVI] Flush signal received (NaviStatus=$naviStatus) — clearing state", tag = Logger.Tags.NAVI)
            _state.value = NavigationState()
            lastManeuverMs = 0
            localCursor = 0
            lastRouteRef = null
            lastDistance = -1
            bootstrapDone = false
            lastIap2State = null
            // Drop any pre-composed maneuver icons from the prior route. Next route-calc
            // (incoming _iap2m field) repopulates via ComposedIconStore.populateFromIap2m.
            com.carlink.navigation.compose.ComposedIconStore.clear()
            com.carlink.navigation.compose.ManeuverIconDebugDumper.resetSession()
            return
        }

        // Burst detection: a maneuver-bearing message within BURST_THRESHOLD_MS of the
        // previous one is the adapter firmware's preview of the next maneuver.
        val isManeuverMessage = payload.containsKey("NaviManeuverType") || payload.containsKey("NaviOrderType")
        val now = SystemClock.elapsedRealtime()
        val gapMs = now - lastManeuverMs
        val isBurst = isManeuverMessage && lastManeuverMs > 0 && gapMs < BURST_THRESHOLD_MS

        if (isManeuverMessage) {
            lastManeuverMs = now
        }

        val current = _state.value

        // Resolve maneuver type: CarPlay sends NaviManeuverType; AA sends NaviOrderType
        val rawCpType = (payload["NaviManeuverType"] as? Number)?.toInt()
        val rawOrderType = (payload["NaviOrderType"] as? Number)?.toInt()
        val rawTurnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide
        val rawRoundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt() ?: current.roundaboutExit
        val resolvedManeuverType = rawCpType
            ?: rawOrderType?.let { orderTypeToCpManeuverType(it, rawTurnSide, rawRoundaboutExit) }

        if (rawCpType == null && rawOrderType != null) {
            logNavi { "[NAVI] AA orderType=$rawOrderType turnSide=$rawTurnSide → cpType=$resolvedManeuverType" }
        }

        if (isBurst) {
            // Burst: route maneuver-specific fields to next-step slots.
            // Route-level fields (status, distance, destination, turnSide) still update current.
            val merged =
                current.copy(
                    status = naviStatus ?: current.status,
                    remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
                    distanceToDestination =
                        (payload["NaviDistanceToDestination"] as? Number)?.toInt()
                            ?: current.distanceToDestination,
                    timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
                    destinationName =
                        (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() }
                            ?: current.destinationName,
                    appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
                    turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
                    // Maneuver fields → next-step
                    nextManeuverType = resolvedManeuverType,
                    nextOrderType = (payload["NaviOrderType"] as? Number)?.toInt(),
                    nextRoadName = (payload["NaviRoadName"] as? String)?.takeIf { it.isNotEmpty() },
                    nextTurnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt(),
                    nextJunctionType = (payload["NaviJunctionType"] as? Number)?.toInt(),
                    nextRoundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt(),
                    // AA roundabout exit angle for the previewed next maneuver (NaviTurnAngle).
                    nextExitAngle = (payload["NaviTurnAngle"] as? Number)?.toInt(),
                )

            logInfo(
                "[NAVI] Burst detected (${gapMs}ms gap) — " +
                    "next-step: maneuver=${merged.nextManeuverType}, road=${merged.nextRoadName}; " +
                    "current preserved: maneuver=${merged.maneuverType}, road=${merged.roadName}",
                tag = Logger.Tags.NAVI,
            )

            _state.value = merged
            // Keep lastDistance current so Tier C's dist-jump detection survives bursts.
            (payload["NaviRemainDistance"] as? Number)?.toInt()?.let { lastDistance = it }
            return
        }

        // ─── Tier C v6.1: authoritative cursor from iAP2 0x5201 field 0x000d ───
        // Apple's per-tick `_iap2` carries RouteGuidanceManeuverCurrentList — the explicit
        // (currentIdx, nextIdx) pair. The patched ARMiPhoneIAP2 v5 binary on the adapter
        // forwards this raw frame; Iap2StateParser parses it; CarlinkManager sets it via
        // setLastIap2State before this call. When the wire cursor + route plan agree we
        // use the cursor directly — no inference, no heuristic.
        //
        // Falls through to Tier C v5 below when:
        //   - lastIap2State is null (no _iap2 this tick, parse failed, post-flush, etc.)
        //   - currentManeuverIdx is null (0x000d absent/empty — both the len-2 [current] and
        //     len-4 [current,next] wire forms now populate currentManeuverIdx, so this is rare)
        //   - route is not loaded yet
        //   - currentManeuverIdx is out of route bounds (likely a reroute in flight)
        //   - ManeuverCount disagrees with route plan size (route/cursor desynced)
        val routeV6 = com.carlink.navigation.compose.ComposedIconStore.currentRoute()
        val iap2 = lastIap2State
        if (routeV6 != null && routeV6.maneuvers.isNotEmpty() &&
            iap2?.currentManeuverIdx != null &&
            iap2.currentManeuverIdx < routeV6.maneuvers.size &&
            (iap2.maneuverCount == null || iap2.maneuverCount == routeV6.maneuvers.size)
        ) {
            if (routeV6 !== lastRouteRef) {
                lastRouteRef = routeV6
                lastDistance = -1
                logInfo(
                    "[NAVI] V6_FIRED: new route — cursor=${iap2.currentManeuverIdx} (${routeV6.maneuvers.size} steps)",
                    tag = Logger.Tags.NAVI,
                )
            }
            localCursor = iap2.currentManeuverIdx
            bootstrapDone = true

            val routeStep = routeV6.maneuvers[localCursor]
            val nextStep = iap2.nextManeuverIdx?.let { idx ->
                if (idx < routeV6.maneuvers.size) routeV6.maneuvers[idx] else null
            } ?: routeV6.maneuvers.getOrNull(localCursor + 1)
            val derivedRoadName = routeStep.instructionText.takeIf { it.isNotEmpty() }
                ?: routeStep.postManeuverRoadName.takeIf { it.isNotEmpty() }
                ?: current.roadName
            val mergedV6 = current.copy(
                status = naviStatus ?: current.status,
                maneuverType = routeStep.cpManeuverType,
                orderType = (payload["NaviOrderType"] as? Number)?.toInt() ?: current.orderType,
                roadName = derivedRoadName,
                remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
                distanceToDestination =
                    (payload["NaviDistanceToDestination"] as? Number)?.toInt()
                        ?: current.distanceToDestination,
                timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
                destinationName =
                    (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() }
                        ?: current.destinationName,
                appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
                turnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt() ?: current.turnAngle,
                turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
                junctionType = (payload["NaviJunctionType"] as? Number)?.toInt() ?: current.junctionType,
                roundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt() ?: current.roundaboutExit,
                // CarPlay roundabout exit angle straight from the route plan's iAP2 0x000b field.
                exitAngle = routeStep.exitAngle,
                nextManeuverType = nextStep?.cpManeuverType,
                nextOrderType = null,
                nextRoadName = nextStep?.instructionText?.takeIf { it.isNotEmpty() } ?: nextStep?.postManeuverRoadName,
                nextTurnAngle = null,
                nextJunctionType = null,
                nextRoundaboutExit = null,
                nextExitAngle = nextStep?.exitAngle,
            )
            logNavi {
                "[NAVI] V6_FIRED cursor=$localCursor/${routeV6.maneuvers.size}, maneuver=${mergedV6.maneuverType}, " +
                    "road=${mergedV6.roadName}, remainDist=${mergedV6.remainDistance}m, " +
                    "next=${mergedV6.nextManeuverType}@${mergedV6.nextRoadName}"
            }
            _state.value = mergedV6
            (payload["NaviRemainDistance"] as? Number)?.toInt()?.let { lastDistance = it }
            return
        }

        // Diagnostic: ManeuverCount disagreement between Apple's wire and our route plan.
        // Does NOT alter control flow — v6.1 already fell through to v5 by the gate above.
        if (iap2?.currentManeuverIdx != null && routeV6 != null &&
            iap2.maneuverCount != null && iap2.maneuverCount != routeV6.maneuvers.size
        ) {
            logInfo(
                "[NAVI] V6_DISCREPANCY: maneuverCount=${iap2.maneuverCount} but route has " +
                    "${routeV6.maneuvers.size} steps — likely stale route plan, v5 fallback active",
                tag = Logger.Tags.NAVI,
            )
        }

        // ─── Tier C v5: timeline-driven cursor (non-burst events only) ───
        // state.maneuverType + state.roadName are ALWAYS derived from route[localCursor].
        // Cursor self-advances on physical crossing signal: NaviRemainDistance jumps UP
        // when user crosses a maneuver point (lastDistance was near zero, now significant).
        // Apple's per-step messages used for verification ONLY: cold-start bootstrap
        // (lastDistance=-1) snaps cursor to Apple's idx; backward correction snaps only when
        // delta ≥ 3 (avoids duplicate-(cp,road) spurious snap-back). Apple's forward previews
        // (idx > cursor) DO NOT advance cursor — they show up naturally as state.next.
        // Falls through to existing normal-transition body when no route plan is available
        // (AA / pre-iap2m) OR when incoming doesn't match anywhere in route (hard reroute).
        val route = com.carlink.navigation.compose.ComposedIconStore.currentRoute()
        val incomingDistTierC = (payload["NaviRemainDistance"] as? Number)?.toInt()
        if (route != null && route.maneuvers.isNotEmpty()) {
            // Route change → reset cursor + distance baseline + bootstrap flag
            if (route !== lastRouteRef) {
                localCursor = 0
                lastDistance = -1
                lastRouteRef = route
                bootstrapDone = false
                logInfo("[NAVI] Tier C: new route — cursor=0 (${route.maneuvers.size} steps)", tag = Logger.Tags.NAVI)
            }

            // ─── 1. Self-advance cursor on physical crossing signal ───
            // NaviRemainDistance JUMPS UP when user crosses a maneuver point — empirically
            // verified via captured Alaska run5 #121-122: distance went 10m → 1093m in one
            // tick when user crossed the Merge maneuver, BEFORE Apple sent the next
            // maneuver-bearing message. Detection: lastDistance was small (≤ 50m, "near a
            // maneuver point") AND incomingDist > lastDistance + 5 (any meaningful increase).
            // The +5m delta resists GPS jitter near 0 while still firing for short consecutive
            // sub-steps (e.g., Park Way step 5→6 has distM=9 — jumps 0 → 9 fires correctly).
            // Mid-segment GPS noise resisted because lastDistance must be ≤ 50m (highway
            // approach distances like 800m → 850m don't qualify).
            if (incomingDistTierC != null && lastDistance in 0..50 && incomingDistTierC > lastDistance + 5) {
                if (localCursor + 1 < route.maneuvers.size) {
                    val prev = localCursor
                    localCursor++
                    bootstrapDone = true
                    logInfo(
                        "[NAVI] Tier C dist-advance: cursor $prev → $localCursor (dist $lastDistance → $incomingDistTierC)",
                        tag = Logger.Tags.NAVI,
                    )
                }
            }

            // ─── 2. Apple message verification (no forward advance; backward/cold-start only) ───
            val incomingRoad = (payload["NaviRoadName"] as? String)?.takeIf { it.isNotEmpty() }
            var hardReroute = false
            if (isManeuverMessage && resolvedManeuverType != null && incomingRoad != null) {
                val idx = route.findStepIndexLoose(resolvedManeuverType, incomingRoad, searchStartIndex = 0)
                if (idx == null) {
                    // No match anywhere — hard reroute. Fall through to existing logic
                    // until fresh _iap2m arrives.
                    hardReroute = true
                    lastRouteRef = null
                    logInfo("[NAVI] Tier C hard reroute: no match for $resolvedManeuverType@\"$incomingRoad\" — falling through", tag = Logger.Tags.NAVI)
                } else if (!bootstrapDone && idx > localCursor) {
                    // Cold-start bootstrap: FIRST maneuver-bearing message after route load.
                    // Trust Apple's idx (one-shot — flag flips after first use). Safe because
                    // we haven't yet driven anywhere on the new route so Apple's idea of
                    // "current step" IS our starting position.
                    logInfo("[NAVI] Tier C cold-start bootstrap: cursor $localCursor → $idx via $resolvedManeuverType@\"$incomingRoad\"", tag = Logger.Tags.NAVI)
                    localCursor = idx
                    bootstrapDone = true
                } else if (idx < localCursor && (localCursor - idx) >= 3) {
                    // Backward correction: snap only if delta ≥ 3 (avoids duplicate (cp, road)
                    // matches in same route from triggering spurious snaps).
                    logInfo("[NAVI] Tier C backward correction: cursor $localCursor → $idx via $resolvedManeuverType@\"$incomingRoad\"", tag = Logger.Tags.NAVI)
                    localCursor = idx
                }
                // idx >= localCursor: confirmation (=) or preview (>). Don't advance cursor.
                // Preview info will surface naturally via state.next-* from route[cursor+1].
            }

            if (!hardReroute) {
                val routeStep = route.maneuvers[localCursor.coerceAtMost(route.maneuvers.size - 1)]
                val nextStep = route.maneuvers.getOrNull(localCursor + 1)
                // Prefer instructionText (the title — what Apple's iPhone displays as the BIG label,
                // e.g. "Merge onto 2 Richardson Hwy" or "Badger Rd to Fairbanks"). Fall back to
                // postManeuverRoadName when title is empty (rare).
                val derivedRoadName = routeStep.instructionText.takeIf { it.isNotEmpty() }
                    ?: routeStep.postManeuverRoadName.takeIf { it.isNotEmpty() }
                    ?: current.roadName
                val mergedTierC = current.copy(
                    status = naviStatus ?: current.status,
                    maneuverType = routeStep.cpManeuverType,
                    orderType = (payload["NaviOrderType"] as? Number)?.toInt() ?: current.orderType,
                    roadName = derivedRoadName,
                    remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
                    distanceToDestination =
                        (payload["NaviDistanceToDestination"] as? Number)?.toInt()
                            ?: current.distanceToDestination,
                    timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
                    destinationName =
                        (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() }
                            ?: current.destinationName,
                    appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
                    turnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt() ?: current.turnAngle,
                    turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
                    junctionType = (payload["NaviJunctionType"] as? Number)?.toInt() ?: current.junctionType,
                    roundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt() ?: current.roundaboutExit,
                    // CarPlay roundabout exit angle straight from the route plan's iAP2 0x000b field.
                    exitAngle = routeStep.exitAngle,
                    nextManeuverType = nextStep?.cpManeuverType,
                    nextOrderType = null,
                    nextRoadName = nextStep?.instructionText?.takeIf { it.isNotEmpty() } ?: nextStep?.postManeuverRoadName,
                    nextTurnAngle = null,
                    nextJunctionType = null,
                    nextRoundaboutExit = null,
                    nextExitAngle = nextStep?.exitAngle,
                )
                logNavi {
                    "[NAVI] Tier C state: cursor=$localCursor, maneuver=${mergedTierC.maneuverType}, road=${mergedTierC.roadName}, " +
                        "remainDist=${mergedTierC.remainDistance}m, next=${mergedTierC.nextManeuverType}@${mergedTierC.nextRoadName}"
                }
                _state.value = mergedTierC
                if (incomingDistTierC != null) lastDistance = incomingDistTierC
                return
            }
        }

        // Normal transition: update current maneuver fields, clear next-step
        val merged =
            current.copy(
                status = naviStatus ?: current.status,
                maneuverType = resolvedManeuverType ?: current.maneuverType,
                orderType = (payload["NaviOrderType"] as? Number)?.toInt() ?: current.orderType,
                roadName = (payload["NaviRoadName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.roadName,
                remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
                distanceToDestination =
                    (payload["NaviDistanceToDestination"] as? Number)?.toInt()
                        ?: current.distanceToDestination,
                timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
                destinationName =
                    (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() }
                        ?: current.destinationName,
                appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
                turnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt() ?: current.turnAngle,
                turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
                junctionType = (payload["NaviJunctionType"] as? Number)?.toInt() ?: current.junctionType,
                roundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt() ?: current.roundaboutExit,
                // AA / non-route path: roundabout exit angle is NaviTurnAngle when present (same
                // driver-relative convention). Nullable so absent ≠ a genuine 0° (straight) exit.
                exitAngle = (payload["NaviTurnAngle"] as? Number)?.toInt() ?: current.exitAngle,
                // Clear next-step on normal maneuver transition — previous preview is stale.
                // Distance-only updates (no NaviManeuverType) preserve existing next-step.
                nextManeuverType = if (isManeuverMessage) null else current.nextManeuverType,
                nextOrderType = if (isManeuverMessage) null else current.nextOrderType,
                nextRoadName = if (isManeuverMessage) null else current.nextRoadName,
                nextTurnAngle = if (isManeuverMessage) null else current.nextTurnAngle,
                nextJunctionType = if (isManeuverMessage) null else current.nextJunctionType,
                nextRoundaboutExit = if (isManeuverMessage) null else current.nextRoundaboutExit,
                nextExitAngle = if (isManeuverMessage) null else current.nextExitAngle,
            )

        if (isManeuverMessage && current.hasNextStep) {
            logInfo(
                "[NAVI] Next-step cleared (normal transition, ${gapMs}ms gap) — " +
                    "was: maneuver=${current.nextManeuverType}, road=${current.nextRoadName}",
                tag = Logger.Tags.NAVI,
            )
        }

        logNavi {
            "[NAVI] State merged: status=${merged.status}, maneuver=${merged.maneuverType}, " +
                "road=${merged.roadName}, remainDist=${merged.remainDistance}m, " +
                "destDist=${merged.distanceToDestination}m, eta=${merged.timeToDestination}s, " +
                "dest=${merged.destinationName}, app=${merged.appName}, " +
                "turnSide=${merged.turnSide}, turnAngle=${merged.turnAngle}, junction=${merged.junctionType}, " +
                "roundaboutExit=${merged.roundaboutExit}" +
                if (merged.hasNextStep) ", nextManeuver=${merged.nextManeuverType}, nextRoad=${merged.nextRoadName}" else ""
        }

        _state.value = merged
        // Keep lastDistance current even on fallback paths so Tier C dist-jump detection
        // works correctly on the next event after a hard-reroute or AA-mode fall-through.
        (payload["NaviRemainDistance"] as? Number)?.toInt()?.let { lastDistance = it }
    }

    /**
     * Process an incoming AA maneuver icon (MEDIA_DATA sub-type 201).
     *
     * Called from USB read thread. Decodes PNG to Bitmap only when content changes
     * (compared by array contentHashCode to skip redundant decodes for repeated icons).
     *
     * @param imageData Raw PNG bytes from the adapter
     */
    fun onNaviImage(imageData: ByteArray) {
        if (!canUseAaManeuverIcon()) {
            dropCurrentManeuverIcon()
            return
        }

        val hash = imageData.contentHashCode()
        if (hash == currentIconHash && currentManeuverIcon != null) {
            logNavi { "[NAVI_ICON] Same icon (hash=$hash, ${imageData.size}B) — skipped decode" }
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        if (bitmap != null) {
            currentManeuverIcon = bitmap
            currentIconHash = hash
            // Evict maneuver cache so next buildManeuver() picks up the new icon
            ManeuverMapper.clearCache()
            logInfo(
                "[NAVI_ICON] Decoded AA maneuver icon: ${bitmap.width}x${bitmap.height}, " +
                    "${imageData.size}B, hash=$hash",
                tag = Logger.Tags.NAVI,
            )
        } else {
            logWarn(
                "[NAVI_ICON] Failed to decode AA maneuver icon (${imageData.size}B, hash=$hash)",
                tag = Logger.Tags.NAVI,
            )
        }
    }

    /** Clear state on USB disconnect. */
    fun clear() {
        logNavi { "[NAVI] State cleared (USB disconnect or session end)" }
        _state.value = NavigationState()
        lastManeuverMs = 0
        localCursor = 0
        lastRouteRef = null
        lastDistance = -1
        bootstrapDone = false
        lastIap2State = null
        dropCurrentManeuverIcon()
    }
}
