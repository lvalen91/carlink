package com.carlink.navigation.compose

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Composes turn-arrow icons (left/right at any sweep angle, U-turn, slight/sharp variants).
 *
 * Direct port of compute_angle_turns.py generate_right_turn + generate_left_turn, with the
 * U-turn and 90° special cases added. All Apple's constants preserved exactly:
 *   - Stem width = 7.0 (ROAD_WIDTH)
 *   - Arc outer radius Ro = 18.0
 *   - Arc inner radius Ri = 11.0
 *   - Arc center: right turn at (49, 38), left turn at (-18, 38) (mirror)
 *   - Stem extends from y=56 (viewport bottom) up to y=38
 *   - Exit stem length after arc = 6.36
 *   - Cap radius = 3.5 (half road width)
 *   - Cap control point offset: 1.9330 / 1.5670 (Apple's exact magic numbers, not
 *     standard kappa — derived from the 90° right turn cap)
 *
 * Covers CPManeuverType values:
 *   1  LEFT_TURN (90°)               — generate(angleDeg = 90, side = LEFT)
 *   2  RIGHT_TURN (90°)              — generate(angleDeg = 90, side = RIGHT)
 *   4  U_TURN                        — generateUTurn()
 *   20 LEFT_TURN_AT_END (90° + T-bg) — generate(90, LEFT) + T-junction background (not yet)
 *   21 RIGHT_TURN_AT_END             — generate(90, RIGHT) + T-junction background
 *   47 SHARP_LEFT_TURN (135°)        — generate(135, LEFT)
 *   48 SHARP_RIGHT_TURN (135°)       — generate(135, RIGHT)
 *   49 SLIGHT_LEFT_TURN (45°)        — generate(45, LEFT)
 *   50 SLIGHT_RIGHT_TURN (45°)       — generate(45, RIGHT)
 */
internal object TurnArrowComposer {
    enum class Side { LEFT, RIGHT }

    private const val W = GeometryPrimitives.ROAD_WIDTH        // 7.0
    private const val RO = 18.0                                // Outer arc radius
    private const val RI = 11.0                                // Inner arc radius
    private const val STEM_TOP = 38.0                          // Y where stem meets curve
    private const val STEM_BOT = 56.0                          // Y at viewport bottom
    private const val CAP_R = W / 2.0                          // 3.5 — rounded cap radius
    private const val STEM_LEN = 6.36                          // Exit stem length

    // Apple's exact cap control-point offsets (from the 90° right turn — see Python source)
    private const val CAP_OFF_X = 1.9330
    private const val CAP_OFF_Y = 1.5670

    /**
     * Generate a turn arrow at [angleDeg] degrees sweeping [side] (LEFT or RIGHT).
     *
     * Returns a single Path containing the full arrow (stem + curve + exit + arrowhead) as
     * one shape. Use [Path.FillType.WINDING] (Android default) — the shape is self-consistent.
     */
    fun generate(angleDeg: Double, side: Side): Path {
        val path = Path()

        // Right-turn geometry, then mirror about x=15.5 if LEFT.
        val theta = angleDeg * PI / 180.0
        val stemCx = 34.5
        val stemLeft = stemCx - W / 2.0
        val stemRight = stemCx + W / 2.0
        val cx = stemLeft + RO  // Arc center X: 49.0 for right turn
        val cy = STEM_TOP       // Arc center Y: 38.0

        // ── 1. Vertical stem with rounded cap at bottom ──
        val capTopY = STEM_BOT - CAP_R
        path.moveTo(stemCx.toFloat(), STEM_BOT.toFloat())
        // Bezier cap-bottom-left
        path.cubicTo(
            (stemCx - CAP_OFF_X).toFloat(), STEM_BOT.toFloat(),
            stemLeft.toFloat(), (STEM_BOT - CAP_OFF_Y).toFloat(),
            stemLeft.toFloat(), capTopY.toFloat(),
        )
        path.lineTo(stemLeft.toFloat(), STEM_TOP.toFloat())
        path.lineTo(stemRight.toFloat(), STEM_TOP.toFloat())
        path.lineTo(stemRight.toFloat(), capTopY.toFloat())
        // Bezier cap-bottom-right
        path.cubicTo(
            stemRight.toFloat(), (STEM_BOT - CAP_OFF_Y).toFloat(),
            (stemCx + CAP_OFF_X).toFloat(), STEM_BOT.toFloat(),
            stemCx.toFloat(), STEM_BOT.toFloat(),
        )
        path.close()

        // ── 2. Curve from STEM_TOP to exit direction ──
        // Outer arc: from angle π (matches stem-left at y=STEM_TOP) sweeping +theta CW
        // Inner arc: same angular range, reversed for back-fill
        val outerSegs = GeometryPrimitives.arcBezier(cx, cy, RO, PI, theta)
        val innerSegs = GeometryPrimitives.arcBezier(cx, cy, RI, PI + theta, -theta)

        // Outer arc (CW: π → π+θ at radius RO)
        GeometryPrimitives.appendArc(path, outerSegs, moveFirst = true)
        // Line down to inner arc start (at the exit end)
        val innerStart = innerSegs.first()
        path.lineTo(innerStart.p0x.toFloat(), innerStart.p0y.toFloat())
        // Inner arc (backwards)
        for (seg in innerSegs) {
            path.cubicTo(
                seg.p1x.toFloat(), seg.p1y.toFloat(),
                seg.p2x.toFloat(), seg.p2y.toFloat(),
                seg.p3x.toFloat(), seg.p3y.toFloat(),
            )
        }
        path.lineTo(stemRight.toFloat(), STEM_TOP.toFloat())
        path.close()

        // ── 3. Exit stem (short rectangle leading into arrowhead) ──
        val dx = sin(theta)
        val dy = -cos(theta)
        val oex = cx + RO * cos(PI + theta)
        val oey = cy + RO * sin(PI + theta)
        val iex = cx + RI * cos(PI + theta)
        val iey = cy + RI * sin(PI + theta)
        val osx = oex + STEM_LEN * dx
        val osy = oey + STEM_LEN * dy
        val isx = iex + STEM_LEN * dx
        val isy = iey + STEM_LEN * dy

        path.moveTo(osx.toFloat(), osy.toFloat())
        path.lineTo(isx.toFloat(), isy.toFloat())
        path.lineTo(iex.toFloat(), iey.toFloat())
        path.lineTo(oex.toFloat(), oey.toFloat())
        path.close()

        // ── 4. Arrowhead at exit-stem end, pointing in (dx, dy) ──
        val arrowOx = (osx + isx) / 2.0
        val arrowOy = (osy + isy) / 2.0
        GeometryPrimitives.appendArrowhead(path, arrowOx, arrowOy, dx, dy)

        return if (side == Side.LEFT) mirrorPathAboutX(path, mirrorX = 15.5f) else path
    }

    /**
     * Generate the U-turn arrow (CPManeuverType 4). Arrow loops 180° from south-up
     * (entering from south) to the bottom (going back south).
     *
     * Note: Apple's U-turn icon is a discrete shape, not exactly the parameterized turn at
     * 180° — it has a wider loop and the arrowhead points downward to the left of where it
     * came from. The Python lib has it as a static path constant. This implementation
     * approximates it via the turn-arrow family at 180° sweeping LEFT; for 1:1 fidelity
     * with Apple's U-turn, future work should port the SHAPES["uturn"] path string directly.
     */
    fun generateUTurn(): Path = generate(angleDeg = 180.0, side = Side.LEFT)

    /**
     * Mirror a Path about a vertical line at x = [mirrorX]. Used to flip right-turn paths
     * into left-turn paths (Apple does the same in MapKit via CGAffineTransform.scale(-1)).
     *
     * Implementation: extract path segments via PathMeasure → reconstruct mirrored. For
     * Android, the simplest and correct method is android.graphics.Matrix.setScale(-1, 1)
     * around the mirror axis.
     */
    private fun mirrorPathAboutX(src: Path, mirrorX: Float): Path {
        val m = android.graphics.Matrix()
        // Mirror about vertical line x = mirrorX: scale(-1, 1) about (mirrorX, 0).
        // That's: translate(-mirrorX) → scale(-1, 1) → translate(+mirrorX).
        m.setTranslate(mirrorX, 0f)
        m.preScale(-1f, 1f)
        m.preTranslate(-mirrorX, 0f)
        val dst = Path()
        src.transform(m, dst)
        return dst
    }
}
