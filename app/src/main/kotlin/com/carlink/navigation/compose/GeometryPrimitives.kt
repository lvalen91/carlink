package com.carlink.navigation.compose

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Kotlin port of /Users/zeno/Documents/Apple_Maps_SVG/compute_angle_turns.py.
 *
 * Apple's bezier-curve primitives reverse-engineered from MapKit's _MKArrowAppend* family
 * of CGPath-building functions. The defining characteristic of "Apple-shaped" arcs is
 * [KAPPA_90] = 2/3 (Apple's choice for 90° quarter-circles), which is ~21% larger than
 * the geometrically-correct kappa of (4/3)·tan(π/8) ≈ 0.5523. This gives Apple's arcs
 * the slightly "fuller" rounded appearance vs perfectly circular beziers.
 *
 * Used by both the static-template composer (StaticManeuverPaths) and the dynamic
 * roundabout composer (RoundaboutComposer). Pure / stateless / no Android dependencies
 * apart from android.graphics.Path as the output type.
 *
 * All angles in this file are in radians. Coordinate system is SVG-conventional: x right,
 * y DOWN, angles measured CCW from positive-x axis. Conversion to/from degrees + handling
 * of the iAP2 "compass-style" angle convention (where 0° is north, increases CW) is the
 * caller's responsibility — see RoundaboutComposer.
 */
internal object GeometryPrimitives {
    /** Apple's kappa constant for a 90° arc. Larger than the geometric ideal ≈ 0.5523. */
    const val KAPPA_90 = 2.0 / 3.0

    /** Geometric kappa for a 90° arc: (4/3)·tan(π/8) ≈ 0.5523. */
    private val STD_KAPPA_90 = (4.0 / 3.0) * tan(PI / 8.0)

    /** Scale factor that converts standard kappa to Apple-style for any arc angle. ~1.2071. */
    private val KAPPA_SCALE = KAPPA_90 / STD_KAPPA_90

    /** Standard road width Apple uses for turn-arrow stems and roundabout spoke stubs (in 56-pt viewport units). */
    const val ROAD_WIDTH = 7.0

    /** Apple's roundabout outer-circle radius in 56-pt viewport units. Matches MKJunction roundaboutArrowWithSize:metrics:outerRadius:... default. */
    const val ROUNDABOUT_OUTER_R = 16.2909

    /** Apple's roundabout inner-circle radius: outer minus the road width. */
    const val ROUNDABOUT_INNER_R = ROUNDABOUT_OUTER_R - ROAD_WIDTH

    /** Center of the 56-pt-square viewport — anchor for roundabouts and arrowhead-template transforms. */
    const val CENTER = 28.0

    /**
     * Apple-style kappa for an arc of [angleRad] radians.
     *
     * The standard formula `(4/3)·tan(θ/4)` gives the geometrically-correct kappa for a
     * single cubic bezier approximating a circular arc of any angle ≤ π/2; Apple scales
     * the result by [KAPPA_SCALE] to match their characteristic "fuller" arc shape.
     */
    fun appleKappa(angleRad: Double): Double {
        val standard = (4.0 / 3.0) * tan(angleRad / 4.0)
        return KAPPA_SCALE * standard
    }

    /**
     * One segment of a cubic bezier arc: P0 (start) → P1, P2 (controls) → P3 (end).
     * Points are (x, y) pairs in viewport coords.
     */
    data class BezierSegment(
        val p0x: Double, val p0y: Double,
        val p1x: Double, val p1y: Double,
        val p2x: Double, val p2y: Double,
        val p3x: Double, val p3y: Double,
    )

    /**
     * Decompose a circular arc into one or more cubic-bezier segments.
     *
     * The arc is centered at ([cx], [cy]) with radius [r], starts at angle [startAngle]
     * (measured from positive-x axis in standard math convention — though our caller is in
     * SVG y-down coords, the formulae are identical: y-down just flips CCW vs CW visually).
     * Sweeps [sweepAngle] radians (signed: positive = CCW in math convention = CW visually
     * in SVG y-down).
     *
     * Arcs longer than 90° (π/2) are split into segments of at most π/2 each — required to
     * keep the bezier approximation error below ~1% of radius. Each returned segment is
     * suitable to feed straight into [Path.cubicTo] after a [Path.moveTo] to its [p0x]/[p0y].
     */
    fun arcBezier(
        cx: Double,
        cy: Double,
        r: Double,
        startAngle: Double,
        sweepAngle: Double,
    ): List<BezierSegment> {
        val segments = mutableListOf<BezierSegment>()
        var remaining = kotlin.math.abs(sweepAngle)
        val sign = if (sweepAngle > 0) 1.0 else -1.0
        var a = startAngle

        while (remaining > 1e-10) {
            val segAngle = min(remaining, PI / 2.0)
            val k = appleKappa(segAngle)
            val aEnd = a + sign * segAngle

            val p0x = cx + r * cos(a)
            val p0y = cy + r * sin(a)
            val p3x = cx + r * cos(aEnd)
            val p3y = cy + r * sin(aEnd)

            // Tangent unit vector at start: perpendicular to radius, oriented in sweep direction.
            val tu0x = -sign * sin(a)
            val tu0y = sign * cos(a)
            val tu3x = -sign * sin(aEnd)
            val tu3y = sign * cos(aEnd)

            // Control points sit `k·r` along the tangent — the textbook approximation.
            val p1x = p0x + k * r * tu0x
            val p1y = p0y + k * r * tu0y
            val p2x = p3x - k * r * tu3x
            val p2y = p3y - k * r * tu3y

            segments.add(BezierSegment(p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y))

            a = aEnd
            remaining -= segAngle
        }
        return segments
    }

    /**
     * Append [segments] (from [arcBezier]) to [path], starting with a moveTo if [moveFirst]
     * is true, then chaining cubicTo for each segment.
     */
    fun appendArc(
        path: Path,
        segments: List<BezierSegment>,
        moveFirst: Boolean,
    ) {
        if (segments.isEmpty()) return
        if (moveFirst) {
            path.moveTo(segments[0].p0x.toFloat(), segments[0].p0y.toFloat())
        }
        for (seg in segments) {
            path.cubicTo(
                seg.p1x.toFloat(), seg.p1y.toFloat(),
                seg.p2x.toFloat(), seg.p2y.toFloat(),
                seg.p3x.toFloat(), seg.p3y.toFloat(),
            )
        }
    }

    /**
     * Transform a point expressed in local "along-travel / right-of-travel" coordinates to
     * SVG viewport coordinates, given the arrowhead origin (ox, oy) and travel direction
     * unit vector (dx, dy).
     *
     * Right-of-travel perpendicular in SVG y-down conventions is `(-dy, dx)`. (Rotating the
     * travel vector +90° in math convention yields the LEFT side; in y-down it yields the
     * RIGHT side because y is flipped.)
     */
    fun transformPoint(
        lu: Double,
        lv: Double,
        ox: Double,
        oy: Double,
        dx: Double,
        dy: Double,
    ): Pair<Double, Double> {
        val px = -dy
        val py = dx
        return (ox + lu * dx + lv * px) to (oy + lu * dy + lv * py)
    }

    /**
     * The arrowhead template in local coordinates. Each entry is one path command — single
     * point for `M`/`L`, three points for `C`, none for `Z` (close).
     *
     * Extracted from Apple's 90° right turn arrow path: arrowhead base center at (0, 0)
     * in local coords, u-axis along travel direction, v-axis right of travel.
     * Direct copy of compute_angle_turns.py ARROWHEAD_LOCAL.
     */
    enum class ArrowCmd { MOVE, LINE, CURVE, CLOSE }

    data class ArrowCommand(
        val cmd: ArrowCmd,
        // For MOVE/LINE: one point in (lu, lv). For CURVE: 3 points (control1, control2, endpoint).
        val points: List<Pair<Double, Double>>,
    )

    val ARROWHEAD_TEMPLATE: List<ArrowCommand> = listOf(
        ArrowCommand(ArrowCmd.MOVE, listOf(17.7751 to -1.6267)),
        ArrowCommand(ArrowCmd.LINE, listOf(3.3885 to -14.5146)),
        ArrowCommand(ArrowCmd.LINE, listOf(3.3885 to -14.5146)),
        ArrowCommand(
            ArrowCmd.CURVE,
            listOf(
                2.5187 to -15.2482, 1.2190 to -15.1378, 0.4854 to -14.2681,
            ),
        ),
        ArrowCommand(
            ArrowCmd.CURVE,
            listOf(
                0.0602 to -13.7640, -0.0980 to -13.0868, 0.0599 to -12.4465,
            ),
        ),
        ArrowCommand(ArrowCmd.LINE, listOf(5.2252 to -3.5000)),
        ArrowCommand(ArrowCmd.LINE, listOf(0.0 to -3.5000)),
        ArrowCommand(ArrowCmd.LINE, listOf(0.0 to 3.5000)),
        ArrowCommand(ArrowCmd.LINE, listOf(5.2252 to 3.5000)),
        ArrowCommand(ArrowCmd.LINE, listOf(0.0599 to 12.4465)),
        ArrowCommand(ArrowCmd.LINE, listOf(0.0599 to 12.4465)),
        ArrowCommand(
            ArrowCmd.CURVE,
            listOf(
                -0.2125 to 13.5512, 0.4622 to 14.6676, 1.5669 to 14.9401,
            ),
        ),
        ArrowCommand(
            ArrowCmd.CURVE,
            listOf(
                2.2072 to 15.0980, 2.8844 to 14.9398, 3.3885 to 14.5146,
            ),
        ),
        ArrowCommand(ArrowCmd.LINE, listOf(17.7751 to 1.6267)),
        ArrowCommand(ArrowCmd.LINE, listOf(17.7751 to 1.6267)),
        ArrowCommand(
            ArrowCmd.CURVE,
            listOf(
                18.6735 to 1.0207, 18.9105 to -0.1988, 18.3046 to -1.0972,
            ),
        ),
        ArrowCommand(
            ArrowCmd.CURVE,
            listOf(
                18.1637 to -1.3060, 17.9839 to -1.4858, 17.7751 to -1.6267,
            ),
        ),
        ArrowCommand(ArrowCmd.CLOSE, emptyList()),
    )

    /**
     * Append Apple's arrowhead template to [path], positioned at ([ox], [oy]) and rotated
     * to point in direction ([dx], [dy]) (which must be a unit vector).
     *
     * The arrowhead base sits at ([ox], [oy]); the tip extends 17.7751 viewport units along
     * the (dx, dy) direction. The arrowhead is 7 units wide perpendicular to travel
     * (matches [ROAD_WIDTH]).
     */
    fun appendArrowhead(
        path: Path,
        ox: Double,
        oy: Double,
        dx: Double,
        dy: Double,
    ) {
        for (cmd in ARROWHEAD_TEMPLATE) {
            when (cmd.cmd) {
                ArrowCmd.MOVE -> {
                    val (sx, sy) = transformPoint(cmd.points[0].first, cmd.points[0].second, ox, oy, dx, dy)
                    path.moveTo(sx.toFloat(), sy.toFloat())
                }
                ArrowCmd.LINE -> {
                    val (sx, sy) = transformPoint(cmd.points[0].first, cmd.points[0].second, ox, oy, dx, dy)
                    path.lineTo(sx.toFloat(), sy.toFloat())
                }
                ArrowCmd.CURVE -> {
                    val (c1x, c1y) = transformPoint(cmd.points[0].first, cmd.points[0].second, ox, oy, dx, dy)
                    val (c2x, c2y) = transformPoint(cmd.points[1].first, cmd.points[1].second, ox, oy, dx, dy)
                    val (ex, ey) = transformPoint(cmd.points[2].first, cmd.points[2].second, ox, oy, dx, dy)
                    path.cubicTo(c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(), ex.toFloat(), ey.toFloat())
                }
                ArrowCmd.CLOSE -> path.close()
            }
        }
    }
}
