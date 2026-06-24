package com.carlink.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhoneType
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.VideoSurfaceState
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.settings.PhonesTabContent
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.ui.theme.GlassButton
import com.carlink.ui.theme.GlassShapes
import com.carlink.ui.theme.liquidGlass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The single app screen (cp-stripped). When STREAMING it is the CarPlay projection (SurfaceView
 * / HWC overlay) with touch forwarding; otherwise it shows the [CarlinkDashboard] (adapter status,
 * adapter controls, known devices) — no separate Settings screen / overlay.
 */
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    onResetConnection: (() -> Unit)? = null,
) {
    // Key state on carlinkManager identity — when the manager is replaced (Reset Connection
    // rebuild), all session-scoped state resets automatically, preventing stale callbacks /
    // touch state from the old manager leaking into the new session.
    var connectionState by remember(carlinkManager) { mutableStateOf(CarlinkManager.State.DISCONNECTED) }
    var statusText by remember(carlinkManager) { mutableStateOf("Connect Adapter") }
    // True when the OEM "Exit" icon was pressed during a live session → overlay the dashboard.
    var showHostUi by remember(carlinkManager) { mutableStateOf(false) }
    val surfaceState = rememberVideoSurfaceState()

    LaunchedEffect(connectionState) {
        logInfo("[UI_STATE] MainScreen connection state: $connectionState", tag = "UI")
        // The host-UI overlay only makes sense over a live session; reset it otherwise so the
        // idle dashboard shows normally.
        if (connectionState != CarlinkManager.State.STREAMING) showHostUi = false
    }

    // Forward the AAOS day/night state to CarPlay. Fires when a session reaches STREAMING (initial
    // sync) and whenever the system theme toggles mid-session (isSystemInDarkTheme recomposes
    // because MainActivity handles the uiMode config change without recreating).
    val darkTheme = isSystemInDarkTheme()
    LaunchedEffect(darkTheme, connectionState) {
        if (connectionState == CarlinkManager.State.STREAMING) {
            carlinkManager.setNightMode(darkTheme)
        }
    }

    var lastTouchTime by remember(carlinkManager) { mutableLongStateOf(0L) }
    val activeTouches = remember(carlinkManager) { mutableStateMapOf<Int, TouchPoint>() }
    var hasStartedConnection by remember(carlinkManager) { mutableStateOf(false) }

    // Container (display-bounds) dimensions, used for the adapter OPEN/BoxSettings resolution.
    var containerSize by remember(carlinkManager) { mutableStateOf(IntSize.Zero) }

    // Surface init for the adapter — uses container (display) dimensions. Idempotent across Surface
    // recreations; start() runs once per manager (hasStartedConnection gate).
    LaunchedEffect(surfaceState.surface, containerSize) {
        surfaceState.surface?.let { surface ->
            if (containerSize.width <= 0 || containerSize.height <= 0) return@let

            val adapterWidth = containerSize.width and 1.inv()
            val adapterHeight = containerSize.height and 1.inv()

            logInfo(
                "[CARLINK_RESOLUTION] Container size: ${adapterWidth}x$adapterHeight " +
                    "(surface: ${surfaceState.width}x${surfaceState.height})",
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
                            // OEM "Exit" icon pressed in CarPlay → overlay the dashboard cards on
                            // the live video. The CarPlay session keeps running; dismissing returns
                            // to projection (and flushes the codec so video resumes cleanly).
                            logInfo("[UI_NAV] Host UI requested — overlaying dashboard", tag = "UI")
                            showHostUi = true
                        }

                        override fun onPhoneTypeChanged(phoneType: PhoneType) {
                            logInfo("[UI_SURFACE] Phone type changed: $phoneType", tag = "UI")
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

    // True while the dashboard overlays a LIVE session (host-UI/"Exit" pressed mid-stream).
    val overlayingSession = showHostUi && !isLoading

    // Frozen frosted-glass backdrop: when the overlay opens, PixelCopy one frame of the video
    // surface; we draw it (blurred via Modifier.blur) behind the cards so the CarPlay feed
    // appears frozen + frosted. Null when not overlaying or if the copy fails (then the live
    // translucent bleedthrough shows instead). The SurfaceView can't be GPU-blurred live (it's
    // a hardware overlay), so a snapshot is the only way to get a real blur — see commit notes.
    var frostedBackdrop by remember(carlinkManager) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(overlayingSession) {
        frostedBackdrop =
            if (overlayingSession) captureSurfaceBitmap(surfaceState) else null
    }

    // Fullscreen-immersive: video fills the entire display behind the (hidden) system bars
    // and cutout. SafeArea (where CarPlay avoids placing UI) is emitted separately by
    // CarlinkManager/MessageSerializer, not from this file.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
            // Track display-bounds for the adapter OPEN resolution.
            val containerPx =
                with(density) {
                    IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                }
            LaunchedEffect(containerPx) {
                if (containerPx.width > 0 && containerPx.height > 0) {
                    containerSize = containerPx
                }
            }

            // Key the VideoSurface on manager identity so a Reset Connection rebuild drops the
            // AndroidView slot, disposing the old SurfaceView and releasing its HWC overlay plane;
            // a fresh SurfaceView is then inflated against the current window rect.
            key(carlinkManager) {
                VideoSurface(
                    modifier = Modifier.fillMaxSize(),
                    onSurfaceAvailable = { surface, width, height ->
                        logInfo("[UI_SURFACE] Surface available: ${width}x$height", tag = "UI")
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
                                containerSize.width,
                                containerSize.height,
                            )
                        }
                        true
                    },
                )
            }
        }

        // Frozen frosted-glass backdrop: the captured frame, blurred, drawn over the (now hidden)
        // live SurfaceView and under the dashboard cards. Only present while overlaying a session
        // and the PixelCopy succeeded; otherwise the live translucent bleedthrough shows.
        if (overlayingSession) {
            frostedBackdrop?.let { backdrop ->
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(SNAPSHOT_BLUR_RADIUS),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Dashboard shows when idle, OR overlaid on a live session when the OEM "Exit" icon was
        // pressed (showHostUi). When overlaying a session it gets a Return-to-CarPlay dismiss.
        if (isLoading || showHostUi) {
            CarlinkDashboard(
                carlinkManager = carlinkManager,
                statusText = statusText,
                onResetConnection = onResetConnection,
                onReturnToProjection =
                    if (overlayingSession) {
                        {
                            showHostUi = false
                            carlinkManager.recoverVideoFromOverlay()
                        }
                    } else {
                        null
                    },
            )
        }
        // System back dismisses the host-UI overlay and returns to projection.
        BackHandler(enabled = overlayingSession) {
            showHostUi = false
            carlinkManager.recoverVideoFromOverlay()
        }
    }
}

// ==================== Dashboard ====================

/** Landscape dashboard cards take this fraction of the available height (moderate, not full). */
private const val CARD_HEIGHT_FRACTION = 0.6f

/** Landscape dashboard cards take this fraction of the available width, centered (not edge-to-edge). */
private const val CARD_WIDTH_FRACTION = 0.7f

/** Frosted-glass overlay (live-session): light scrim over the blurred video so the glass lifts. */
private const val OVERLAY_SCRIM_ALPHA = 0.12f

/** Blur radius for the frozen frosted-glass backdrop snapshot. */
private val SNAPSHOT_BLUR_RADIUS = 32.dp

/**
 * Capture one frame of the video [VideoSurfaceState.surface] via PixelCopy, returning it as an
 * ImageBitmap (or null if the surface isn't ready / the copy fails). Used to freeze a frame for
 * the frosted-glass overlay backdrop. PixelCopy is async; this suspends until it completes.
 */
private suspend fun captureSurfaceBitmap(state: VideoSurfaceState): ImageBitmap? {
    val surface = state.surface ?: return null
    val w = state.width
    val h = state.height
    if (w <= 0 || h <= 0 || !surface.isValid) return null
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val ok =
        suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    surface,
                    bitmap,
                    { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (e: IllegalArgumentException) {
                // Surface not backed by a usable buffer (rare race on overlay open) — degrade
                // to the live translucent bleedthrough rather than crashing.
                if (cont.isActive) cont.resume(false)
            }
        }
    return if (ok) bitmap.asImageBitmap() else null
}

/**
 * Single-view dashboard shown when not projecting. Responsive to the display bounds:
 * landscape (e.g. gminfo 2400x960) lays the Adapter status + controls in a left column beside a
 * large Known-Devices card; portrait (e.g. 800x1280) stacks them in a scroll column. Black
 * background; day/night follows [MaterialTheme] (CarlinkTheme).
 */
@Composable
private fun CarlinkDashboard(
    carlinkManager: CarlinkManager,
    statusText: String,
    onResetConnection: (() -> Unit)?,
    onReturnToProjection: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val appVersion =
        remember {
            try {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "v${pi.versionName} (${pi.longVersionCode})"
            } catch (e: Exception) {
                ""
            }
        }
    val gap = 16.dp

    // Overlaying a live CarPlay session (host-UI/"Exit") → frosted glass: a translucent scrim
    // over the blurred video instead of opaque black, and semi-transparent card panels.
    val overlaying = onReturnToProjection != null

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (overlaying) Color.Black.copy(alpha = OVERLAY_SCRIM_ALPHA) else Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth >= maxHeight
            // Cards wrap their content (height); they don't stretch to fill the open space.
            // Top-aligned so they sit at the top with the black background around them.
            if (landscape) {
                // Cards take a moderate slice of the dashboard height (not full-screen) and are
                // centered vertically in the screen. The adapter card then has slack to center
                // its status block above the bottom-pinned controls. Tune CARD_HEIGHT_FRACTION.
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth(CARD_WIDTH_FRACTION)
                            .fillMaxHeight(CARD_HEIGHT_FRACTION)
                            .align(Alignment.Center),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    AdapterCard(
                        carlinkManager,
                        statusText,
                        onResetConnection,
                        Modifier.weight(0.24f).fillMaxHeight(),
                        stretchStatus = true,
                        onReturnToProjection = onReturnToProjection,
                    )
                    KnownDevicesCard(carlinkManager, Modifier.weight(0.76f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    AdapterCard(
                        carlinkManager,
                        statusText,
                        onResetConnection,
                        Modifier.fillMaxWidth(),
                        onReturnToProjection = onReturnToProjection,
                    )
                    KnownDevicesCard(carlinkManager, Modifier.fillMaxWidth())
                }
            }
        }

        // App version / code pill (bottom-end).
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd),
            shape = MaterialTheme.shapes.small,
            color = colorScheme.surfaceVariant,
        ) {
            Text(
                text = appVersion,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Combined adapter card: projection status (logo + "Connect Phone to: [name]" + live status
 * text) followed by the adapter control buttons (Reboot Adapter, Reset Connection). No section
 * title; no loading spinner.
 */
@Composable
private fun AdapterCard(
    carlinkManager: CarlinkManager,
    statusText: String,
    onResetConnection: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // When true (landscape, card stretched to full height) the status block takes the slack
    // above the controls and centers within it. When false (portrait, wrap-content) it packs
    // at the top as before.
    stretchStatus: Boolean = false,
    // Non-null only while the dashboard overlays a live CarPlay session (host-UI/"Exit" action).
    // When non-null the top "Return to CarPlay" button is enabled and returns to projection;
    // otherwise the button is shown greyed-out/disabled as a permanent placeholder.
    onReturnToProjection: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.liquidGlass(GlassShapes.Card, strong = true)) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // --- Return to CarPlay (glass pill; greyed out until reachable during a live
            // session via the host-UI overlay) ---
            GlassButton(
                onClick = { onReturnToProjection?.invoke() },
                enabled = onReturnToProjection != null,
                contentColor = colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
            ) {
                Text(
                    text = "Return to CarPlay",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // --- Status (centered in the space between the Return button and the first control) ---
            Column(
                modifier =
                    if (stretchStatus) {
                        Modifier.fillMaxWidth().weight(1f)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Connect Phone to:",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = carlinkManager.adapterName,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            // --- Controls: Reboot = glass (warning tint), Reset = solid vibrant accent (destructive) ---
            Spacer(modifier = Modifier.height(16.dp))
            GlassButton(
                onClick = { showRebootDialog = true },
                enabled = !isProcessing,
                contentColor = colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
            ) {
                Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Reboot Adapter", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(12.dp))

            ControlButton(
                label = "Reset Connection",
                icon = Icons.Default.Usb,
                severity = ButtonSeverity.DESTRUCTIVE,
                enabled = !isProcessing,
                isProcessing = isProcessing,
                onClick = {
                    logWarn("[UI_ACTION] Reset Connection clicked", tag = "UI")
                    isProcessing = true
                    val reset = onResetConnection
                    if (reset != null) {
                        reset()
                        isProcessing = false
                    } else {
                        scope.launch {
                            try {
                                carlinkManager.restart()
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                },
            )
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    tint = colorScheme.tertiary,
                )
            },
            title = { Text("Reboot Adapter?") },
            text = { Text("The adapter will restart. It will reconnect automatically in about 15 seconds.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logWarn("[UI_ACTION] Reboot Adapter confirmed", tag = "UI")
                        showRebootDialog = false
                        scope.launch(Dispatchers.IO) { carlinkManager.rebootAdapter() }
                    },
                ) {
                    Text("Reboot", color = colorScheme.tertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Known/paired devices — an instruction line, then the device cards (USB + wireless, from
 * PhonesTab) centered within the card body. The centered row shifts as devices populate and
 * scrolls horizontally when they overflow.
 */
@Composable
private fun KnownDevicesCard(
    carlinkManager: CarlinkManager,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().liquidGlass(GlassShapes.Card, strong = true)) {
        Column(modifier = Modifier.padding(20.dp).fillMaxSize()) {
            Text(
                text = "Tap a known device or remove it",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PhonesTabContent(carlinkManager)
            }
        }
    }
}

// ==================== Reusable card + button (moved from the removed SettingsScreen) ====================

private enum class ButtonSeverity { WARNING, DESTRUCTIVE }

/** Action button: swaps its icon for a spinner while [isProcessing]; color by [severity]. */
@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    severity: ButtonSeverity,
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor: Color
    val contentColor: Color
    when (severity) {
        ButtonSeverity.DESTRUCTIVE -> {
            containerColor = colorScheme.error
            contentColor = colorScheme.onError
        }
        ButtonSeverity.WARNING -> {
            containerColor = colorScheme.tertiaryContainer
            contentColor = colorScheme.onTertiaryContainer
        }
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isProcessing,
        modifier = modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) {
        AnimatedContent(
            targetState = isProcessing,
            transitionSpec = { (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut()) },
            label = "iconTransition",
        ) { processing ->
            if (processing) {
                LoadingSpinner(size = 24.dp, color = contentColor)
            } else {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ==================== Touch forwarding ====================

/** In-memory per-pointer touch record (normalized 0..1 coords + last action) for deduping MOVE spam. */
private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

/**
 * Handle touch on the SurfaceView. CarPlay multitouch (type 0x17): normalize to 0..1 of the
 * SurfaceView. A deadband suppresses sub-pixel MOVE spam.
 */
private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0 || containerWidth == 0 || containerHeight == 0) return

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)

    val action =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
            MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> MultiTouchAction.UP
            else -> return
        }

    val x = event.getX(pointerIndex) / surfaceWidth
    val y = event.getY(pointerIndex) / surfaceHeight

    when (action) {
        MultiTouchAction.DOWN -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        MultiTouchAction.MOVE -> {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = event.getX(i) / surfaceWidth
                val py = event.getY(i) / surfaceHeight
                activeTouches[id]?.let { existing ->
                    // Deadband ≈ 0.3% of the normalized surface — suppresses sub-pixel MOVE spam.
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                }
            }
        }

        MultiTouchAction.UP -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

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
