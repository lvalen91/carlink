package com.carlink.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Frosted-Glass surface primitives (day/night aware).
 *
 * The "glass" look is built from four layers stacked by [frostedGlass]:
 *  1. a soft drop shadow (lift),
 *  2. a translucent fill (tinted by the theme surface — lighter in day, darker at night),
 *  3. a top-down specular sheen,
 *  4. a thin bright rim that is brightest at the top edge.
 *
 * Day vs night is detected from the active [MaterialTheme] surface luminance, so it tracks the
 * AAOS system theme automatically (CarlinkTheme already switches the colorScheme by day/night).
 */
object GlassShapes {
    /** Outer panels / cards. */
    val Card = RoundedCornerShape(28.dp)

    /** Nested elements (device cards). */
    val Inner = RoundedCornerShape(22.dp)

    /** Buttons — full pill. */
    val Button = RoundedCornerShape(percent = 50)
}

@Immutable
data class GlassColors(
    val fill: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val sheen: Color,
)

/**
 * Resolve the glass layer colors for the current theme.
 * @param strong slightly more opaque fill (outer panels / buttons) for legibility over busy
 *   blurred video; nested elements pass false for a lighter, more see-through pane.
 */
@Composable
fun glassColors(strong: Boolean = false): GlassColors {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val fillAlpha = (if (dark) 0.42f else 0.5f) + if (strong) 0.1f else 0f
    return GlassColors(
        fill = surface.copy(alpha = fillAlpha),
        rimTop = Color.White.copy(alpha = if (dark) 0.35f else 0.85f),
        rimBottom = Color.White.copy(alpha = if (dark) 0.08f else 0.35f),
        sheen = Color.White.copy(alpha = if (dark) 0.10f else 0.40f),
    )
}

/**
 * Apply the Frosted-Glass treatment to a surface: translucent fill → optional state [tint] →
 * specular sheen → bright rim. Caller supplies the [shape] (use [GlassShapes]).
 *
 * No elevation shadow is used: Android casts elevation shadows from an element's *opaque* outline,
 * but a glass fill is translucent, so `Modifier.shadow` falls back toward the rectangular layer
 * bounds — producing a boxy halo behind the rounded shape that is visible over a bright/blurred
 * backdrop. Depth here comes from the rim + sheen (which is how frosted glass reads anyway).
 *
 * @param tint optional state color drawn over the fill (e.g. active CarPlay/AA card), under the
 *   sheen + rim so the glass edges stay intact.
 */
@Composable
fun Modifier.frostedGlass(
    shape: Shape = GlassShapes.Card,
    strong: Boolean = false,
    tint: Color? = null,
): Modifier {
    val g = glassColors(strong)
    return this
        .clip(shape)
        .background(g.fill)
        .then(if (tint != null) Modifier.background(tint) else Modifier)
        .background(Brush.verticalGradient(listOf(g.sheen, Color.Transparent)))
        .border(width = 1.dp, brush = Brush.verticalGradient(listOf(g.rimTop, g.rimBottom)), shape = shape)
}

/**
 * A Frosted-Glass pill button (translucent + rim) for non-destructive actions. Content color is
 * supplied via [contentColor] (defaults to onSurface) and flows to Icon/Text through
 * LocalContentColor; disabled state dims it. Destructive actions stay solid (use a filled Button).
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit,
) {
    val resolved = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    Row(
        modifier =
            modifier
                .frostedGlass(GlassShapes.Button, strong = true)
                .clip(GlassShapes.Button)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides resolved) {
            content()
        }
    }
}
