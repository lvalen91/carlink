package com.carlink.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.ui.theme.AutomotiveDimens

/**
 * Display Mode Dialog with Live Preview
 *
 * Allows users to select between four display modes with instant visual preview.
 * Changes are previewed immediately by toggling system bars. Apply performs an
 * in-place tier-2 session reinit via MainActivity.reinitializeForDisplayMode
 * (NOT a process kill/restart — see SettingsScreen.kt:559 for historical context).
 *
 * Modes:
 * - SYSTEM_UI_VISIBLE: Both status bar and navigation bar always visible
 * - STATUS_BAR_HIDDEN: Only status bar hidden, navigation bar remains visible
 * - NAV_BAR_HIDDEN: Only navigation bar hidden, status bar remains visible
 * - FULLSCREEN_IMMERSIVE: Both bars hidden, swipe to reveal temporarily
 *
 * Precondition: WindowCompat.setDecorFitsSystemWindows(window, false) MUST be in effect;
 * MainActivity sets it at onCreate. Confirmed working on gminfo37 (POTATO 2026-04-20: cutout
 * T:0 B:0 L:0 R:0; bounds 2400x960 == display 2400x960 regardless of mode). Preview toggles
 * would no-op under the default `true`.
 *
 * @param onApplyAndRestart Historical name; behavior is an in-place session reinit
 *   via MainActivity.reinitializeForDisplayMode, NOT a process restart. Name is
 *   preserved to avoid breaking callers.
 */
@Composable
internal fun DisplayModeDialog(
    displayModePreference: DisplayModePreference,
    onDismiss: () -> Unit,
    onApplyAndRestart: (DisplayMode) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Get window for live preview
    val window = (context as? ComponentActivity)?.window

    // Load saved mode from preferences
    val savedMode by displayModePreference.displayModeFlow.collectAsStateWithLifecycle(
        initialValue = DisplayMode.SYSTEM_UI_VISIBLE,
    )

    // Local state for preview - allows cancel without saving
    var selectedMode by remember { mutableStateOf(savedMode) }

    // Sync local state when saved value loads (for initial load)
    LaunchedEffect(savedMode) {
        selectedMode = savedMode
    }

    // LIVE PREVIEW: Apply mode instantly when selection changes
    LaunchedEffect(selectedMode) {
        window?.let { applyDisplayModePreview(it, selectedMode) }
    }

    // Restore original mode when dialog dismissed without saving.
    // KNOWN ISSUE (flicker on Apply): SettingsScreen.kt:548 flips
    // showDisplayModeDialog=false synchronously, so onDispose fires immediately
    // with the STALE captured savedMode — before the setDisplayMode(newMode)
    // coroutine has emitted. System bars briefly flip to the old mode until
    // MainActivity.reinitializeForDisplayMode applies the new one.
    // Also redundant: Cancel, back-press, and outside-tap each already call
    // applyDisplayModePreview(savedMode); onDispose is a third (harmless) call.
    DisposableEffect(Unit) {
        onDispose {
            window?.let { applyDisplayModePreview(it, savedMode) }
        }
    }

    val hasChanges = selectedMode != savedMode

    // Responsive dialog width - 60% of container width, clamped between 320dp and 600dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val dialogMaxWidth = (containerWidthDp * 0.6f).coerceIn(320.dp, 600.dp)

    Dialog(onDismissRequest = {
        // Restore original mode on cancel
        window?.let { applyDisplayModePreview(it, savedMode) }
        onDismiss()
    }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = dialogMaxWidth),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Display Mode",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle with preview indicator
                // NOTE: Text is stale — Apply now performs in-place tier-2 session
                // reinit (no process restart). Consider rewording to
                // "Preview changes instantly • Reinitialize to apply".
                Text(
                    text = "Preview changes instantly • Restart required to apply",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Scrollable content area
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Configuration option card
                    // See ConfigurationOptionCard in AdapterConfigurationDialog.kt:929 (internal).
                    ConfigurationOptionCard(
                        title = "App Immersion",
                        description = "Control system UI visibility during projection",
                        icon = Icons.Default.Layers,
                    ) {
                        // Three mode buttons in a row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DisplayModeButton(
                                label = "System UI",
                                icon = Icons.Default.FullscreenExit,
                                isSelected = selectedMode == DisplayMode.SYSTEM_UI_VISIBLE,
                                onClick = { selectedMode = DisplayMode.SYSTEM_UI_VISIBLE },
                                modifier = Modifier.weight(1f),
                            )
                            DisplayModeButton(
                                label = "Hide Status",
                                icon = Icons.Default.Layers,
                                isSelected = selectedMode == DisplayMode.STATUS_BAR_HIDDEN,
                                onClick = { selectedMode = DisplayMode.STATUS_BAR_HIDDEN },
                                modifier = Modifier.weight(1f),
                            )
                            DisplayModeButton(
                                label = "Hide Dock",
                                icon = Icons.Default.WebAsset,
                                isSelected = selectedMode == DisplayMode.NAV_BAR_HIDDEN,
                                onClick = { selectedMode = DisplayMode.NAV_BAR_HIDDEN },
                                modifier = Modifier.weight(1f),
                            )
                            DisplayModeButton(
                                label = "Fullscreen",
                                icon = Icons.Default.Fullscreen,
                                isSelected = selectedMode == DisplayMode.FULLSCREEN_IMMERSIVE,
                                onClick = { selectedMode = DisplayMode.FULLSCREEN_IMMERSIVE },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Description for selected mode
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text =
                                when (selectedMode) {
                                    DisplayMode.SYSTEM_UI_VISIBLE -> {
                                        "Status bar and navigation always visible. AAOS manages display bounds."
                                    }

                                    DisplayMode.STATUS_BAR_HIDDEN -> {
                                        "Status bar hidden, navigation bar visible. Extra vertical space."
                                    }

                                    DisplayMode.NAV_BAR_HIDDEN -> {
                                        "Navigation bar hidden, status bar visible. Extra bottom space."
                                    }

                                    DisplayMode.FULLSCREEN_IMMERSIVE -> {
                                        "All system UI hidden. Swipe edge to temporarily reveal."
                                    }
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer with action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel - restores original mode
                    TextButton(
                        onClick = {
                            window?.let { applyDisplayModePreview(it, savedMode) }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }

                    // Apply & Restart
                    // NOTE: Label "Apply & Restart" is stale — the underlying action
                    // is an in-place session reinit (MainActivity.reinitializeForDisplayMode),
                    // NOT a process restart. Label preserved for user familiarity.
                    Button(
                        onClick = { onApplyAndRestart(selectedMode) },
                        modifier = Modifier.weight(1.5f),
                        enabled = hasChanges,
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply & Restart")
                    }
                }
            }
        }
    }
}

/**
 * Applies the display mode preview by showing/hiding system bars.
 * Used for instant visual feedback in the DisplayModeDialog.
 */
private fun applyDisplayModePreview(
    window: android.view.Window,
    mode: DisplayMode,
) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)

    when (mode) {
        DisplayMode.SYSTEM_UI_VISIBLE -> {
            // Show all system bars - let AAOS manage display bounds.
            // MINOR QUIRK: systemBarsBehavior is not reset here. If the user
            // previously previewed a hiding mode, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // persists on the controller. Benign since bars are shown, but noted.
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        DisplayMode.STATUS_BAR_HIDDEN -> {
            // Hide status bar only, keep navigation bar visible
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.show(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        DisplayMode.NAV_BAR_HIDDEN -> {
            // Hide navigation bar only, keep status bar visible
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        DisplayMode.FULLSCREEN_IMMERSIVE -> {
            // Hide all system bars for maximum projection area
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * Display Mode Selection Button
 *
 * Toggle button with animated visual indicator for selected state.
 * Styled to match AudioSourceButton for consistency.
 *
 * PERF NOTE: Uses 5 animate*AsState per button (bg, border width, border color,
 * content color, icon scale) × 4 buttons = 20 animation state objects. Not a
 * flicker risk, but a noticeable recomposition cost on AAOS. Deliberate trade-off
 * for button feel.
 */
@Composable
private fun DisplayModeButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Animated properties for smooth selection transitions
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primaryContainer else colorScheme.surfaceContainer,
        label = "backgroundColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        label = "borderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.outline,
        label = "borderColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
        label = "contentColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        label = "iconScale",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(AutomotiveDimens.ButtonMinHeight),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border =
            BorderStroke(
                width = borderWidth,
                color = borderColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier =
                    Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                color = contentColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}
