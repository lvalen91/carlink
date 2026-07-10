package com.carlink.navigation.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Renders a [ComposedIcon] (background + foreground paths) to a [Bitmap] suitable for
 * `CarIcon.createWithBitmap()`. Two-pass paint: background first in grey, then foreground
 * in white — matches Apple's MapKit two-tone scheme (secondaryLabelColor + labelColor).
 *
 * Stateless static functions, safe to call from any thread.
 *
 * Color scheme matches Apple Maps' card icons:
 *   - background  → 0xFFB0B0B0 (semantic = secondaryLabelColor on dark theme; ~70% grey)
 *   - foreground  → 0xFFFFFFFF (semantic = labelColor on dark theme; pure white)
 *
 * Adjust [FOREGROUND_COLOR] / [BACKGROUND_COLOR] if rendering on a light-themed cluster —
 * but cluster context on GM/AAOS is always dark, so white-on-grey-on-transparent is the
 * universal choice.
 */
internal object IconBitmapRenderer {
    /**
     * Viewport size in source coordinates. Apple's MapKit uses 56pt as the nominal canvas,
     * but the actual shape bounds extend WELL beyond that — empirically verified by calling
     * the private `MKArrowAppend*ToPathInRect` on macOS arm64e MapKit (2026-05-26):
     *
     *   - LeftTurn:  x ranges -42.14 .. +0     (arrowhead tip at -42.14 to the west)
     *   - RightTurn: x ranges  +25   .. +73.14 (arrowhead tip at +73.14 to the east)
     *   - U-turn:    x ranges  +0.06 .. +49.5  (vertical-ish, fits)
     *   - Merge*:    x ranges  +6    .. +49    (fits)
     *   - ExitRoad*: similar range
     *   - Roundabouts: span ~R_OUT+STUB+ARROW = ~42 from center
     *
     * For the left/right turn arrows, the total horizontal span is from -42 to +73 = 115pt.
     * Centered on Apple's (28, 28) origin, we need viewport ≥ 115 + 2*max(|center-edge|) =
     * 115 + 2*45 = ~150pt safe. Using 160pt provides ~22pt margin on the leftmost arrowhead
     * tip and ~14pt margin on the rightmost. Cluster downscales to ~88-200dp regardless,
     * so the larger viewport doesn't affect visible output quality.
     *
     * Prior 96pt viewport CLIPPED the LeftTurn arrowhead (verified 2026-05-26 in Robolectric
     * test); 160pt fixes it.
     */
    private const val VIEWPORT_SIZE = 160.0f
    /**
     * Apple's nominal canvas is centered on (28, 28) but the actual icon bbox is NOT
     * always centered there. e.g. LEFT_TURN bbox center is at x=-21.6 (49pt left of canvas
     * center), RIGHT_TURN at x=52.6 (25pt right). Translating Apple's (28, 28) to the
     * viewport center produces visually off-center bitmaps — pure LEFT_TURN ends up at
     * the left edge of the bitmap and looks clipped when the cluster displays it.
     *
     * We compute each icon's actual bbox at render time and translate to center THAT.
     * Roundabouts have a background ring naturally centered on (28, 28); their union bbox
     * stays roughly centered, so the change is a no-op for them. Simple turns get a
     * per-icon translation so the arrow sits in the visual center regardless of which
     * direction it points.
     */

    /** Foreground color: the path the driver should take (Apple's `labelColor` on dark). */
    private const val FOREGROUND_COLOR = Color.WHITE

    /**
     * Background color: the rest of the junction (Apple's `secondaryLabelColor` on dark).
     *
     * NOTE: Cluster ICs on AAOS typically render at ~88-120 dp (compact size) on a dark
     * background. A 70% grey (#B0B0B0) is hard to see at that scale against the cluster's
     * typically very-dark theme. Apple's iOS uses ~60% opacity on white = #999999 effective,
     * which IS more visible at small sizes. Tuned to match the visual weight Apple's icons
     * achieve at the cluster's render size.
     */
    private val BACKGROUND_COLOR = Color.argb(0xFF, 0x99, 0x99, 0x99)

    fun render(
        size: Int,
        icon: ComposedIcon,
        foregroundColor: Int = FOREGROUND_COLOR,
        backgroundColor: Int = BACKGROUND_COLOR,
        canvasBackground: Int = Color.TRANSPARENT,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (canvasBackground != Color.TRANSPARENT) canvas.drawColor(canvasBackground)

        val scale = size.toFloat() / VIEWPORT_SIZE

        val bbox = RectF()
        icon.foreground.computeBounds(bbox, true)
        icon.background?.let {
            val bgBox = RectF()
            it.computeBounds(bgBox, true)
            bbox.union(bgBox)
        }
        val centerX = (bbox.left + bbox.right) / 2.0f
        val centerY = (bbox.top + bbox.bottom) / 2.0f
        val viewportCenter = VIEWPORT_SIZE / 2.0f

        val matrix = Matrix().apply {
            setTranslate(viewportCenter - centerX, viewportCenter - centerY)
            postScale(scale, scale)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Background pass — grey junction shape (ring + non-exit spokes + entry)
        icon.background?.let { bg ->
            val scaled = Path().also { bg.transform(matrix, it) }
            paint.color = backgroundColor
            canvas.drawPath(scaled, paint)
        }

        // Foreground pass — white path (exit + arrowhead, or whole shape for simple maneuvers)
        val scaledFg = Path().also { icon.foreground.transform(matrix, it) }
        paint.color = foregroundColor
        canvas.drawPath(scaledFg, paint)

        return bmp
    }
}
