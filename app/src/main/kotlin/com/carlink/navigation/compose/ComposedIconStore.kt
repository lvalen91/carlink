package com.carlink.navigation.compose

import android.graphics.Bitmap
import android.os.Process
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.navigation.Iap2ManeuverData
import com.carlink.navigation.Iap2RouteData
import com.carlink.navigation.Iap2RouteParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Caches pre-composed maneuver icon bitmaps for the active route.
 *
 * Lifecycle:
 *   1. Route-calc arrives as `_iap2m` field in a NaviJSON message — [CarlinkManager] calls
 *      [populateFromIap2m]. This parses the burst synchronously, then dispatches the
 *      per-maneuver compose+render ([ManeuverComposer] + [IconBitmapRenderer]) to a
 *      background worker that fills [iconByKey] off the USB read thread.
 *   2. Per-step NaviJSON events arrive with (NaviManeuverType, NaviRoadName) —
 *      [NavigationStateManager.onNaviJson] calls [lookup] to get the pre-composed bitmap
 *      (or null → static fallback while the background fill is still in progress).
 *   3. Route ends (NaviStatus=0 flush) or new route-calc arrives — [clear] discards the
 *      whole cache.
 *
 * Architecture justification: the 0x5202 burst is the entire route in one message. Composing
 * the whole route once (off-thread) avoids per-step compose cost during driving. CRITICAL:
 * the compose MUST run on the background worker, not the caller's thread — the caller is the
 * single USB read loop that also reads video frames, and a long route (e.g. 183 maneuvers ≈
 * 14.6s of render on a CPU-constrained head unit) would otherwise stall video for the whole
 * burst. Per-maneuver cost is ~5ms on a desktop emulator but ~80ms on a saturated Intel Atom.
 *
 * Key design: store keyed by `(cpManeuverType, postManeuverRoadName)` — matches the fields
 * present in per-step NaviJSON events. Falls back to type-only match if road name is empty
 * or absent. The cursor-walk pattern (see [Iap2RouteData.findStepIndex]) handles duplicate
 * (type, road) pairs by remembering the last-matched index and walking forward from there.
 *
 * Thread safety: [populateFromIap2m] parses on the caller's (USB read) thread and publishes
 * [routeData] synchronously (its [currentRoute] consumer runs on the same thread immediately
 * after), but hands the expensive per-maneuver compose+render to a single background
 * [composeExecutor] thread so it never blocks USB video reads. [iconByKey] is swapped to
 * empty on populate and re-published wholesale by the worker when the route's bitmaps are
 * ready; [lookup] reads it lock-free and falls back to the static icon while it is empty.
 * A [composeGeneration] token (guarded by [publishLock]) supersedes an in-flight compose
 * when a re-route or [clear] arrives, so stale bitmaps can never overwrite the live route.
 *
 * Feature-flag gated: nothing wires the store into the cluster icon pipeline unless
 * [enabled] is true. While disabled, populate/lookup still happen for debug-log purposes
 * but the cluster keeps using the static-XML / AA bitmap paths.
 */
object ComposedIconStore {
    /**
     * Target bitmap dimensions in pixels. 512 chosen as a high enough resolution that
     * cluster downscales (most clusters render at 88-200dp = ~100-400px) produce clean
     * results. 512×512 ARGB_8888 = 1MB per icon × ~20 maneuvers/route = ~20MB cache —
     * acceptable for a non-CPU-constrained host phone. Bump to 1024 if any cluster's
     * native icon slot exceeds 512px. Truly-scalable delivery would need the ContentURI
     * render-on-demand provider pattern (see ClusterIconShimProvider) instead of
     * pre-rendered bitmaps, but bitmap is the only IPC-serializable format for CarIcon.
     */
    private const val ICON_SIZE_PX = 512

    /**
     * Master switch. When false, [lookup] returns null even if a bitmap exists.
     * Toggleable at runtime via [setEnabled] for A/B testing during validation.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(value: Boolean) {
        if (enabled != value) {
            enabled = value
            logInfo("[COMPOSER] enabled=$value", tag = Logger.Tags.NAVI)
        }
    }

    /** Optional sink for writing each composed bitmap to disk as a debug PNG. */
    @Volatile
    var debugSink: ((Iap2ManeuverData, Bitmap) -> Unit)? = null

    @Volatile
    private var routeData: Iap2RouteData? = null

    @Volatile
    private var iconByKey: Map<IconKey, Bitmap> = emptyMap()

    /** Cursor for the cursor-walk matcher — remembers the last matched maneuver index. */
    @Volatile
    private var cursorIndex: Int = 0

    /**
     * Single-thread background worker for the per-maneuver compose+render. Daemon so it never
     * holds up process shutdown. The job sets THREAD_PRIORITY_BACKGROUND so it yields to the
     * video feeder / decode / audio threads on CPU-constrained head units (gminfo37 Intel Atom).
     */
    private val composeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IconComposer").apply { isDaemon = true }
    }

    /**
     * Bumped on every [populateFromIap2m] / [clear]. A background compose job captures the
     * value at dispatch and aborts (discarding partial bitmaps) once it goes stale, so a
     * re-route supersedes a multi-second compose instead of letting it overwrite the new
     * route. The final publish is gated by this under [publishLock].
     */
    private val composeGeneration = AtomicLong(0)

    /** Serializes the worker's generation-check-and-publish against [populateFromIap2m] / [clear]. */
    private val publishLock = Any()

    /** Read-only accessor for NavigationStateManager (Tier C route-anchored state derivation). */
    internal fun currentRoute(): Iap2RouteData? = routeData

    private data class IconKey(
        val cpManeuverType: Int,
        val roadName: String,
    )

    /**
     * Parse an `_iap2m` hex string from a NaviJSON message and dispatch icon composition.
     *
     * Called by [com.carlink.CarlinkManager] when a `_iap2m` field is observed in a NaviJSON
     * payload, on the USB read thread. The parse (cheap) runs inline so [currentRoute] is
     * valid synchronously for the [NavigationStateManager.onNaviJson] call that follows on the
     * same thread; the expensive per-maneuver compose+render is handed to [composeExecutor] so
     * it never blocks USB video reads. On parse failure the prior cache is left in place.
     *
     * @return number of maneuvers dispatched for background compose, or null if parsing failed
     */
    fun populateFromIap2m(iap2mHex: String): Int? {
        val parsed = Iap2RouteParser.parse(iap2mHex) ?: return null
        if (parsed.maneuvers.isEmpty()) return null

        // On the caller's (USB read) thread: publish parse-side state only, then return.
        // routeData must be visible synchronously because NavigationStateManager.onNaviJson —
        // which reads currentRoute() — runs on this same thread immediately after. iconByKey is
        // dropped to empty so lookup() can't return a prior route's bitmap; the background
        // worker re-publishes it once the new route's icons are ready.
        val gen: Long
        synchronized(publishLock) {
            gen = composeGeneration.incrementAndGet()
            routeData = parsed
            iconByKey = emptyMap()
            cursorIndex = 0
        }

        val sink = debugSink
        composeExecutor.execute { composeRoute(parsed, gen, sink) }

        logInfo(
            "[COMPOSER] Parsed ${parsed.maneuvers.size} maneuvers; compose dispatched (gen=$gen)",
            tag = Logger.Tags.NAVI,
        )
        return parsed.maneuvers.size
    }

    /**
     * Background worker: compose+render one bitmap per UNIQUE (cpManeuverType, roadName) key —
     * the dedup happens BEFORE the expensive render, so work is O(unique) not O(total
     * maneuvers) — then publish the map iff this job is still the current generation. Runs on
     * [composeExecutor] at THREAD_PRIORITY_BACKGROUND.
     */
    private fun composeRoute(
        parsed: Iap2RouteData,
        gen: Long,
        sink: ((Iap2ManeuverData, Bitmap) -> Unit)?,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val newMap = HashMap<IconKey, Bitmap>(parsed.maneuvers.size)
        var composed = 0
        for (maneuver in parsed.maneuvers) {
            // Superseded by a newer route or a clear()? Drop partial work and stop.
            if (composeGeneration.get() != gen) {
                recycleAll(newMap)
                logNavi { "[COMPOSER] compose gen=$gen superseded after $composed icons — aborted" }
                return
            }
            val key = IconKey(maneuver.cpManeuverType, maneuver.postManeuverRoadName)
            if (newMap.containsKey(key)) continue // dedup BEFORE render — skip already-built icon
            try {
                val composedIcon = ManeuverComposer.compose(maneuver)
                val bmp = IconBitmapRenderer.render(ICON_SIZE_PX, composedIcon)
                newMap[key] = bmp
                sink?.invoke(maneuver, bmp)
                composed++
            } catch (e: NoSuchElementException) {
                // Compose path has no entry for this (cp, geometry) — typically high-exit-ordinal
                // roundabouts beyond the AppleManeuverPaths map. Not an error; lookup returns null
                // and ManeuverMapper falls through to the static AVD via
                // ManeuverIconRenderer.drawableForManeuver as a last-resort safety net.
                logNavi { "[COMPOSER] no path data for idx=${maneuver.index} cpType=${maneuver.cpManeuverType}: ${e.message}" }
            } catch (e: Throwable) {
                Logger.w(Logger.Tags.NAVI, "[COMPOSER] failed for idx=${maneuver.index} cpType=${maneuver.cpManeuverType}: ${e.message}")
            }
        }
        // Publish iff still current — atomic against populate()/clear() bumping the generation.
        synchronized(publishLock) {
            if (composeGeneration.get() != gen) {
                recycleAll(newMap)
                logNavi { "[COMPOSER] compose gen=$gen superseded at publish — discarded" }
                return
            }
            iconByKey = newMap
        }
        logInfo(
            "[COMPOSER] Composed $composed unique icons / ${parsed.maneuvers.size} maneuvers (gen=$gen)",
            tag = Logger.Tags.NAVI,
        )
    }

    /** Recycle every bitmap in a not-yet-published map to free native memory immediately. */
    private fun recycleAll(map: Map<IconKey, Bitmap>) {
        map.values.forEach { if (!it.isRecycled) it.recycle() }
    }

    /**
     * Look up a pre-composed bitmap for the per-step event's (cpManeuverType, roadName) pair.
     *
     * Returns null when:
     *   - [enabled] is false (feature flag off)
     *   - the cache is empty (no route active or population failed)
     *   - no maneuver in the route matches the given key
     *
     * Uses the cursor-walk pattern: searches from [cursorIndex] forward, advances the
     * cursor on a match. Handles re-routes (cursor reset on next populateFromIap2m) and
     * duplicate (type, road) pairs (cursor walks past them).
     */
    fun lookup(cpManeuverType: Int, roadName: String?): Bitmap? {
        if (!enabled) return null
        val route = routeData ?: return null
        // Find the matching maneuver index using the LOOSE matcher (post-road OR instruction-text).
        // v6.1 Tier C cursor body sets state.roadName from `route.maneuvers[cursor].instructionText`
        // (the long form, e.g. "At the roundabout, take the 3rd exit onto E 116th St"), not the
        // shorter `postManeuverRoadName` ("E 116th St"). The strict matcher would miss this. Cursor
        // walks forward from prior cursor index; wraps to 0 to support backward-jumps from reroutes.
        val foundIdx = route.findStepIndexLoose(cpManeuverType, roadName, searchStartIndex = cursorIndex)
            ?: route.findStepIndexLoose(cpManeuverType, roadName, searchStartIndex = 0)
            ?: return null

        cursorIndex = foundIdx
        val maneuver = route.maneuvers[foundIdx]
        val bmp = iconByKey[IconKey(maneuver.cpManeuverType, maneuver.postManeuverRoadName)]
        logNavi {
            "[COMPOSER] Lookup matched idx=$foundIdx cpType=$cpManeuverType road=\"$roadName\" -> ${if (bmp != null) "bitmap[${bmp.width}x${bmp.height}]" else "no bitmap (compose failed earlier)"}"
        }
        return bmp
    }

    /** Wipe the cache. Called on NaviStatus=0 flush or before re-populating. */
    fun clear() {
        synchronized(publishLock) {
            // Bump the generation so any in-flight background compose aborts / declines to publish.
            composeGeneration.incrementAndGet()
            if (iconByKey.isNotEmpty()) {
                // Recycle bitmaps to release native memory immediately rather than waiting for GC.
                iconByKey.values.forEach { if (!it.isRecycled) it.recycle() }
                logInfo("[COMPOSER] Cleared ${iconByKey.size} icons", tag = Logger.Tags.NAVI)
            }
            routeData = null
            iconByKey = emptyMap()
            cursorIndex = 0
        }
    }
}
