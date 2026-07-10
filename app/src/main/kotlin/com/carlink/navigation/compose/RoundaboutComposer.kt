package com.carlink.navigation.compose

import android.graphics.Path
import com.carlink.navigation.Iap2ManeuverData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dynamic roundabout icon composer — Apple-style two-tone (grey junction + white driver path).
 *
 *   BACKGROUND (grey, secondaryLabelColor):
 *     - Annulus ring (outer + inner concentric circles, even-odd hole)
 *     - Spoke stubs at each non-entry, non-exit angle (rectangles overlaid on the ring)
 *
 *   FOREGROUND (white, labelColor) — driver's path, ONE closed contour:
 *     - Outer perimeter: entry stub right edge → outer ring arc CCW to exit → exit stub
 *       right edge → ACROSS stub end → exit stub left edge
 *     - Inner perimeter: inner ring arc CW from exit back to entry → entry stub left edge
 *     - Closed back to start
 *     PLUS: arrowhead as a separate closed sub-shape positioned at exit stub's outer end
 *     (the arrowhead base touches the stub end, so visually they merge into one shape).
 *
 * Wire-format inputs (from [Iap2ManeuverData]):
 *   - `entryAngles` (field 0x000a, multi-occurrence): compass-degrees of NON-entry roads.
 *   - `exitAngle` (field 0x000b): compass-degrees of the road we're TAKING.
 *
 * Angle convention: compass-degrees → SVG-radians via `(compassDeg - 90°) × π/180`.
 *   compass  0° (N) → SVG -π/2  |  compass +90° (E) → SVG 0
 *   compass 180° (S) → SVG +π/2 |  compass -90° (W) → SVG ±π
 *
 * Travel: right-hand traffic (US/EU mainland) — driver goes CCW around the roundabout.
 * Internal arc sweep direction = CCW visually = NEGATIVE sweep in arc_bezier convention.
 */
internal object RoundaboutComposer {
    private const val CX = GeometryPrimitives.CENTER
    private const val CY = GeometryPrimitives.CENTER
    private const val R_OUT = GeometryPrimitives.ROUNDABOUT_OUTER_R
    private const val R_IN = GeometryPrimitives.ROUNDABOUT_INNER_R
    private const val HALF_W = GeometryPrimitives.ROAD_WIDTH / 2.0
    private const val STUB_EXTENT = 8.0
    private const val ENTRY_COMPASS_DEG = 180.0
    private const val ANGLE_MATCH_TOL_DEG = 8.0

    /**
     * The 8 compass directions of Apple's MKJunction baseline (per RE doc §5 + §9 angle
     * lookup table at 0x1a361adf0 — 8 doubles at 45° increments). Apple's roundabout
     * rendering snaps spoke angles to this 8-arm compass rose, giving a clean stylized
     * layout regardless of the actual real-world junction's spoke positions.
     *
     * N=0, NE=45, E=90, SE=135, S=180, SW=-135, W=-90, NW=-45 (compass degrees).
     */
    private val CARDINALS_COMPASS = doubleArrayOf(
        0.0, 45.0, 90.0, 135.0, 180.0, -135.0, -90.0, -45.0,
    )

    /** Snap a compass-degree angle to the nearest of [CARDINALS_COMPASS]. */
    private fun snapToCardinal(compassDeg: Double): Double {
        var best = CARDINALS_COMPASS[0]
        var bestDelta = 360.0
        for (c in CARDINALS_COMPASS) {
            var diff = abs(compassDeg - c) % 360.0
            if (diff > 180.0) diff = 360.0 - diff
            if (diff < bestDelta) { bestDelta = diff; best = c }
        }
        return best
    }

    /** Half-angle subtended by a road-width chord at the outer ring radius. */
    private val DELTA_OUT_RAD = asin(HALF_W / R_OUT)

    /** Half-angle subtended by a road-width chord at the INNER ring radius (larger than DELTA_OUT_RAD because smaller radius). */
    private val DELTA_IN_RAD = asin(HALF_W / R_IN)

    private fun compassDegToSvgRad(deg: Double): Double = (deg - 90.0) * PI / 180.0

    fun compose(data: Iap2ManeuverData): ComposedIcon {
        // ── Resolve spoke + exit angles, SNAPPED to 4-cardinal baseline ──
        // Apple's card icon convention (per RE doc §5 + user-confirmed Image #5/#9 ref):
        // every roundabout card glyph shows stubs at exact N/E/S/W. We snap each raw iAP2
        // angle to the nearest cardinal, then dedupe collisions (multiple iAP2 angles
        // sometimes snap to the same cardinal — e.g. two near-east spokes).
        val rawSpokes: List<Double> = if (data.entryAngles.isNotEmpty()) {
            data.entryAngles.map { it.toDouble() }
        } else {
            val exitNum = if (data.cpManeuverType in 28..46) data.cpManeuverType - 27 else 2
            val cardinalSlots = listOf(90.0, 0.0, -90.0, 180.0)
            cardinalSlots.filterIndexed { idx, _ -> idx != (((exitNum - 1) % 4 + 4) % 4) }
        }
        val rawExitDeg: Double = data.exitAngle?.toDouble() ?: run {
            val exitNum = if (data.cpManeuverType in 28..46) data.cpManeuverType - 27 else 2
            when (((exitNum - 1) % 4 + 4) % 4) { 0 -> 90.0; 1 -> 0.0; 2 -> -90.0; else -> 180.0 }
        }

        // Snap-to-cardinal + dedupe.
        val exitCompassDeg = snapToCardinal(rawExitDeg)
        val snappedSpokes = rawSpokes
            .map { snapToCardinal(it) }
            .distinct()
            .filter { !angleEquals(it, exitCompassDeg) && !angleEquals(it, ENTRY_COMPASS_DEG) }

        val entryRad = compassDegToSvgRad(ENTRY_COMPASS_DEG)
        val exitRad = compassDegToSvgRad(exitCompassDeg)
        val backgroundSpokeRads = snappedSpokes.map { compassDegToSvgRad(it) }

        // ── BACKGROUND (grey): plain annulus + non-exit-non-entry stubs as overlays ──
        val background = Path().apply { fillType = Path.FillType.EVEN_ODD }
        drawAnnulus(background)
        for (spokeRad in backgroundSpokeRads) {
            drawStubRect(background, spokeRad)
        }

        // ── FOREGROUND (white): ONE closed snake + arrowhead sub-shape ──
        val foreground = Path().apply { fillType = Path.FillType.WINDING }
        drawDriverSnake(foreground, entryRad, exitRad)
        // Arrowhead: separate self-closed sub-shape, positioned so its base coincides with
        // exit stub's outer end. Visually merges with the snake's stub-end.
        val xdx = cos(exitRad); val xdy = sin(exitRad)
        val stubX = CX + (R_OUT + STUB_EXTENT) * xdx
        val stubY = CY + (R_OUT + STUB_EXTENT) * xdy
        GeometryPrimitives.appendArrowhead(foreground, stubX, stubY, xdx, xdy)

        return ComposedIcon(background = background, foreground = foreground)
    }

    private fun angleEquals(a: Double, b: Double): Boolean {
        var diff = abs(a - b) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff <= ANGLE_MATCH_TOL_DEG
    }

    /** Plain annulus: outer circle CW + inner circle CCW (even-odd hole). */
    private fun drawAnnulus(path: Path) {
        val o1 = GeometryPrimitives.arcBezier(CX, CY, R_OUT, 0.0, PI)
        val o2 = GeometryPrimitives.arcBezier(CX, CY, R_OUT, PI, PI)
        GeometryPrimitives.appendArc(path, o1, moveFirst = true)
        GeometryPrimitives.appendArc(path, o2, moveFirst = false)
        path.close()
        val i1 = GeometryPrimitives.arcBezier(CX, CY, R_IN, 0.0, -PI)
        val i2 = GeometryPrimitives.arcBezier(CX, CY, R_IN, -PI, -PI)
        GeometryPrimitives.appendArc(path, i1, moveFirst = true)
        GeometryPrimitives.appendArc(path, i2, moveFirst = false)
        path.close()
    }

    /** Simple road-stub rectangle extending OUTWARD from outer ring along [angleSvgRad]. */
    private fun drawStubRect(path: Path, angleSvgRad: Double) {
        val dx = cos(angleSvgRad); val dy = sin(angleSvgRad)
        val px = -dy; val py = dx
        val rIn = R_OUT
        val rOut = R_OUT + STUB_EXTENT
        path.moveTo((CX + rIn * dx + HALF_W * px).toFloat(), (CY + rIn * dy + HALF_W * py).toFloat())
        path.lineTo((CX + rOut * dx + HALF_W * px).toFloat(), (CY + rOut * dy + HALF_W * py).toFloat())
        path.lineTo((CX + rOut * dx - HALF_W * px).toFloat(), (CY + rOut * dy - HALF_W * py).toFloat())
        path.lineTo((CX + rIn * dx - HALF_W * px).toFloat(), (CY + rIn * dy - HALF_W * py).toFloat())
        path.close()
    }

    /**
     * Draw the driver's full path as ONE C-shaped closed contour matching Apple's exact
     * MKJunction.roundaboutArrowWithSize: bezier sequence (14 steps, reverse-engineered
     * from MapKit ARM64 disassembly 2026-05-26):
     *
     *   1.  moveTo (entry stem RIGHT cap-corner top)
     *   2.  lineTo (entry stem right edge UP to outer-ring attach)
     *   3.  arc CCW along OUTER ring from entry-attach-right to exit-attach-right  (= "outer edge of road")
     *   4.  lineTo (radial OUTWARD from outer-ring attach to exit stub outer-right corner)
     *   5.  lineTo (across stub end to exit stub outer-left corner)
     *   6.  lineTo (radial INWARD to inner-ring at exit angle)
     *   7.  cubicTo (smooth blend joining radial line back to inner-ring arc start)
     *   8.  arc CW along INNER ring from exit-attach-left to entry-attach-left  (= "inner edge of road")
     *   9.  cubicTo (smooth blend from inner-ring arc end to entry stem left edge top)
     *   10. lineTo (entry stem left edge DOWN to cap-corner top)
     *   11. quadTo (ROUNDED CAP at entry stem bottom — Apple uses quadratic bezier here)
     *   12. closePath
     *
     * Then arrowhead is appended as a separate sub-shape positioned at the exit stub end.
     *
     * The radial dimensions (entry stem length, cap radius) match Apple's captured data:
     *   - Entry stem extends from R_OUT (south of ring) outward by ~ROAD_WIDTH (= 7 units)
     *     before the rounded cap starts
     *   - Cap is a road-width-diameter semicircle (radius HALF_W) approximated via quad bezier
     */
    private fun drawDriverSnake(path: Path, entryRad: Double, exitRad: Double) {
        // Entry stem extends from ring outer-south by ROAD_WIDTH (matches Apple data:
        // ring south at y=44.29, stem cap-top at y=51, cap bottom at y=54.5 ≈ ring+ROAD_WIDTH).
        val entryStemLen = GeometryPrimitives.ROAD_WIDTH

        // Tangent direction at exit
        val xdx = cos(exitRad); val xdy = sin(exitRad)
        val xpx = -xdy; val xpy = xdx

        // Ring-attach points (where stubs meet outer ring on either side). The half-angle
        // depends on which ring radius — OUTER uses DELTA_OUT_RAD, INNER uses DELTA_IN_RAD
        // (= asin(HALF_W/R_IN), larger because R_IN < R_OUT). Mixing the two would mis-place
        // inner-arc endpoints by ~0.17 rad (~10°), creating a visible misalignment between
        // the inner-arc end and the entry stem edge.
        // Naming: "Right" / "Left" reflects driver-facing direction (right of north-going
        // driver = +x = east-of-south on the ring). cos(π/2 - δ) = sin(δ) > 0 → +x side.
        val entryAttachRightRad = entryRad - DELTA_OUT_RAD // east-of-south on OUTER ring (driver's right entering north)
        val entryAttachLeftRad = entryRad + DELTA_OUT_RAD  // west-of-south on OUTER ring (driver's left)
        val exitAttachRightRad = exitRad - DELTA_OUT_RAD
        val exitAttachLeftRad = exitRad + DELTA_OUT_RAD
        // Same for inner ring, but using larger DELTA_IN_RAD (chord wider at smaller radius)
        val entryInnerLeftRad = entryRad + DELTA_IN_RAD
        val exitInnerLeftRad = exitRad + DELTA_IN_RAD

        val entryAttachRightX = CX + R_OUT * cos(entryAttachRightRad)
        val entryAttachRightY = CY + R_OUT * sin(entryAttachRightRad)
        val exitAttachRightX = CX + R_OUT * cos(exitAttachRightRad)
        val exitAttachRightY = CY + R_OUT * sin(exitAttachRightRad)

        // Exit stub end corners
        val stubX = CX + (R_OUT + STUB_EXTENT) * xdx
        val stubY = CY + (R_OUT + STUB_EXTENT) * xdy
        val stubEndRightX = stubX - HALF_W * xpx
        val stubEndRightY = stubY - HALF_W * xpy
        val stubEndLeftX = stubX + HALF_W * xpx
        val stubEndLeftY = stubY + HALF_W * xpy

        // Entry stem cap region. Stem extends from ring-outer-south (y=CY+R_OUT) downward
        // by entryStemLen, with a quadratic-bezier rounded cap at the bottom.
        val entryCapTopY = CY + R_OUT + entryStemLen - HALF_W // straight-side bottom (cap-top)
        val entryCapBottomY = CY + R_OUT + entryStemLen       // cap's bottom-most point
        val entryStemRightX = CX + HALF_W
        val entryStemLeftX = CX - HALF_W

        // ── 1. moveTo entry stem RIGHT cap-corner top ──
        path.moveTo(entryStemRightX.toFloat(), entryCapTopY.toFloat())

        // ── 2. lineTo UP entry stem right edge to ring outer-attach ──
        path.lineTo(entryAttachRightX.toFloat(), entryAttachRightY.toFloat())

        // ── 3. ARC CCW along OUTER ring (entry-attach-right → exit-attach-right) ──
        var outerSweep = exitAttachRightRad - entryAttachRightRad
        while (outerSweep > 0) outerSweep -= 2 * PI
        if (outerSweep < -2 * PI) outerSweep += 2 * PI
        val outerSegs = GeometryPrimitives.arcBezier(CX, CY, R_OUT, entryAttachRightRad, outerSweep)
        for (seg in outerSegs) {
            path.cubicTo(
                seg.p1x.toFloat(), seg.p1y.toFloat(),
                seg.p2x.toFloat(), seg.p2y.toFloat(),
                seg.p3x.toFloat(), seg.p3y.toFloat(),
            )
        }

        // ── 4. lineTo radial OUTWARD to exit stub outer-right corner ──
        path.lineTo(stubEndRightX.toFloat(), stubEndRightY.toFloat())
        // ── 5. lineTo ACROSS stub end to outer-left corner ──
        path.lineTo(stubEndLeftX.toFloat(), stubEndLeftY.toFloat())
        // ── 6. lineTo to inner-ring at EXIT-ATTACH-LEFT (not exit center!)
        // BUG FIX 2026-05-26: this used to lineTo (R_IN*cos(exitRad), R_IN*sin(exitRad))
        // = inner ring at exit CENTER. That created a V-notch on the inner side of the exit
        // road because the inner-ring traversal then started at exit-LEFT, leaving a wedge.
        // Correct target is the inner-ring point symmetric to the outer-attach-left on the
        // exit side — same compass angle, R_IN radius, with DELTA_IN_RAD offset.
        val exitInnerLeftX = CX + R_IN * cos(exitInnerLeftRad)
        val exitInnerLeftY = CY + R_IN * sin(exitInnerLeftRad)
        path.lineTo(exitInnerLeftX.toFloat(), exitInnerLeftY.toFloat())

        // ── 7. ARC CW along INNER ring (exit-attach-left → entry-attach-left) ──
        // (Previous "Step 7 cubic blend" removed — was a 1/3-2/3 degenerate line that
        // happened to be straight anyway, AND it duplicated the wrong target from step 6.
        // Now we go straight from the corrected step-6 endpoint into the arc.)
        var innerSweep = entryInnerLeftRad - exitInnerLeftRad
        while (innerSweep < 0) innerSweep += 2 * PI
        if (innerSweep > 2 * PI) innerSweep -= 2 * PI
        val innerSegs = GeometryPrimitives.arcBezier(CX, CY, R_IN, exitInnerLeftRad, innerSweep)
        for (seg in innerSegs) {
            path.cubicTo(
                seg.p1x.toFloat(), seg.p1y.toFloat(),
                seg.p2x.toFloat(), seg.p2y.toFloat(),
                seg.p3x.toFloat(), seg.p3y.toFloat(),
            )
        }

        // ── 8. lineTo entry stem left edge top (transition from inner-ring end to stem) ──
        // Apple's captured data shows this transition as a degenerate cubic (control points
        // coincident with endpoints = straight line). Use plain lineTo here for clarity —
        // visually identical to a degenerate cubic.
        val stemLeftTopY = CY + R_OUT
        path.lineTo(entryStemLeftX.toFloat(), stemLeftTopY.toFloat())

        // ── 9. lineTo DOWN entry stem left edge to cap-corner top ──
        path.lineTo(entryStemLeftX.toFloat(), entryCapTopY.toFloat())

        // ── 10. ROUNDED CAP semicircle at entry stem bottom.
        // BUG FIX 2026-05-26: previous single quadTo with control at (CX, capBottom) only
        // bulged HALF_W/2 (= 1.75) from chord, not the intended HALF_W (= 3.5) for a true
        // semicircle. Replaced with two cubic-bezier quarter-arcs via arcBezier (Apple-kappa
        // approximation of a semicircle). Arc center at (CX, capTopY), radius HALF_W, sweep
        // from π (west, at chord-left) by -π (CCW visually in y-down = bulges DOWN through south).
        val capSegs = GeometryPrimitives.arcBezier(CX, entryCapTopY, HALF_W, PI, -PI)
        for (seg in capSegs) {
            path.cubicTo(
                seg.p1x.toFloat(), seg.p1y.toFloat(),
                seg.p2x.toFloat(), seg.p2y.toFloat(),
                seg.p3x.toFloat(), seg.p3y.toFloat(),
            )
        }

        // ── 11. closePath (line from cap end at (entryStemRightX, entryCapTopY) back to
        // moveTo origin which is also (entryStemRightX, entryCapTopY) — zero-length) ──
        path.close()
    }
}
