package com.carlink.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * SurfaceView for H.264 decoding via HWC overlay (lower latency than TextureView).
 * Forwards Surface lifecycle and touch events to a [Callback].
 *
 * Threading: SurfaceHolder.Callback is delivered on the main thread (Android contract).
 * The [callback] property is a plain mutable var with no @Volatile/synchronization;
 * reads from other threads may see stale values, and reassigning mid-lifecycle can
 * produce orphaned create-without-destroy (or destroy-without-create) pairs for
 * the replaced consumer. Assign [callback] from the main thread only, ideally before
 * the surface is attached to the window.
 */
class VideoSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        /**
         * Consumer contract for the surface lifecycle and touch delegation.
         *
         * Lifecycle ordering (see [surfaceChanged] for the deferred-fire contract):
         *   onSurfaceCreated (deferred until first surfaceChanged with real dimensions)
         *   -> onSurfaceChanged* (zero or more subsequent size/format changes)
         *   -> onSurfaceDestroyed.
         *
         * Idempotency: [onSurfaceDestroyed] may be invoked more than once per logical
         * surface because VideoSurface.kt's DisposableEffect.onDispose also calls it
         * on Compose teardown. Consumers MUST treat destroy as idempotent.
         *
         * Edge case: if [surfaceChanged] never arrives after [surfaceCreated]
         * (e.g. zero-size window), [onSurfaceCreated] is never fired and the
         * consumer never receives the Surface. Callers that need a guaranteed
         * creation signal must handle this out-of-band.
         */
        interface Callback {
            /** Fired once the surface exists AND has non-zero dimensions. */
            fun onSurfaceCreated(
                surface: Surface,
                width: Int,
                height: Int,
            )

            /** Fired on subsequent size/format/HDR changes after the initial create. */
            fun onSurfaceChanged(
                width: Int,
                height: Int,
            )

            /** Fired on surface teardown. May be called more than once; must be idempotent. */
            fun onSurfaceDestroyed()

            /** Touch delegation target; returns true if the event was consumed. */
            fun onTouchEvent(event: MotionEvent): Boolean
        }

        /**
         * Consumer callback. Main-thread only; no volatile/synchronization.
         * See class KDoc for threading and reassignment hazards.
         */
        var callback: Callback? = null

        /**
         * One-shot latch — true after [surfaceCreated] fires, cleared on the first
         * [surfaceChanged] (which is when [Callback.onSurfaceCreated] is delivered).
         * NOTE: the name is misleading — this is NOT a "surface is currently alive"
         * flag. Once cleared, the surface is still valid until [surfaceDestroyed];
         * the flag only tracks whether the initial onSurfaceCreated callback is
         * still pending delivery. The public getter is effectively vestigial
         * (no in-tree reader references it; VideoSurface.kt never reads it).
         * Left public to avoid changing the external API surface.
         */
        var isSurfaceCreated: Boolean = false
            private set

        init {
            holder.addCallback(this)
            isFocusable = true
            isFocusableInTouchMode = true
            logInfo("[VIDEO_SURFACE_VIEW] Initialized", tag = "UI")
        }

        // Touch delegation: returns exactly what the callback returns (including false),
        // which skips super.onTouchEvent / click handling. performClick() is intentionally
        // NOT wired — this view is a video surface, not a clickable control, so accessibility
        // click semantics don't apply. @Suppress silences the lint warning that would
        // otherwise demand performClick() wiring.
        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = callback?.onTouchEvent(event) ?: super.onTouchEvent(event)

        /**
         * Stage 1 of the deferred-fire state machine: mark the initial-create callback
         * as pending. We intentionally do NOT invoke [Callback.onSurfaceCreated] here
         * because width/height are not yet known — they arrive with [surfaceChanged].
         */
        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceCreated = true
            logInfo("[VIDEO_SURFACE_VIEW] Surface created", tag = "UI")
        }

        /**
         * Stage 2 of the deferred-fire state machine:
         *   - First call after [surfaceCreated]: clears the latch and delivers
         *     [Callback.onSurfaceCreated] with real dimensions.
         *   - Subsequent calls: delivers [Callback.onSurfaceChanged] for
         *     rotation/resize/HDR/format changes.
         *
         * This non-obvious reordering is the entire reason [isSurfaceCreated] exists:
         * it guarantees the consumer's first callback carries valid width/height.
         */
        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            // Known log-spam hazard: fires on every rotation/resize/HDR/format change.
            // Acceptable at INFO for debugging surface sizing; switch to logDebugOnly if noisy.
            logInfo("[VIDEO_SURFACE_VIEW] Surface: ${width}x$height", tag = "UI")

            if (isSurfaceCreated) {
                callback?.onSurfaceCreated(holder.surface, width, height)
            } else {
                callback?.onSurfaceChanged(width, height)
            }
        }

        /**
         * Stage 3: teardown. Clears the latch (in case destroy arrives before the
         * first surfaceChanged had a chance to fire) and notifies the consumer.
         * Note: VideoSurface.kt also fires onSurfaceDestroyed from its
         * DisposableEffect.onDispose, so consumers may see this twice — see [Callback].
         */
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            logWarn("[VIDEO_SURFACE_VIEW] Surface destroyed", tag = "UI")
            isSurfaceCreated = false
            callback?.onSurfaceDestroyed()
        }
    }
