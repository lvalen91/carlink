package com.carlink.ui

import android.view.MotionEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhoneType
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch

/** Main projection screen displaying H.264 video via SurfaceView (HWC overlay) with touch forwarding. */
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    displayMode: DisplayMode,
    onNavigateToSettings: () -> Unit,
    onResetConnection: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Key state on carlinkManager identity — when manager is replaced (display mode reinit),
    // all session-scoped state resets automatically. This prevents stale callbacks, flags,
    // or touch state from the old manager leaking into the new session.
    var connectionState by remember(carlinkManager) { mutableStateOf(CarlinkManager.State.DISCONNECTED) }
    var statusText by remember(carlinkManager) { mutableStateOf("Connect Adapter") }
    var isResetting by remember(carlinkManager) { mutableStateOf(false) }
    val surfaceState = rememberVideoSurfaceState()
    var isAndroidAuto by remember(carlinkManager) { mutableStateOf(false) }
    var aaCropParams by remember(carlinkManager) { mutableStateOf<CarlinkManager.AaCropParams?>(null) }

    LaunchedEffect(connectionState) {
        logInfo("[UI_STATE] MainScreen connection state: $connectionState", tag = "UI")
    }

    var lastTouchTime by remember(carlinkManager) { mutableLongStateOf(0L) }
    val activeTouches = remember(carlinkManager) { mutableStateMapOf<Int, TouchPoint>() }
    var hasStartedConnection by remember(carlinkManager) { mutableStateOf(false) }

    // Container dimensions (display-mode-padded area) — used for BoxSettings AR calculation.
    // Tracked separately from surfaceState because AA oversizes the SurfaceView beyond the container.
    // NOTE: activeTouches (L95) may leak stale DOWN entries if the SurfaceView is destroyed
    // mid-gesture (AA resize) without a matching UP; bounded-minor leak cleared on next manager
    // reinit (remember(carlinkManager) resets the map).
    var containerSize by remember(carlinkManager) { mutableStateOf(IntSize.Zero) }

    // Handle surface initialization for adapter — uses CONTAINER dimensions for config/BoxSettings,
    // not the SurfaceView dimensions (oversized for AA bar cropping — confirmed in AutoKit; not
    // exercised in 2026-04-20 POTATO gminfo37 captures since all sessions ran CarPlay isAA=false).
    // Re-invoked on every Surface change (SurfaceView destroy→create, AA oversize resize, rotation);
    // CarlinkManager.initialize MUST be idempotent. hasStartedConnection (L96) is cleared only on
    // manager swap, so a new Surface with the same manager (AA resize) will re-initialize but will
    // NOT re-invoke start() — preserving the session.
    LaunchedEffect(surfaceState.surface, containerSize) {
        surfaceState.surface?.let { surface ->
            if (containerSize.width <= 0 || containerSize.height <= 0) return@let

            // Force even dimensions for H.264 macroblock alignment
            val adapterWidth = containerSize.width and 1.inv()
            val adapterHeight = containerSize.height and 1.inv()

            logInfo(
                "[CARLINK_RESOLUTION] Container size: ${adapterWidth}x$adapterHeight " +
                    "(surface: ${surfaceState.width}x${surfaceState.height}, mode=$displayMode)",
                tag = "UI",
            )

            carlinkManager.initialize(
                surface = surface,
                surfaceWidth = adapterWidth,
                surfaceHeight = adapterHeight,
                callback =
                    object : CarlinkManager.Callback {
                        override fun onStateChanged(state: CarlinkManager.State) {
                            connectionState = state
                        }

                        override fun onStatusTextChanged(text: String) {
                            statusText = text
                        }

                        override fun onHostUIPressed() {
                            onNavigateToSettings()
                        }

                        override fun onPhoneTypeChanged(phoneType: PhoneType) {
                            // Known limitation: aaCropParams is only refreshed on phoneType transition.
                            // If CarlinkManager later recomputes crop params (e.g. mid-session display
                            // resize), this UI won't see the update until the next phoneType toggle or
                            // manager reinit.
                            val isAA = phoneType == PhoneType.ANDROID_AUTO
                            if (isAA != isAndroidAuto) {
                                isAndroidAuto = isAA
                                aaCropParams = if (isAA) carlinkManager.getAaCropParams() else null
                                logInfo(
                                    "[UI_SURFACE] Phone type changed: $phoneType, isAA=$isAA, crop=$aaCropParams",
                                    tag = "UI",
                                )
                            }
                        }
                    },
            )
            if (!hasStartedConnection) {
                hasStartedConnection = true
                carlinkManager.start()
            }
        }
    }

    val isLoading = connectionState != CarlinkManager.State.STREAMING
    val colorScheme = MaterialTheme.colorScheme

    val baseModifier = Modifier.fillMaxSize().background(Color.Black)
    val boxModifier =
        when (displayMode) {
            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                baseModifier
            }

            DisplayMode.SYSTEM_UI_VISIBLE -> {
                baseModifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
            }

            DisplayMode.STATUS_BAR_HIDDEN,
            DisplayMode.NAV_BAR_HIDDEN,
            -> {
                baseModifier
                    .windowInsetsPadding(WindowInsets.systemBars)
            }
            // No displayCutout padding — video extends behind cutout.
            // (SafeArea, which tells CarPlay where to avoid placing UI, is NOT sent from this file —
            // it is emitted by CarlinkManager/MessageSerializer. This comment documents the visual
            // intent of the hidden-bars branch above, not an action performed here.)
        }

    Box(modifier = boxModifier) {
        val density = LocalDensity.current

        // For AA: SurfaceView oversized to tier AR (16:9), centered + clipped.
        // Black bars in the codec frame fall outside the clip → cropped visually.
        // Codec start is deferred until phone type is known, so surface resize
        // (destroyed→created at oversized dims) happens before decoder is active.
        // (Cross-file: the deferral is enforced in CarlinkManager.kt — see initialize()
        // and the phone-type gate around the codec start path, approx. :247 / :1153 / :1477.)
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
            // Track container dimensions for BoxSettings AR calculation
            val containerPx =
                with(density) {
                    IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                }
            LaunchedEffect(containerPx) {
                if (containerPx.width > 0 && containerPx.height > 0) {
                    containerSize = containerPx
                }
            }

            // AA: oversize SurfaceView to tier AR so black bars overflow the clip region.
            // CarPlay: SurfaceView fills container (frame matches surface, no crop needed).
            val surfaceModifier =
                if (aaCropParams != null) {
                    val tierAR = aaCropParams!!.tierWidth.toFloat() / aaCropParams!!.tierHeight.toFloat()
                    val oversizedHeightDp = with(density) { (maxWidth.toPx() / tierAR).toInt().toDp() }
                    Modifier
                        .fillMaxWidth()
                        .requiredHeight(oversizedHeightDp)
                        .align(Alignment.Center)
                } else {
                    Modifier.fillMaxSize()
                }

            // Key the VideoSurface on (manager identity, displayMode) so any reinit
            // path (display-mode change, resolution change, Reset Connection via the
            // manager-rebuild path) drops the AndroidView composition slot, disposing
            // the old VideoSurfaceView and releasing its HWC overlay plane. A fresh
            // SurfaceView is then inflated against the current window rect — the only
            // reliable way to migrate the HWC plane when system insets change post-boot.
            // Parent-size deltas alone are NOT included here: they would rebuild the
            // SurfaceView on every transient resize (e.g. bar animation frames) and
            // cause video flicker. Only identity-level changes force recreation.
            key(carlinkManager, displayMode) {
            VideoSurface(
                modifier = surfaceModifier,
                onSurfaceAvailable = { surface, width, height ->
                    logInfo("[UI_SURFACE] Surface available: ${width}x$height (isAA=$isAndroidAuto)", tag = "UI")
                    surfaceState.onSurfaceAvailable(surface, width, height)
                },
                onSurfaceDestroyed = {
                    logInfo("[UI_SURFACE] Surface destroyed", tag = "UI")
                    surfaceState.onSurfaceDestroyed()
                    carlinkManager.onSurfaceDestroyed()
                },
                onSurfaceSizeChanged = { width, height ->
                    logInfo("[UI_SURFACE] Surface size changed: ${width}x$height", tag = "UI")
                    surfaceState.onSurfaceSizeChanged(width, height)
                },
                onTouchEvent = { event ->
                    // Read state directly through the Compose delegate — NOT via
                    // the pre-computed val `isUserInteractingWithProjection`.
                    // This lambda is captured once in AndroidView's factory block;
                    // a pre-computed val would snapshot DISCONNECTED permanently.
                    if (connectionState == CarlinkManager.State.STREAMING) {
                        if (BuildConfig.DEBUG) {
                            val now = System.currentTimeMillis()
                            if (now - lastTouchTime > 1000) {
                                logDebug(
                                    "[UI_TOUCH] touch: action=${event.actionMasked}" +
                                        ", pointers=${event.pointerCount}" +
                                        ", surface=${surfaceState.width}x${surfaceState.height}" +
                                        ", container=${containerSize.width}x${containerSize.height}",
                                    tag = "UI",
                                )
                                lastTouchTime = now
                            }
                        }
                        handleTouchEvent(
                            event,
                            activeTouches,
                            carlinkManager,
                            surfaceState.width,
                            surfaceState.height,
                            containerSize.height,
                        )
                    }
                    true
                },
            )
            } // end key(carlinkManager, displayMode)
        }

        // Loading overlay
        if (isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(colorScheme.scrim.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_phone_projection),
                        contentDescription = "Carlink",
                        modifier = Modifier.height(220.dp),
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    LoadingSpinner(
                        color = colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "[ $statusText ]",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFDDE4E5), // Fixed light color for dark overlay
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledTonalButton(
                    onClick = { onNavigateToSettings() },
                    modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                    contentPadding =
                        PaddingValues(
                            horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                            vertical = AutomotiveDimens.ButtonPaddingVertical,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(AutomotiveDimens.IconSize),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                FilledTonalButton(
                    onClick = {
                        if (!isResetting) {
                            isResetting = true
                            // Prefer the Activity-level manager-rebuild path when wired
                            // (it recreates the SurfaceView against fresh WindowMetrics and
                            // renegotiates Open() with current dims — deterministic repair).
                            // Fall back to in-manager restart() for callers that don't plumb
                            // the callback (e.g. tests/previews).
                            val reset = onResetConnection
                            if (reset != null) {
                                reset()
                                // Activity releases the old manager; Compose key(carlinkManager, ...)
                                // will drop this subtree shortly. Clear the spinner promptly so
                                // the UI doesn't flash a stale "resetting" state during teardown.
                                isResetting = false
                            } else {
                                scope.launch {
                                    try {
                                        carlinkManager.restart()
                                    } finally {
                                        isResetting = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isResetting,
                    modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = colorScheme.errorContainer,
                            contentColor = colorScheme.onErrorContainer,
                        ),
                    contentPadding =
                        PaddingValues(
                            horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                            vertical = AutomotiveDimens.ButtonPaddingVertical,
                        ),
                ) {
                    AnimatedContent(
                        targetState = isResetting,
                        transitionSpec = {
                            (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                        },
                        label = "resetIconTransition",
                    ) { resetting ->
                        if (resetting) {
                            LoadingSpinner(
                                size = AutomotiveDimens.IconSize,
                                color = colorScheme.onErrorContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Device",
                                modifier = Modifier.size(AutomotiveDimens.IconSize),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset Device",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** In-memory per-pointer touch record (normalized 0..1 coords + last action) for deduping MOVE spam. */
private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

/**
 * Handle touch on the SurfaceView.
 *
 * eventX/Y are in the SurfaceView's coordinate space. For AA the SurfaceView is
 * oversized (e.g. 2400x1350) but only [containerHeight] pixels are visible
 * (the center portion). We subtract the crop offset so touch maps to 0..1 of
 * the visible content area.
 *
 * AA:     type 0x05, 0..10000 ints  (CarlinkManager converts 0..1 → 0..10000)
 * CarPlay: type 0x17, 0..1 floats   (unchanged)
 */
private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
    containerHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0 || containerHeight == 0) return

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)

    val action =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
            MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> MultiTouchAction.UP
            else -> return
        }

    // Crop offset: how many SurfaceView pixels are hidden above the visible area.
    // For CarPlay (no oversize): surfaceHeight == containerHeight → cropTop = 0.
    // For AA (oversized to tier AR): e.g. (1350 - 960) / 2 = 195.
    val cropTopY = (surfaceHeight - containerHeight) / 2f

    // Normalize to 0..1 of the oversized surface (matches AutoKit's scaled_height denominator).
    // X: assumed surface width == container width. Verified for AA per AutoKit. CarPlay POTATO
    //    logs 2026-04-20 show off-by-1 drift (container 1604x960 vs surface 1605x960; 2210x960
    //    vs 2211x842) — 1-px error is inside MotionEvent rounding and harmless, but if a future
    //    AA tier ever oversizes W this assumption breaks silently (touch x would undershoot).
    // Y: subtract crop offset, divide by SURFACE height (not container).
    //
    // Confirmed against AutoKit: its AA touch path normalizes with `(x * 10000) / scaled_width`
    // and `(y * 10000) / scaled_height`, where `scaled_width`/`scaled_height` are the OVERSIZED
    // post-resize view dimensions (not the visible content box). Dividing by surfaceHeight here
    // reproduces that denominator — the visible-area y normalizes to 0..(containerHeight/surfaceHeight),
    // e.g. 0..0.711 for a 1350/960 AA tier. AA side then scales 0..1 → 0..10000 at
    // CarlinkManager.kt (near sendMultiTouch), so bottom-of-visible touches cap near ~7111 rather
    // than 10000 — matching what the adapter expects.
    val x = event.getX(pointerIndex) / surfaceWidth
    val y = (event.getY(pointerIndex) - cropTopY) / surfaceHeight

    when (action) {
        MultiTouchAction.DOWN -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        MultiTouchAction.MOVE -> {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = event.getX(i) / surfaceWidth
                val py = (event.getY(i) - cropTopY) / surfaceHeight
                activeTouches[id]?.let { existing ->
                    // Deadband: delta is normalized 0..1, multiplied by 1000 then compared against 3.
                    // Effective threshold ≈ 0.003 of the normalized surface (~0.3%), i.e. ~30 units
                    // on AA's 0..10000 scale, ~7 px horizontal / ~4 px vertical on a 2400x1350 AA
                    // surface. Suppresses sub-pixel MOVE spam without losing real drags.
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                }
            }
        }

        MultiTouchAction.UP -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        // Dead / defensive: the when at L447-451 only yields DOWN/MOVE/UP (other MotionEvent
        // actions early-return). This branch exists to satisfy exhaustiveness and guard against
        // future MultiTouchAction additions. Intentionally a no-op.
        else -> {}
    }

    val touchList =
        activeTouches.entries.map { entry ->
            MessageSerializer.TouchPoint(
                x = entry.value.x,
                y = entry.value.y,
                action = entry.value.action,
                id = entry.key,
            )
        }

    carlinkManager.sendMultiTouch(touchList)
    activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
}
