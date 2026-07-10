package com.carlink.navigation

/**
 * Parsed representation of a single iAP2 0x5202 maneuver message.
 *
 * Wire-format reference (live-decoded 2026-05-26 from patched ARMiPhoneIAP2 v5,
 * filed to mempalace drawer_carlink_re_documention_00e5b4564ce1112402c1191c):
 *
 * Frame header per maneuver: `4040 <len_be:2> 5202 0006 0000002a 0006 0001 <index_be:2>`
 * KVP body: repeated `<field_id_be:2> <value_len_be:2> <value_bytes>`
 *
 * Field IDs observed across 19 maneuvers on a Carmel, IN route:
 *   0x0002 — instructionText (UTF-8 NUL-terminated, e.g. "At the roundabout, take the second exit")
 *   0x0003 — cpManeuverType byte (matches CPManeuverType enum 0-53)
 *   0x0004 — postManeuverRoadName (UTF-8)
 *   0x0005 — distanceMeters (uint32 BE)
 *   0x0006 — distanceDisplayText (UTF-8, e.g. "0.2", "500", "1.5")
 *   0x0007 — distanceUnitFlag (1 byte: 0x04 ~ ft, 0x01 ~ mi/km, observed values)
 *   0x0008 — flagByte (1 byte, unknown semantics)
 *   0x0009 — flagByte (1 byte: 0x00 or 0x01, observed)
 *   0x000a — junctionElementAngle (int16 BE, signed degrees). MULTI-OCCURRENCE on
 *            multi-spoke junctions — one entry per immediate non-traversed arm Apple's
 *            MKJunction chose to emit. EMPIRICAL CAVEAT: the wire format does NOT
 *            guarantee every physical arm of the junction is listed. Apple sometimes
 *            omits arms beyond the immediate spokes when geometry is unambiguous from
 *            `junctionExitAngle` + entry position. Verified Carmel IN 2026-05-28:
 *            cp=30 step 4 (3rd-exit roundabout at City Center Dr) carried only 2
 *            entryAngles though the junction is a 4-arm intersection.
 *            Consumers computing arm count must use
 *            `max(entryAngles.size + 1, exitOrdinal + 1)` not raw `entryAngles.size + 1`.
 *   0x000b — junctionExitAngle (int16 BE, signed degrees). The chosen-exit arm's angle.
 *
 * Not all maneuvers carry every field. Roundabouts and complex multi-spoke intersections
 * carry 0x000a (often multiple times) + 0x000b; simple turns may carry only 0x000a once or
 * not at all.
 *
 * The List<Int> on `entryAngles` preserves wire-order — Apple's MKJunction documents
 * `_snapped[8]` slots which suggest angles are pre-sorted by the firmware. Empirically
 * confirmed on the Carmel capture: angles within a multi-spoke maneuver appeared in
 * monotonically increasing order when present.
 */
data class Iap2ManeuverData(
    /** 0-based index into the route's maneuver list (matches the wire-format index field). */
    val index: Int,
    /** Field 0x0003 — CPManeuverType integer (0-53). */
    val cpManeuverType: Int,
    /** Field 0x0002 — full instruction text (the announcement string Apple Maps speaks). */
    val instructionText: String,
    /** Field 0x0004 — name of the road you'll be ON after this maneuver completes. */
    val postManeuverRoadName: String,
    /** Field 0x0005 — distance to this maneuver in meters. */
    val distanceMeters: Int,
    /** Field 0x0006 — pre-formatted distance string Apple Maps would display (e.g. "0.2"). */
    val distanceDisplayText: String,
    /** Field 0x0007 — distance unit indicator (0x04 ~ feet, 0x01 ~ mi/km in observations). */
    val distanceUnitFlag: Int,
    /** Field 0x0008 — semantic-unknown flag byte. */
    val flag8: Int,
    /** Field 0x0009 — semantic-unknown flag byte. */
    val flag9: Int,
    /** Field 0x000a (multi-occurrence) — junction spoke angles in degrees. May be empty for simple turns. */
    val entryAngles: List<Int>,
    /** Field 0x000b — chosen exit angle in degrees. Null if not present (simple turns, ramps, etc). */
    val exitAngle: Int?,
) {
    /**
     * True when this maneuver has structured junction geometry (multi-spoke angle list).
     * The composer uses dynamic bezier path-building for these; simple maneuvers use static templates.
     */
    val hasJunctionGeometry: Boolean
        get() = entryAngles.isNotEmpty() && exitAngle != null

    /**
     * True when this maneuver is in the roundabout CPManeuverType range (28-46) OR
     * (defensively) carries junction geometry that looks roundabout-shaped (many spokes + an exit angle).
     * Roundabouts always need dynamic composition for fidelity.
     */
    val isRoundabout: Boolean
        get() = cpManeuverType in 28..46 || cpManeuverType in 6..7 || cpManeuverType == 19
}

/**
 * The fully-parsed route burst from a single 0x5202 ManeuverList message-chain.
 *
 * Produced once per route-calculation event (initial route, reroute, new destination). The
 * adapter delivers this as a concatenation of one [Iap2ManeuverData] per maneuver in the
 * _iap2m field of a single NaviJSON message.
 */
data class Iap2RouteData(
    val maneuvers: List<Iap2ManeuverData>,
) {
    /**
     * Look up the maneuver matching a per-step NaviJSON event's `(NaviManeuverType, NaviRoadName)`
     * pair. Walks the list from [searchStartIndex] forward to support the cursor-walk pattern —
     * resolving ambiguity when consecutive maneuvers share the same `(type, road)` (e.g. two
     * straight stretches of the same road separated by a maneuver-less continue).
     *
     * Returns null when no match is found — caller falls back to the static-library glyph.
     */
    fun findStepIndex(
        cpManeuverType: Int,
        roadName: String?,
        searchStartIndex: Int = 0,
    ): Int? {
        if (roadName.isNullOrBlank()) {
            // Fall back to type-only match — accept any maneuver with the right cpType.
            for (i in searchStartIndex until maneuvers.size) {
                if (maneuvers[i].cpManeuverType == cpManeuverType) return i
            }
            return null
        }
        for (i in searchStartIndex until maneuvers.size) {
            val m = maneuvers[i]
            if (m.cpManeuverType == cpManeuverType && m.postManeuverRoadName.equals(roadName, ignoreCase = true)) {
                return i
            }
        }
        return null
    }

    /**
     * Like [findStepIndex] but matches on EITHER `postManeuverRoadName` OR `instructionText`.
     * Used by NavigationStateManager's Tier C cursor sync because Apple's per-step
     * `NaviRoadName` empirically can carry the route's title ("Turn right", "Goldhill Rd")
     * not just the post-road. Kept separate from `findStepIndex` so existing icon-cache
     * lookups (`ComposedIconStore.lookup`) aren't affected by the looser match.
     */
    fun findStepIndexLoose(
        cpManeuverType: Int,
        roadName: String?,
        searchStartIndex: Int = 0,
    ): Int? {
        if (roadName.isNullOrBlank()) {
            for (i in searchStartIndex until maneuvers.size) {
                if (maneuvers[i].cpManeuverType == cpManeuverType) return i
            }
            return null
        }
        for (i in searchStartIndex until maneuvers.size) {
            val m = maneuvers[i]
            if (m.cpManeuverType != cpManeuverType) continue
            if (m.postManeuverRoadName.equals(roadName, ignoreCase = true)) return i
            if (m.instructionText.equals(roadName, ignoreCase = true)) return i
        }
        return null
    }
}
