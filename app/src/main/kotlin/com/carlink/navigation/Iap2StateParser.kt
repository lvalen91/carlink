package com.carlink.navigation

/**
 * Parsed per-tick state from iAP2 0x5201 RouteGuidanceUpdate.
 *
 * Wire format: `<sync:0x4040><len_be:2><type:0x5201><KVPs><trailer:1B>`.
 * KVP: `<entry_len_be:2><field_id_be:2><value:entry_len-4>`.
 *
 * Field naming source: iOS 26.5 (build 23F77, iPhone18,4) `AccessoryNavigation.framework`,
 * authoritative via disassembly of `+[ACCNavigationRouteGuidanceUpdateInfo keyForType:]`
 * at vaddr `0x226ac5f74` with jump table at `0x226ac61e8`. The 26 ParameterID-to-name
 * mappings are documented in
 * `documents/reference/adapter/RE_Documention/02_Protocol_Reference/iap2_message_catalog.md`
 * and the R14G17-to-iOS-26 changelog is in `iap2_sdk_delta_r14g17_to_ios26.md`.
 *
 * Adapter context: the stock CPC200-CCPA `ARMiPhoneIAP2` strips most of the 26 fields when
 * emitting NaviJSON. The patched `ARMiPhoneIAP2 v5` binary (deployed `/usr/sbin`, survives
 * reboot) additionally attaches the raw 0x5201 frame as the `_iap2` NaviJSON field. This
 * parser consumes that raw hex on the host side so we can read fields the adapter strips —
 * most importantly the cursor at field 0x000d (`RouteGuidanceManeuverCurrentList`), which
 * Bug A v1–v5 spent five iterations trying to infer from sparse maneuver bursts +
 * distance heuristics.
 *
 * Fields 0x0010–0x001a are iOS 26+ extensions (lane guidance, EV battery, time-zone,
 * stop type, source capability echo) that stay at zero/default in current captures because
 * the CCPA never advertises the corresponding capabilities during iAP2 identification —
 * see `iap2_sdk_delta_r14g17_to_ios26.md` §6 and `carkit_ios26_master.md` §6 for the
 * gating chain and patching recipes. They are parsed defensively here for
 * forward-compatibility with future firmware patches or non-CCPA adapters that do advertise
 * the capabilities.
 */
data class Iap2RouteGuidanceState(
    /** Field 0x01 — `RouteGuidanceState` enum. Observed 0/1/2/6 (likely inactive/active/paused/unknown). */
    val routeGuidanceState: Int? = null,
    /** Field 0x03 — `CurrentRoadName`: road the user is physically on now. Distinct from
     *  per-step `NaviRoadName` which carries the upcoming maneuver's road. */
    val currentRoadName: String? = null,
    /** Field 0x05 — `EstimatedTimeOfArrival` as unix seconds (u64 BE). */
    val etaUnixSeconds: Long? = null,
    /** Field 0x06 — `TimeRemainingToDestination` in seconds (u64 BE). */
    val timeRemainingSeconds: Long? = null,
    /** Field 0x07 — `DistanceRemaining`: meters to destination (u32 BE). */
    val distanceRemainingMeters: Int? = null,
    /** Field 0x0a — `DistanceRemainingToNextManeuver`: meters (u32 BE). Mirrors `NaviRemainDistance`. */
    val distanceToNextManeuverMeters: Int? = null,
    /** Field 0x0d head[0] — current maneuver index from `RouteGuidanceManeuverCurrentList`
     *  (the maneuver the driver is approaching now). Present in BOTH the len-4 [current,next]
     *  and len-2 [current] wire forms; null only when 0x0d is absent/empty. Drives Bug A
     *  cursor in v6.1. */
    val currentManeuverIdx: Int? = null,
    /** Field 0x0d head[1] — upcoming maneuver index. Populated only by the len-4 form; null
     *  for the len-2 (current-only) form, where v6.1 derives next from route[current+1]. */
    val nextManeuverIdx: Int? = null,
    /** Field 0x0e — `RouteGuidanceManeuverCount`: total maneuvers in current route. Used for
     *  sanity check against the route plan from 0x5202 / Iap2RouteParser. */
    val maneuverCount: Int? = null,
    /** Field 0x0f — `RouteGuidanceBeingShownInApp` (was `VisibleInApp` in R14G17). u8 bool. */
    val routeGuidanceBeingShownInApp: Boolean? = null,
    /** Field 0x10 — `LaneGuidanceCurrentIndex` (iOS 26+). u16. Active lane-guidance entry index.
     *  Zero in current captures — CCPA does not advertise `MaxLaneGuidanceStorageCapacity`. */
    val laneGuidanceCurrentIndex: Int? = null,
    /** Field 0x11 — `LaneGuidanceTotalCount` (iOS 26+). u16. Total lane-guidance entries in route. */
    val laneGuidanceTotalCount: Int? = null,
    /** Field 0x12 — `LaneGuidanceShowing` (iOS 26+). u8 bool. Whether iPhone is currently
     *  presenting lane guidance. */
    val laneGuidanceShowing: Boolean? = null,
    /** Field 0x14 — `SourceSupportsRouteGuidance` (iOS 26+). u8 bool. Always true since
     *  source = iPhone Maps which inherently supports route guidance. */
    val sourceSupportsRouteGuidance: Boolean? = null,
    /** Field 0x15 — `DestinationTimeZoneOffsetMinutes` (iOS 26+). i16 signed minutes offset
     *  from UTC at destination. Requires adapter to advertise `supportsTimeZoneOffset`. */
    val destinationTimeZoneOffsetMinutes: Int? = null,
    /** Field 0x16 — `StopType` (iOS 26+). u8. Type of upcoming stop (waypoint / charging / etc.).
     *  Requires multi-stop route. */
    val stopType: Int? = null,
    /** Field 0x18 — `ArrivalBatteryLevel` (iOS 26+, EV). u8 percent. Requires adapter to
     *  advertise `supportsCharging` AND a vehicle profile = EV. */
    val arrivalBatteryLevelPercent: Int? = null,
    /** Field 0x19 — `DepartureBatteryLevel` (iOS 26+, EV). u8 percent. */
    val departureBatteryLevelPercent: Int? = null,
    /** Field 0x1a — `FinalWaypointBatteryLevel` (iOS 26+, EV multi-stop). u8 percent. */
    val finalWaypointBatteryLevelPercent: Int? = null,
)

object Iap2StateParser {
    /**
     * Parse an `_iap2` hex string (a single 0x5201 frame).
     *
     * Returns null on: sync/type mismatch, truncation, parse failure, OR when no
     * SDK-named field could be extracted. Callers (CarlinkManager.processMediaMetadata)
     * MUST treat null as "invalidate any cached state" — never preserve a stale prior
     * result across a parse failure, otherwise a corrupt frame would freeze the
     * authoritative cursor indefinitely.
     */
    fun parse(hex: String): Iap2RouteGuidanceState? {
        val b = hexToBytes(hex) ?: return null
        if (b.size < 7) return null
        if (b[0] != 0x40.toByte() || b[1] != 0x40.toByte()) return null
        if (b[4] != 0x52.toByte() || b[5] != 0x01.toByte()) return null

        var routeState: Int? = null
        var currentRoad: String? = null
        var eta: Long? = null
        var timeRemain: Long? = null
        var distRemain: Int? = null
        var distNext: Int? = null
        var curIdx: Int? = null
        var nextIdx: Int? = null
        var maneuverCount: Int? = null
        var beingShownInApp: Boolean? = null
        var lgCurrentIndex: Int? = null
        var lgTotalCount: Int? = null
        var lgShowing: Boolean? = null
        var srcSupportsRG: Boolean? = null
        var destTzOffsetMin: Int? = null
        var stopType: Int? = null
        var arrivalBatteryPct: Int? = null
        var departureBatteryPct: Int? = null
        var finalWpBatteryPct: Int? = null

        var i = 6
        while (i + 4 <= b.size) {
            val entryLen = readU16BE(b, i)
            if (entryLen < 4 || entryLen > 1024 || i + entryLen > b.size) break
            val fieldId = readU16BE(b, i + 2)
            val valStart = i + 4
            val valLen = entryLen - 4

            when (fieldId) {
                0x0001 -> if (valLen == 1) routeState = b[valStart].toInt() and 0xFF
                0x0003 -> currentRoad = readStringNullTerminated(b, valStart, valLen)
                0x0005 -> if (valLen == 8) eta = readU64BE(b, valStart)
                0x0006 -> if (valLen == 8) timeRemain = readU64BE(b, valStart)
                0x0007 -> if (valLen == 4) distRemain = readU32BEasInt(b, valStart)
                0x000a -> if (valLen == 4) distNext = readU32BEasInt(b, valStart)
                0x000d -> {
                    // RouteGuidanceManeuverCurrentList — list of u16 maneuver indices.
                    // Element 0 = CURRENT (the maneuver the driver is approaching now);
                    // element 1 = next. Apple emits BOTH forms during active driving:
                    //   len 4 = [current, next]   (observed next == current+1)
                    //   len 2 = [current]         (next omitted — derive from route[current+1])
                    // The len-2 form is current-ONLY, NOT "next-only depart/arrival". Verified
                    // live 2026-05-29 (3-agent, 3 routes): the len-2 single value tracked
                    // CarPlay's active instruction exactly (e.g. =6 when the phone showed route
                    // idx6 @1.4mi), and 0x000a counts down to ~0 at that same index. Depart shows
                    // as len 0 or len 4 [0,1]; arrival as len 2 [lastIdx]. (A prior revision
                    // mis-assigned the len-2 value to nextIdx, leaving currentManeuverIdx null,
                    // which failed the v6.1 gate and stranded the cursor on the Tier C fallback.)
                    when {
                        valLen == 2 -> {
                            curIdx = readU16BE(b, valStart)
                            nextIdx = null
                        }
                        valLen >= 4 && valLen % 2 == 0 -> {
                            curIdx = readU16BE(b, valStart)
                            nextIdx = readU16BE(b, valStart + 2)
                        }
                        // Unrecognized length — leave both null; v5 fallback handles cursor.
                    }
                }
                0x000e -> if (valLen == 2) maneuverCount = readU16BE(b, valStart)
                0x000f -> if (valLen == 1) beingShownInApp = b[valStart].toInt() != 0
                0x0010 -> if (valLen == 2) lgCurrentIndex = readU16BE(b, valStart)
                0x0011 -> if (valLen == 2) lgTotalCount = readU16BE(b, valStart)
                0x0012 -> if (valLen == 1) lgShowing = b[valStart].toInt() != 0
                0x0014 -> if (valLen == 1) srcSupportsRG = b[valStart].toInt() != 0
                0x0015 -> if (valLen == 2) destTzOffsetMin = readI16BE(b, valStart)
                0x0016 -> if (valLen == 1) stopType = b[valStart].toInt() and 0xFF
                0x0018 -> if (valLen == 1) arrivalBatteryPct = b[valStart].toInt() and 0xFF
                0x0019 -> if (valLen == 1) departureBatteryPct = b[valStart].toInt() and 0xFF
                0x001a -> if (valLen == 1) finalWpBatteryPct = b[valStart].toInt() and 0xFF
                // 0x0013 SourceName, 0x0017 ChargingStationInfoList intentionally not parsed
                // (already mirrored in NaviAPPName / not yet needed; the list is a nested group).
            }
            i += entryLen
        }

        if (routeState == null && currentRoad == null && eta == null &&
            timeRemain == null && distRemain == null && distNext == null &&
            curIdx == null && nextIdx == null && maneuverCount == null &&
            beingShownInApp == null && lgCurrentIndex == null && lgTotalCount == null &&
            lgShowing == null && srcSupportsRG == null && destTzOffsetMin == null &&
            stopType == null && arrivalBatteryPct == null && departureBatteryPct == null &&
            finalWpBatteryPct == null
        ) {
            return null
        }

        return Iap2RouteGuidanceState(
            routeGuidanceState = routeState,
            currentRoadName = currentRoad,
            etaUnixSeconds = eta,
            timeRemainingSeconds = timeRemain,
            distanceRemainingMeters = distRemain,
            distanceToNextManeuverMeters = distNext,
            currentManeuverIdx = curIdx,
            nextManeuverIdx = nextIdx,
            maneuverCount = maneuverCount,
            routeGuidanceBeingShownInApp = beingShownInApp,
            laneGuidanceCurrentIndex = lgCurrentIndex,
            laneGuidanceTotalCount = lgTotalCount,
            laneGuidanceShowing = lgShowing,
            sourceSupportsRouteGuidance = srcSupportsRG,
            destinationTimeZoneOffsetMinutes = destTzOffsetMin,
            stopType = stopType,
            arrivalBatteryLevelPercent = arrivalBatteryPct,
            departureBatteryLevelPercent = departureBatteryPct,
            finalWaypointBatteryLevelPercent = finalWpBatteryPct,
        )
    }

    private fun hexToBytes(hex: String): ByteArray? = try {
        if (hex.length % 2 != 0) {
            null
        } else {
            ByteArray(hex.length / 2) { i ->
                ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
            }
        }
    } catch (e: Throwable) {
        null
    }

    private fun readU16BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readI16BE(b: ByteArray, off: Int): Int {
        val u = readU16BE(b, off)
        return if (u and 0x8000 != 0) u - 0x10000 else u
    }

    private fun readU32BEasInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun readU64BE(b: ByteArray, off: Int): Long {
        var r = 0L
        for (k in 0 until 8) r = (r shl 8) or (b[off + k].toLong() and 0xFF)
        return r
    }

    private fun readStringNullTerminated(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }
}
