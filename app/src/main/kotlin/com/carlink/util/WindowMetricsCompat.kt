package com.carlink.util

import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * WindowMetricsCompat - Cross-version window geometry.
 *
 * PURPOSE:
 * WindowManager.getCurrentWindowMetrics() and WindowInsets.getInsetsIgnoringVisibility()
 * were both added in API 30 (Android 11 / R). Calling them on an API 29 device throws
 * NoSuchMethodError (an Error, not an Exception — so a try/catch(Exception) will NOT
 * shield it). This helper keeps the exact API 30+ behaviour the app already relies on
 * and adds an API 29 (AAOS 10) fallback so the app runs on gminfo3.7 head units that
 * have not been updated past Android 10.
 *
 * BEHAVIOUR:
 * - API 30+ : delegates to currentWindowMetrics — the original code path, unchanged.
 * - API 29  : Display.getRealMetrics for bounds, ViewCompat.getRootWindowInsets for insets.
 *
 * On AAOS the activity is always fullscreen, so getRealMetrics() (the full-display /
 * "maximum" metrics) equals currentWindowMetrics.bounds. gminfo3.7 has no display
 * cutout, so the inset approximation WindowInsetsCompat returns on API 29 is exact for
 * the system-bar types this app reads.
 */
object WindowMetricsCompat {

    /**
     * Full display bounds in pixels.
     *
     * @param windowManager the window manager for the display to measure.
     */
    fun displayBounds(windowManager: WindowManager): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

    /**
     * Stable window insets (ignoring visibility) as a [WindowInsetsCompat]. Callers
     * extract specific inset types via
     * [WindowInsetsCompat.getInsetsIgnoringVisibility] with a [WindowInsetsCompat.Type]
     * mask.
     *
     * @param windowManager the window manager for the current window.
     * @param decorView must be attached — true inside a `decorView.post { }` block or
     *   any time after the first layout pass. Returns [WindowInsetsCompat.CONSUMED]
     *   (all-zero insets) if the view is detached and insets cannot be resolved.
     */
    fun stableWindowInsets(
        windowManager: WindowManager,
        decorView: View,
    ): WindowInsetsCompat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsCompat.toWindowInsetsCompat(
                windowManager.currentWindowMetrics.windowInsets,
            )
        } else {
            ViewCompat.getRootWindowInsets(decorView) ?: WindowInsetsCompat.CONSUMED
        }
}
