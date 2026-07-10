package com.carlink.ui.components

import android.view.MotionEvent
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * Compose wrapper for VideoSurfaceView. Uses HWC overlay for low-latency rendering.
 *
 * See VideoSurfaceView.kt for the underlying SurfaceHolder.Callback deferred-fire state
 * machine (pending-surface bookkeeping, idempotent teardown, re-create semantics).
 *
 * CALLER CONTRACT (load-bearing — the factory block below runs exactly once):
 *  - onSurfaceAvailable / onSurfaceDestroyed / onSurfaceSizeChanged / onTouchEvent are
 *    captured by the Callback object on first composition. There is no update={} block
 *    and no rememberUpdatedState indirection, so non-stable lambdas passed on later
 *    recompositions are SILENTLY IGNORED. Callers MUST hoist these lambdas with
 *    remember {} (or rememberUpdatedState via an outer wrapper) to avoid stale captures.
 *  - onSurfaceDestroyed is invoked from TWO sources: (a) DisposableEffect.onDispose when
 *    the composable leaves the tree, and (b) VideoSurfaceView.Callback.onSurfaceDestroyed
 *    when the underlying SurfaceHolder tears down. Both can fire for a single teardown.
 *    CarlinkManager.kt:1132-1145 handles this idempotently (null-checks pendingSurface /
 *    videoSurface, idempotent renderer.stop()). Do not add de-dup here — the downstream
 *    handler is the source of truth.
 */
@Composable
fun VideoSurface(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    // Optional: when null, SurfaceHolder size changes are SILENTLY DROPPED (see `?.invoke`
    // at the callback site below). CarlinkManager needs size changes for surface-resize
    // tier logic, so production wiring must always pass a non-null lambda here.
    onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null,
    // Optional: when null, onTouchEvent returns false and VideoSurfaceView's delegation
    // skips super.onTouchEvent — i.e. all touches on the surface are swallowed. Pass a
    // non-null lambda if the surface should be interactive.
    onTouchEvent: ((MotionEvent) -> Boolean)? = null,
) {
    DisposableEffect(Unit) {
        logInfo("[VIDEO_SURFACE] VideoSurface composable created", tag = "UI")
        onDispose {
            logWarn("[VIDEO_SURFACE] VideoSurface composable disposed", tag = "UI")
            // DOUBLE-FIRE SITE #1: composable disposal. See class KDoc — may also fire
            // via Callback.onSurfaceDestroyed below. Downstream is idempotent.
            onSurfaceDestroyed()
        }
    }

    AndroidView(
        // NOTE: fillMaxSize() is appended AFTER the caller's modifier, so any caller
        // size constraints (e.g. MainScreen's requiredHeight surfaceModifier) apply
        // first and fillMaxSize() fills within that bounded region. Surprising but
        // intentional — do not reorder.
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            logInfo("[VIDEO_SURFACE] Creating VideoSurfaceView", tag = "UI")
            VideoSurfaceView(context).apply {
                // STALE-LAMBDA HAZARD: this Callback closes over the lambdas captured at
                // first composition. There is no update={} block, so later recompositions
                // with different lambda instances have no effect. See class KDoc contract.
                callback =
                    object : VideoSurfaceView.Callback {
                        override fun onSurfaceCreated(
                            surface: Surface,
                            width: Int,
                            height: Int,
                        ) {
                            logInfo("[VIDEO_SURFACE] SurfaceView.onSurfaceCreated: ${width}x$height", tag = "UI")
                            onSurfaceAvailable(surface, width, height)
                        }

                        override fun onSurfaceChanged(
                            width: Int,
                            height: Int,
                        ) {
                            logInfo("[VIDEO_SURFACE] SurfaceView.onSurfaceChanged: ${width}x$height", tag = "UI")
                            // SILENT-DROP SITE: if caller passed null, size changes are
                            // discarded. Combined with onSurfaceAvailable writing w/h, this
                            // means a caller that wires onSurfaceAvailable but leaves
                            // onSurfaceSizeChanged null will see width/height frozen at
                            // creation values — a wiring footgun. See param KDoc above.
                            onSurfaceSizeChanged?.invoke(width, height)
                        }

                        override fun onSurfaceDestroyed() {
                            logWarn("[VIDEO_SURFACE] SurfaceView.onSurfaceDestroyed", tag = "UI")
                            // DOUBLE-FIRE SITE #2: SurfaceHolder teardown. Pairs with the
                            // DisposableEffect.onDispose site above; CarlinkManager.kt
                            // dedupes via null-checked pendingSurface/videoSurface.
                            onSurfaceDestroyed()
                        }

                        // Null caller -> returns false -> VideoSurfaceView's delegation
                        // skips super.onTouchEvent, swallowing the touch. See param KDoc.
                        override fun onTouchEvent(event: MotionEvent): Boolean = onTouchEvent?.invoke(event) ?: false
                    }
            }
        },
    )
}

/**
 * Optional externally-driven state holder for a [VideoSurface].
 *
 * NOT automatically wired: the [VideoSurface] composable does NOT accept a state holder
 * parameter. Callers create an instance via [rememberVideoSurfaceState] and MUST manually
 * wire it by passing its methods as the composable's lambda params, e.g.:
 *   val state = rememberVideoSurfaceState()
 *   VideoSurface(
 *       onSurfaceAvailable = state::onSurfaceAvailable,
 *       onSurfaceDestroyed = state::onSurfaceDestroyed,
 *       onSurfaceSizeChanged = state::onSurfaceSizeChanged,
 *   )
 *
 * Live usage: MainScreen.kt:86. Reads like orphaned API but is intentionally decoupled —
 * callers that don't need observable surface state can ignore it entirely.
 *
 * WIRING REQUIREMENT: if you wire [onSurfaceAvailable] but leave [onSurfaceSizeChanged]
 * null on the composable, [width]/[height] here will stay at their creation values
 * (see VideoSurface's onSurfaceChanged silent-drop note).
 */
class VideoSurfaceState {
    var surface: Surface? by mutableStateOf(null)
        private set

    var width: Int by mutableIntStateOf(0)
        private set

    var height: Int by mutableIntStateOf(0)
        private set

    fun onSurfaceAvailable(
        surface: Surface,
        w: Int,
        h: Int,
    ) {
        this.surface = surface
        width = w
        height = h
    }

    fun onSurfaceDestroyed() {
        surface = null
    }

    fun onSurfaceSizeChanged(
        w: Int,
        h: Int,
    ) {
        width = w
        height = h
    }
}

/** Remember a [VideoSurfaceState] scoped to the current composition. See [VideoSurfaceState] for wiring. */
@Composable
fun rememberVideoSurfaceState(): VideoSurfaceState = remember { VideoSurfaceState() }
