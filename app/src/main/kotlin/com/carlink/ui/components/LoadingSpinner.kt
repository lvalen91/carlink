package com.carlink.ui.components

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.carlink.R

/**
 * AVD-based loading spinner. Choice of AVD over Compose's CircularProgressIndicator is
 * inherited folklore — no in-tree benchmark, no logcat/metrics evidence either way on
 * gminfo37 (Intel HD 505, SurfaceFlinger normal per POTATO 2026-04-20). If
 * CircularProgressIndicator is ever benchmarked equivalent, drop the AndroidView interop.
 *
 * Hard dependency: [R.drawable.avd_spinner] must exist and must be an AnimatedVectorDrawable.
 * Both `as?` casts below fail silently — if shrinker strips it or someone renames it, the
 * spinner will render blank with no log. Keep avd_spinner in keep rules.
 *
 * Default [color] is [MaterialTheme.colorScheme.primary] (resolved at call site, not lazily).
 * Param name [size] intentionally shadows the [Modifier.size] import within this function.
 */
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // Captured outside factory because AndroidView.factory runs once; color changes flow
    // through the update block below (which re-reads colorArgb via closure on recomposition).
    val colorArgb = color.toArgb()

    AndroidView(
        modifier = modifier.size(size),
        factory = { context ->
            ImageView(context).apply {
                val drawable = ContextCompat.getDrawable(context, R.drawable.avd_spinner)
                drawable?.let { avd ->
                    // HAZARD: ContextCompat.getDrawable returns a Drawable sharing ConstantState
                    // with every other caller of R.drawable.avd_spinner (MainScreen.kt:297,391;
                    // SettingsScreen.kt:655,700). setTint mutates that shared state, so in
                    // principle two concurrently-visible spinners with different colors would
                    // cross-tint. Latent today because only one spinner is ever on screen.
                    // Fix when it bites: call `avd.mutate()` before setTint.
                    DrawableCompat.setTint(avd, colorArgb)
                    setImageDrawable(avd)
                    (avd as? AnimatedVectorDrawable)?.start()
                }
            }
        },
        update = { imageView ->
            // Runs on every recomposition: re-tints and re-checks isRunning. Safe because
            // AVD.start() is a no-op while already running and the isRunning guard makes it
            // explicit; redundant setTint is cheap. Same shared-ConstantState hazard as above.
            imageView.drawable?.let { drawable ->
                DrawableCompat.setTint(drawable, colorArgb)
                (drawable as? AnimatedVectorDrawable)?.let { avd ->
                    if (!avd.isRunning) avd.start()
                }
            }
        },
        // No DisposableEffect / onRelease: the AVD keeps animating until GC after this
        // composable leaves composition. Acceptable because only one spinner is visible at
        // a time and the cost is trivial; revisit if we ever pool many spinners.
    )
}
