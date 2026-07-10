package com.carlink.navigation.compose

import android.graphics.Path

/**
 * A two-layer maneuver icon: a [background] (grey, secondaryLabelColor) showing the parts
 * of the junction you're NOT taking, and a [foreground] (white, labelColor) showing the
 * path you ARE taking.
 *
 * Apple's MapKit uses this exact two-tone scheme — see Apple_Maps_Maneuver_Icons_RE.md §10:
 *
 * > Clips intersection background (even-odd fill rule)
 * > Fills junction background with secondaryLabelColor
 * > Fills arrow path with labelColor
 *
 * This visual hierarchy is critical for driver readability: the white path/arrow stands out
 * sharply against the grey junction background, so the driver immediately sees which way to
 * go without parsing the whole geometry.
 *
 * For simple shapes that have no "other roads to show" (e.g. a standalone turn arrow with
 * no junction context, or a U-turn), [background] is null and [foreground] carries the
 * whole shape rendered in white.
 *
 * Both paths share the same 56×56 viewport — [IconBitmapRenderer] scales them together.
 */
internal data class ComposedIcon(
    val background: Path?,
    val foreground: Path,
)
