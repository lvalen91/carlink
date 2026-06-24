package com.carlink.util

import android.graphics.Rect
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat

/**
 * Thin window-geometry helper.
 *
 * minSdk is 32 (Android 12L), so `currentWindowMetrics` and `getInsetsIgnoringVisibility`
 * (both API 30) are always available — there is no pre-30 fallback. Kept as a small wrapper
 * so call sites read uniformly.
 */
object WindowMetricsCompat {

    /** Full display bounds in pixels. */
    fun displayBounds(windowManager: WindowManager): Rect =
        windowManager.currentWindowMetrics.bounds

    /**
     * Stable window insets (ignoring visibility) as a [WindowInsetsCompat]. Callers extract
     * specific inset types via [WindowInsetsCompat.getInsetsIgnoringVisibility] with a
     * [WindowInsetsCompat.Type] mask.
     */
    fun stableWindowInsets(windowManager: WindowManager): WindowInsetsCompat =
        WindowInsetsCompat.toWindowInsetsCompat(windowManager.currentWindowMetrics.windowInsets)
}
