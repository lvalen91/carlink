@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.carlink.media

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.carlink.BuildConfig
import com.carlink.MainActivity
import com.carlink.util.LogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
private const val TAG = "CARLINK_MEDIA"

/**
 * Cluster / system consumers that read album-art `content://` URIs from outside the
 * MediaController IPC channel (e.g. through their own ContentProvider/RHMI path) and
 * therefore don't receive the framework's implicit per-controller URI grant. We
 * pre-grant read permission so their `ContentResolver.openFileDescriptor()` succeeds.
 * Unknown packages on a given build are silently ignored by [grantUriToConsumers].
 *
 * Identified from firmware-level analysis of GM AAOS 2024 Silverado ICE
 * (see mempalace gminfo37/projection drawers on GMCarMediaService / ClusterService
 * and the IClusterHmi widget API): GM's RHMI cluster re-marshals MediaMetadata
 * through its own `gm.media.provider` ContentProvider, which strips the framework's
 * implicit grant — without an explicit grant the cluster's getBitmap() throws
 * SecurityException.
 */
private val URI_GRANT_CONSUMERS = listOf(
    "com.gm.rhmi",
    "com.gm.cluster",
    "com.gm.gmaudio.tuner",
    "com.gm.gmaudio.server",
    "com.gm.car.media.gmcarmediaservice",
    "com.android.car.media",
    "com.android.car.cluster",
    "com.android.car.cluster.home",
    "com.android.systemui",
)

/**
 * MediaSessionManager — Media3-backed MediaSession lifecycle + metadata/state mirror
 * for AAOS media source integration.
 *
 * PURPOSE
 * Exposes the Carlink app as a selectable media source on Android Automotive OS (AAOS)
 * using the modern androidx.media3 stack. The app does not play audio locally — the
 * connected phone plays over USB; this class only mirrors the phone's state + forwards
 * transport-control gestures back to the USB adapter.
 *
 * ARCHITECTURE
 * ```
 * CarPlay/AA projection → USB adapter → CarlinkManager.processMediaMetadata
 *                                                      │
 *                                                      ▼
 *                                       MediaSessionManager
 *                                                      │
 *                               ┌──────────────────────┼─────────────────────┐
 *                               │                      │                     │
 *                               ▼                      ▼                     ▼
 *                       UsbAdapterPlayer      AlbumArtCache          androidx.media3
 *                       (SimpleBasePlayer)   (FileProvider URIs)     .session.MediaSession
 *                               │                      │                     │
 *                               │                      └──→ setArtworkUri    │
 *                               │   ByteArray ────────────→ setArtworkData   │
 *                               │                                            │
 *                               └──→ handleSetPlayWhenReady / handleSeek ────┘
 *                                     (Player routes via Callback)
 *                                                      │
 *                                                      ▼
 *                                           MediaControlCallback
 *                                     (CarlinkManager.sendKey(CommandMapping))
 * ```
 *
 * THREAD SAFETY
 * [MediaSession] is not inherently thread-safe; [UsbAdapterPlayer] is pinned to the
 * main Looper. [updateMetadata] / [updatePlaybackState] / [setStateStopped] /
 * [setStateConnecting] are called from the USB read thread; [initialize] / [release]
 * from the main/lifecycle thread. All mutations of session + internal dedup state are
 * wrapped in `synchronized(sessionLock)`. UsbAdapterPlayer internally posts state
 * updates back to the main looper before calling [Player.invalidateState].
 *
 * DUAL-CARRIER ALBUM ART (preserved from legacy MediaSessionCompat path)
 * Media3's [MediaMetadata] supports both [MediaMetadata.Builder.setArtworkData]
 * (raw bytes, decoded by the consumer) and [MediaMetadata.Builder.setArtworkUri]
 * (`content://` URI via FileProvider). We populate BOTH:
 * - setArtworkData is the PRIMARY carrier — raw JPEG/PNG bytes from the USB
 *   MEDIA_DATA frame flow straight through (no local decode; Media3 consumers decode
 *   on their side). Guaranteed to render even if the URI path fails.
 * - setArtworkUri is ADDITIVE — only set after [AlbumArtCache.put] confirms the
 *   backing file is on disk. Consumers that prefer URIs (e.g. GM RHMI) pick this up.
 *
 * WHY DUAL CARRIER: GM's GMCarMediaService reads metadata via MediaControllerCompat
 * (gm_apks/GMCarMediaService/sources/c0/g.java:111 new MediaControllerCompat(ctx, token)
 * → getMetadata() at c0/g.java:261). An unresolvable artworkUri can nullify the bundle.
 * Inline bytes guarantee text + art always render.
 *
 * SEEK DEDUP (2000 ms threshold)
 * USB MEDIA_DATA position frames arrive ~60–100 ms apart; ~95% are position-only ticks
 * (CarlinkManager.kt:2441) which AAOS already extrapolates. Dedup pushes to the Player
 * only on play/pause transition or when position drifts >2 s from the extrapolated
 * value. Verified 2026-04-20 POTATO session 154850: all surviving "seek" events are
 * genuine track-changes/scrubs, zero false positives across ~6000 MEDIA_DATA frames.
 *
 * NO EXPLICIT AUDIO ATTRIBUTES / NO setPlaybackToLocal
 * Intentionally do not configure audio attributes on the Player. Committing to
 * `CONTENT_TYPE_MUSIC` (equivalent of the legacy `setPlaybackToLocal`) would hard-route
 * hardware volume keys to the MEDIA volume group on GM AAOS, bypassing the focus-aware
 * fallback for SIRI / PHONE_CALL / navigation-prompt contexts. The default AudioAttributes
 * preserves AAOS focus routing.
 *
 * MEDIA3 SESSION "ACTIVE" STATE (observations as of 2026-05-03; re-verify on upgrade)
 * Media3's [MediaSession] does not expose a public `isActive` API surface. Based on
 * Media3 1.10.0 source as read on 2026-05-03 (around MediaSessionLegacyStub.start()),
 * the platform-side android.media.session.MediaSession.isActive flag appeared to be set
 * to true ONCE inside the session start path, with no observed `setActive(false)` call
 * before session release. Within that observation, Player state and timeline emptiness
 * did not appear to toggle the platform active flag — but this is an internal Media3
 * detail without a public contract guarantee, so re-verify when upgrading Media3.
 *
 * AAOS Media Center / GMCarMediaService / cluster appear to gate "available media source"
 * visibility on a combination of (a) session metadata payload and (b) playlist (timeline)
 * presence — both of which ARE driven by [Player.State]. Empirical mapping observed on
 * GM AAOS 2024 Silverado at 2026-05-03 only:
 *   Empty timeline + STATE_IDLE                            → switcher drops Carlink (regression)
 *   Non-empty timeline + STATE_READY + playWhenReady=false → source visible, paused
 *   Non-empty timeline + STATE_BUFFERING + playWhenReady=true → source is "preparing"
 * These mappings were observed on a single firmware build; treat them as field findings,
 * not specifications. [setProjectionActive] / [setInactive] choose the [Player.State]
 * combination that produced the desired switcher behavior on the observed firmware.
 *
 * OBSERVED OS BEHAVIOR — stale homescreen Media card after force-stop
 * -------------------------------------------------------------------
 * The findings below are point-in-time observations as of 2026-05-03 from the AOSP
 * source tree readings noted, plus on-device repros on the firmware/builds named.
 * Treat them as field findings, not as a contract guarantee. AOSP and OEM firmware
 * change frequently; re-verify on upgrade or before relying on these for design
 * decisions in newer code.
 *
 * Reproduced (under the test conditions noted) on:
 *   - zeno.carlink (this Media3 projection app) — 2026-04-21.
 *   - Apple Music 5.2.1 (com.apple.android.music), a first-party vendor app using
 *     standard MediaBrowserService + MediaSession — 2026-04-21, same symptom.
 * The repro on a first-party vendor app on the same date suggests the issue is not
 * specific to this app's code; on that basis we did not pursue app-side workarounds.
 * The same failure plausibly affects the GM WidgetPanel media widget and the GM
 * Cluster media panel (they appear to consume MediaSession through the same
 * plumbing), but that has not been independently confirmed.
 *
 * Symptom (as observed): force-stopping the app and relaunching (with the data
 *   source — phone, streaming service, etc. — still producing content) left
 *   CarLauncher's homescreen Media card showing only the app name with no
 *   metadata/artwork. The new session token was ACTIVE; CarMediaService dumpsys
 *   reported `current playback media component: <package>` and `media playback
 *   state: 3` (PLAYING); the card stayed blank.
 *
 * Apparent root cause (per AOSP source read on 2026-05-03; line numbers and class
 * paths may drift in newer branches — grep symbol names rather than line numbers
 * when re-verifying):
 *   - The relevant ViewModel observed in the read was
 *     `com.android.car.media.common.playback.PlaybackViewModel` in the
 *     `car-media-common` library (CarLauncher itself appeared to consume it via
 *     `com.android.car.carlauncher.homescreen.audio.MediaViewModel`). On the read
 *     date PlaybackViewModel binds a `MediaControllerCompat` to the original
 *     session token (around PlaybackViewModel.java:156) and on `onSessionDestroyed`
 *     nulls the controller (around line 303-308) with an in-tree TODO at line 306
 *     ("consider keeping track of orphaned callbacks in case they are resurrected"),
 *     which appears to acknowledge an unhandled gap.
 *   - The controller did not appear to be replaced on the same-package
 *     inactive→active transition because three layered equality guards short-
 *     circuited the rebind path on the AOSP read: `CarMediaService.setPrimaryMediaSource`
 *     early-returned when the new ComponentName equaled the current one (around
 *     line 1311); upstream `MediaSourceViewModel.updateModelState` early-returned
 *     on `MediaSource.equals` (which appeared to compare on ComponentName only);
 *     and `PlaybackViewModel.onMediaBrowsingStateChanged` itself early-returned on
 *     equal BrowsingState. Within those observations, a freshly minted MediaSession
 *     token for the same package never caused `onMediaBrowsingStateChanged` to
 *     re-fire, and the nulled controller stayed nulled.
 *   - CarMediaService also appeared to persist "last playback primary" to a
 *     SharedPreferences file named `com.android.car.media.car_media_service`
 *     (CarMediaService.java around lines 118-121, 535 in the read). The on-disk
 *     path was not confirmed by this audit; an earlier comment here pointed at
 *     `/data/user/0/com.android.car/` which we are flagging as unverified.
 *     On boot, `initUser` (around line 450) appeared to read that preference and
 *     re-assert the selection via `notifyListeners` (around line 487), but the
 *     same equality guards above appeared to prevent any actual rebind — so the
 *     symptom observed was a CarLauncher card with no controller bound, not a
 *     card bound to a stale token. (An earlier wording in this KDoc described it
 *     as the latter; corrected here to match the AOSP source as read.)
 *
 * Workarounds tried (all failed):
 *   - Two-step source switch (select another source, switch back).
 *   - Self-MediaBrowser rebind / MAIN+APP_MUSIC self-intent.
 *   - Making the session INACTIVE-by-default until PLUGGED event (this current design).
 *   - Emulator restart.
 *   - Force-stop + relaunch with fresh session token.
 *
 * Only workaround that succeeded in our 2026-05-03 testing: full package uninstall +
 * reinstall + emulator restart. `adb install -r` (replace) did not clear the cached
 * state in the runs observed; the install needed to remove the package to (apparently)
 * cause CarMediaService's SharedPreferences to purge the stored primary-source
 * reference. This is a behavioral observation, not a documented contract — different
 * AOSP/firmware revisions may behave differently, and a future Media3 or AAOS update
 * could make a less-invasive workaround viable.
 *
 * Implication: the [setProjectionActive]/[setInactive] toggle below is architecturally
 * correct (session should not squat as playback-primary when the adapter is idle) but
 * did not appear to resolve the stale-card behavior in our 2026-05-03 testing —
 * resolution likely requires a fix in `car-media-common` PlaybackViewModel (e.g.
 * handling orphaned-controller resurrection per the in-tree TODO observed at
 * PlaybackViewModel.java:306, or loosening the ComponentName-only equality in the
 * observed `MediaSource.equals` so same-package new-token transitions notify
 * listeners). This depends on AOSP behavior that may be addressed in newer releases;
 * re-test after AOSP updates before assuming the workaround is still necessary.
 *
 * OBSERVED BACKWARDS COMPATIBILITY WITH GM AAOS OBSERVERS (as of 2026-05-03)
 * Media3 [MediaSession] appears to register a platform-level
 * `android.media.session.MediaSession` for legacy observers. Decompiled
 * GMCarMediaService on the firmware tested (c0/g.java:105
 * MediaSessionCompat.Token.a(..getSessionToken()), :176
 * getSystemService("media_session"), :241 android.media.session.MediaController
 * .registerCallback) appeared to bind via MediaBrowserCompat/MediaControllerCompat
 * shims over platform APIs — we did not see direct androidx usage. On that basis the
 * Media3 migration appears transparent to the ClusterService → IClusterHmi widget
 * pipeline on the build tested. Re-verify on different GM firmware revisions.
 */
class MediaSessionManager(
    private val context: Context,
    private val logCallback: LogCallback,
) {

    /** Routed transport controls from AAOS / steering wheel / cluster. Forwarded by
     *  [UsbAdapterPlayer.Callback] → this callback → CarlinkManager.sendKey(). */
    interface MediaControlCallback {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSkipToNext()
        fun onSkipToPrevious()
    }

    private var player: UsbAdapterPlayer? = null
    private var mediaSession: MediaLibrarySession? = null

    // @Volatile: the setter ([setMediaControlCallback]) is public and release() clears the
    // field under sessionLock while playerCallback trampoline reads it from the main thread
    // (UsbAdapterPlayer posts to main before invoking). @Volatile gives a well-defined
    // happens-before on cross-thread writes/reads without requiring callers to take the
    // lock. In practice CarlinkManager sets it once during initialize() and clears via
    // release(), so the field is only written from the main thread, but @Volatile codifies
    // the contract so a future off-main caller won't silently race.
    @Volatile
    private var mediaControlCallback: MediaControlCallback? = null

    // Current reported state — cached so updatePlaybackState can supply duration with
    // each Player update (duration arrives on metadata frames, not playback frames).
    private var currentDurationMs: Long = 0L

    // Dedup state for playback-state pushes. Matches the legacy PlaybackStateCompat
    // dedup — SimpleBasePlayer has its own equality-based suppression, but this
    // outer filter cuts USB-rate updates (60–100 ms) to at most one per state/seek.
    private var lastPushedPlaying: Boolean? = null
    private var lastPushedPositionMs: Long = 0L
    private var lastPushedTimeNanos: Long = 0L

    // Tracks the duration last pushed to UsbAdapterPlayer.durationMs. Without this,
    // the dedup gate below suppresses every updatePlaybackState after the first
    // state-change push, so a duration that arrived AFTER that first push (e.g.,
    // first MEDIA_DATA tick lacked MediaSongDuration) never reaches the player.
    // Empirical bug observed 2026-05-02 on Silverado gminfo37: [SNAPSHOT] reported
    // dur=TIME_UNSET while [MEDIA_DATA] had dur=208341ms for ~3 minutes until the
    // next track change forced a push.
    private var lastPushedDurationMs: Long = 0L

    /** Position must drift more than this from the AAOS-extrapolated value to be
     *  considered a genuine user seek. 2 s absorbs USB/cluster jitter without suppressing
     *  seeks. Validated 2026-04-20 POTATO session 154850 — all surviving seek events were
     *  real track-changes / scrubs. */
    private val seekThresholdMs: Long = 2_000L

    // Album art cache — produces FileProvider content:// URIs for the URI path of the
    // dual-carrier. Decoding-to-Bitmap (legacy decodeDisplayIcon) is NOT used on the
    // Media3 path: raw ByteArray goes straight into MediaMetadata.setArtworkData and
    // consumers decode on their side, saving the USB thread the decode cost.
    private val albumArtCache = AlbumArtCache(context)

    // Last-published art dedup + dual-carrier state.
    private var lastArtHash: Int = 0
    private var lastArtUri: Uri? = null
    private var lastArtBytes: ByteArray? = null
    private var lastArtJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()

    // Periodic state-snapshot diagnostic. Active only on debug builds (see [initialize]);
    // emits one [SNAPSHOT] line every [SNAPSHOT_INTERVAL_MS] showing player + controller
    // state so a logcat capture can correlate USB events against published session state
    // without needing `dumpsys media_session` interleaved.
    private var snapshotJob: Job? = null

    /**
     * Diagnostic [Player.Listener] attached to [UsbAdapterPlayer] in [initialize]. Mirrors
     * the deltas Media3 derives from the player's [State] (transitions, play-when-ready,
     * is-playing, errors) so a debug-build logcat capture shows what controllers actually
     * observe — without this we only see what we POSTED, not what Media3 PUBLISHED.
     *
     * Callbacks run on the application looper per [Player] contract; gated on
     * [BuildConfig.DEBUG] to keep release builds zero-cost.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (BuildConfig.DEBUG) log("[MEDIA_SESSION] [LISTENER] onPlaybackStateChanged $playbackState")
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (BuildConfig.DEBUG) log("[MEDIA_SESSION] [LISTENER] onIsPlayingChanged $isPlaying")
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (BuildConfig.DEBUG) log("[MEDIA_SESSION] [LISTENER] onPlayWhenReadyChanged $playWhenReady reason=$reason")
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (BuildConfig.DEBUG) log("[MEDIA_SESSION] [LISTENER] onMediaItemTransition id=${mediaItem?.mediaId} reason=$reason")
        }
        override fun onMediaMetadataChanged(metadata: MediaMetadata) {
            if (BuildConfig.DEBUG) {
                log(
                    "[MEDIA_SESSION] [LISTENER] onMediaMetadataChanged title=${metadata.title} " +
                        "artist=${metadata.artist} album=${metadata.albumTitle} " +
                        "artBytes=${metadata.artworkData?.size ?: 0} artUri=${metadata.artworkUri}",
                )
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            log("[MEDIA_SESSION] [LISTENER] onPlayerError ${error.errorCodeName}: ${error.message}")
        }
    }

    /**
     * [MediaLibrarySession.Callback] for AAOS Media Center browse-tree queries. carlink_native
     * is a projection app — the browse tree is empty (no local library). The root node is
     * marked browsable-but-not-playable with no children, matching the legacy
     * MediaBrowserServiceCompat.onGetRoot(EMPTY_ROOT) + onLoadChildren(emptyList()) pattern.
     *
     * Callbacks run on the application main thread. The session itself is supplied as
     * [session] argument and mirrors [mediaSession].
     */
    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onGetLibraryRoot caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} ctrlVer=${browser.controllerVersion} " +
                        "paramsExtras=${params?.extras}",
                )
            }
            // Root extras carry the AAOS content-style hints so the media source switcher
            // lists Carlink with the expected card presentation even though the browse tree
            // below is empty. The two EXTRAS_KEY_CONTENT_STYLE_* values are the Media3 1.10.0
            // constants (verified from media3-session-1.10.0-sources.jar) and resolve to the
            // legacy "android.media.browse.CONTENT_STYLE_*_HINT" extras consumed by AAOS
            // Media Center / GMCarMediaService via the MediaBrowserCompat bridge.
            //
            // SEARCH_SUPPORTED has no Media3 constant in 1.10.0 (confirmed absent from
            // androidx.media3.session.MediaConstants); the legacy string is kept to
            // declare to MediaBrowserCompat consumers that this projection app does NOT
            // support search (CarPlay/AA owns search on the phone).
            val rootExtras = Bundle().apply {
                // Per Media3 1.10.0 source observed on 2026-05-03 (around
                // MediaConstants.java:301), EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM resolved
                // to the legacy DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM (value 1).
                // Using the named constant avoids a magic-number literal and tracks any
                // future value change. Re-verify the resolved value on Media3 upgrades.
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                )
                putBoolean("android.media.browse.SEARCH_SUPPORTED", false)
            }
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Carlink")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(rootItem, MediaLibraryService.LibraryParams.Builder().setExtras(rootExtras).build()),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onGetChildren caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} parentId=$parentId page=$page pageSize=$pageSize",
                )
            }
            // DOC DEVIATION: the Media3 onGetChildren spec (as read on 2026-05-03) said
            // "Return an empty list for no children rather than using error codes." We
            // deliberately diverge based on field observations. On the firmware tested as
            // of 2026-05-03 (AAOS Media Center, Android Auto media list, Bluetooth AVRCP
            // browse), an empty result list with RESULT_SUCCESS appeared to be treated as
            // "still loading" rather than "no content," with a spinner held indefinitely
            // on the consumer side. Returning a single non-browsable, non-playable INFO
            // item gave the user a visible explanation in our testing; in the rendering
            // observed both LIST_ITEM and SINGLE_ITEM styles produced one row, and
            // isPlayable=false suppressed phantom playback requests. If a later AAOS host
            // changes the empty-list behavior, prefer
            // `LibraryResult.ofItemList(ImmutableList.of(), params)` to align with the
            // documented preference. Re-test before relying on either branch on a new
            // host build.
            val infoItem = MediaItem.Builder()
                .setMediaId(BROWSE_INFO_ITEM_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Browsing not available")
                        .setSubtitle("Carlink mirrors phone projection — open the app to start CarPlay/Android Auto")
                        .setIsBrowsable(false)
                        .setIsPlayable(false)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.of(infoItem), params),
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onGetItem caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} mediaId=$mediaId",
                )
            }
            // Legacy MediaBrowserCompat clients (AAOS Media Center) may call getItem(rootId)
            // to verify the root node; per the MediaLibrarySession.Callback.onGetItem doc
            // "To allow getting the item, return a LibraryResult with RESULT_SUCCESS and a
            // MediaItem with a valid mediaId." Resolve ROOT_ID so the verification succeeds;
            // all other IDs return RESULT_ERROR_BAD_VALUE (the ID is invalid for this
            // projection app's empty browse tree, not merely unsupported).
            if (mediaId == ROOT_ID) {
                val rootItem = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Carlink")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build(),
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
            }
            if (mediaId == BROWSE_INFO_ITEM_ID) {
                val infoItem = MediaItem.Builder()
                    .setMediaId(BROWSE_INFO_ITEM_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Browsing not available")
                            .setSubtitle("Carlink mirrors phone projection — open the app to start CarPlay/Android Auto")
                            .setIsBrowsable(false)
                            .setIsPlayable(false)
                            .build(),
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(infoItem, null))
            }
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        /**
         * [PROBE] Diagnostic for the AOSP CarLauncher homescreen Media card vs.
         * ClusterMediaDisplay divergence (2026-04-23 session). ClusterMediaDisplay uses
         * platform `MediaSessionManager.getActiveSessions()` and renders correctly;
         * CarLauncher's card uses the MediaBrowserCompat→MediaModel chain and stays stuck
         * on the "source resolved, no metadata" placeholder even though the session has
         * 11 metadata keys, active=true, state=PLAYING, and 8 controllers bound. These
         * overrides log every handshake step with caller package + interface version
         * (ifaceVer==0 signals the legacy MediaBrowserCompat path; ifaceVer>0 is a
         * native Media3 controller) so a logcat capture can distinguish:
         *   (a) CarLauncher connects as a controller but the MBS-legacy onGetLibraryRoot
         *       dispatch never fires → Media3 legacy-bridge wiring bug.
         *   (b) onGetLibraryRoot fires but onSubscribe/onGetChildren never follow →
         *       BrowserRoot shape (mediaId/extras) rejected by AOSP MediaBrowserConnector.
         *   (c) Full handshake completes cleanly yet the card stays blank → failure is
         *       deeper in AOSP MediaModel, outside anything we can fix app-side.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val packageName = controller.packageName
            val uid = controller.uid
            // Media3's documented trust check (per background-playback docs). Per
            // `ControllerInfo.isTrusted()` javadoc as observed in Media3 1.10.0 source on
            // 2026-05-03 (around MediaSession.java:687-688), the predicate covers: own UID,
            // SYSTEM_UID, MEDIA_CONTENT_CONTROL, STATUS_BAR_SERVICE, and enabled
            // notification listeners. That superset includes SystemUI media controls and
            // notification-listener brokers our prior hand-rolled gate would have rejected.
            // The exact predicate set may evolve across Media3 releases; re-verify on
            // upgrade. Line numbers are point-in-time — grep the symbol if they drift.
            val isTrusted = controller.isTrusted

            // Always log GM/AAOS observer connections at INFO so field logcats from real
            // hardware reveal which brokers actually bind. Helpful for diagnosing
            // "carlink doesn't appear in source X" reports — the observer either binds
            // (visible here) or doesn't (and the explanation is firmware-side, e.g.
            // GMAudioServer.MediaSessionUtils.SOURCES_PACKAGE allowlist).
            val isGmAaosObserver = packageName.startsWith("com.gm.") ||
                packageName.startsWith("com.android.car.") ||
                packageName == "com.android.systemui"
            if (isGmAaosObserver) {
                Log.i(
                    TAG,
                    "[MEDIA_SESSION] GM/AAOS observer bound: package=$packageName uid=$uid " +
                        "trusted=$isTrusted ifaceVer=${controller.interfaceVersion}",
                )
            }

            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onConnect caller=$packageName uid=$uid trusted=$isTrusted " +
                        "ifaceVer=${controller.interfaceVersion} ctrlVer=${controller.controllerVersion} " +
                        "connectionHints=${controller.connectionHints}",
                )
            }

            return if (isTrusted) {
                super.onConnect(session, controller)
            } else {
                Log.w(
                    TAG,
                    "[MEDIA_SESSION] Rejecting untrusted controller package=$packageName uid=$uid " +
                        "(no MEDIA_CONTENT_CONTROL permission and not system/own UID)",
                )
                MediaSession.ConnectionResult.reject()
            }
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onDisconnected caller=${controller.packageName} uid=${controller.uid} " +
                        "ifaceVer=${controller.interfaceVersion}",
                )
            }
            super.onDisconnected(session, controller)
        }

        /**
         * Defensive sink for "play from media id" requests routed through Media3's
         * legacy bridge. On Silverado AAOS 12 the cluster's steering-wheel favorite
         * button (keycodes 137/138) reaches `GMMediaKeyService` which may dispatch
         * `transportControls.playFromMediaId(...)` — but only when the active source
         * is classified as `DAB|AM|FM|XM` (verified in
         * GMAudioServer.GMMediaKeyService.handleSWCKeys). Carlink registers as a
         * projection bridge, not a radio source, so the call is unlikely to land
         * here. If it ever does (misclassification), Media3 routes it to this
         * callback. We log and reject — returning an empty list short-circuits the
         * Media3 player and avoids modifying our mirror playlist.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.isNotEmpty()) {
                val ids = mediaItems.take(3).map { it.mediaId }
                Log.w(
                    TAG,
                    "[MEDIA_SESSION] onAddMediaItems from ${controller.packageName} uid=${controller.uid} " +
                        "ifaceVer=${controller.interfaceVersion} count=${mediaItems.size} firstIds=$ids — " +
                        "carlink is a projection mirror with no library; rejecting",
                )
            }
            return Futures.immediateFuture(mutableListOf())
        }

        /**
         * Subscription handler — logging-only override; behavior is delegated to the default
         * implementation.
         *
         * Per the Media3 docs read on 2026-05-03, the default `super.onSubscribe` calls
         * [onGetItem] for [parentId] and accepts the subscription only if [onGetItem]
         * returns `RESULT_SUCCESS` with an `isBrowsable=true` item. Mapping for our
         * [parentId]s under that observed behavior:
         *   - [ROOT_ID]              → [onGetItem] returns isBrowsable=true → subscription
         *                              accepted, [notifyChildrenChanged] becomes deliverable.
         *   - [BROWSE_INFO_ITEM_ID]  → [onGetItem] returns isBrowsable=false → subscription
         *                              rejected. This is intentional under the current
         *                              design: the info-item is a terminal display row,
         *                              not a browsable parent, so a rejection there is the
         *                              behavior we want.
         *   - any other id           → [onGetItem] returns ERROR_BAD_VALUE → subscription
         *                              rejected.
         * If a future Media3 release changes the default behavior of `onSubscribe`, this
         * mapping needs to be re-verified — the override only logs, so any default change
         * propagates here automatically.
         */
        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onSubscribe caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} parentId=$parentId paramsExtras=${params?.extras}",
                )
            }
            return super.onSubscribe(session, browser, parentId, params)
        }
    }

    /**
     * Trampoline from [UsbAdapterPlayer.Callback] → [MediaControlCallback]. The Player's
     * Callback runs on the main thread per SimpleBasePlayer's contract; we forward
     * synchronously so CarlinkManager's sendKey() sees main-thread callbacks as before.
     */
    private val playerCallback = object : UsbAdapterPlayer.Callback {
        override fun onPlay() {
            log("[MEDIA_SESSION] onPlay received")
            mediaControlCallback?.onPlay()
        }
        override fun onPause() {
            log("[MEDIA_SESSION] onPause received")
            mediaControlCallback?.onPause()
        }
        override fun onStop() {
            log("[MEDIA_SESSION] onStop received")
            mediaControlCallback?.onStop()
        }
        override fun onSkipToNext() {
            log("[MEDIA_SESSION] onSkipToNext received")
            mediaControlCallback?.onSkipToNext()
        }
        override fun onSkipToPrevious() {
            log("[MEDIA_SESSION] onSkipToPrevious received")
            mediaControlCallback?.onSkipToPrevious()
        }
    }

    /**
     * Create the UsbAdapterPlayer + MediaSession. Called once from
     * CarlinkManager.initialize() on the main thread during plugin attachment.
     * Safe to call multiple times — re-entry is a no-op once initialized.
     */
    fun initialize() {
        if (mediaSession != null) {
            log("[MEDIA_SESSION] Already initialized — skipping")
            return
        }
        try {
            val p = UsbAdapterPlayer(playerCallback)
            player = p
            // Note: MediaLibrarySession.Builder has two overloads — (Context, Player, Callback)
            // and (MediaLibraryService, Player, Callback). We use the Context overload because
            // this manager is created by CarlinkManager with an application Context, not by
            // the service itself. The service obtains the live session via the companion
            // accessor [getMediaLibrarySession] when AAOS calls [MediaLibraryService.onGetSession].
            mediaSession = MediaLibrarySession.Builder(context, p, libraryCallback)
                .setId(SESSION_ID)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java).apply {
                            // NEW_TASK is required because PendingIntent.getActivity is
                            // launched by the system (controller notification, now-playing
                            // card) outside any existing Activity context — without it
                            // Android throws AndroidRuntimeException. SINGLE_TOP avoids
                            // stacking duplicate MainActivity instances when the app is
                            // already visible.
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            currentInstance = this

            if (BuildConfig.DEBUG) {
                p.addListener(playerListener)
                startStateSnapshotLogging()
            }

            // EXPLICITLY push placeholder metadata to the legacy MediaSessionCompat
            // before signalling the latch. Without this, Media3 only propagates the
            // Player's initial state to the legacy bridge LAZILY on the first state
            // mutation. If a legacy MediaController consumer (the GMRHMIService cluster
            // on Silv + CT5) calls getMetadata() between session-build and the first
            // mutation, it reads null. The Silv cluster's processMetadata smali at
            // /tmp/silv_rhmi_smali/com/gm/rhmi/audio/service/AudioClusterPresentationService.smali:10820
            // (:cond_4b3) silently logs "un handled response" and returns when it sees
            // a null MediaMetadata — leaving the cluster card blank with no recovery.
            //
            // setSessionActive(false) is already the player's initial state, so this
            // call is a no-op for active state. The updateMediaMetadata + updatePlaybackState
            // calls force Media3 to push the placeholder to the legacy compat session
            // synchronously. Verified 2026-05-03 against Media3 1.10.0 source — see
            // /tmp/media3_session_src/androidx/media3/session/MediaSessionImpl.java
            // and MediaSessionLegacyStub.java.
            p.updatePlaybackState(Player.STATE_READY, false, 0L, C.TIME_UNSET)
            p.updateMediaMetadata(buildPlaceholderMetadata("Not connected"))

            // Session starts in the INACTIVE gate (placeholder metadata path). The
            // Player's getState() returns a single non-empty MediaItem at STATE_READY +
            // playWhenReady=false, so platform MediaSession.isActive=true throughout
            // the lifecycle — AAOS observers see Carlink as a present, ready-but-paused
            // source from the moment the service is bound, including during GM's ~88s
            // lazy bind window. [setProjectionActive] flips the gate to live metadata
            // when DEVICE_CONNECTED fires; [setInactive] flips back to placeholder.
            log("[MEDIA_SESSION] Initialized (placeholder pushed, Media3 session id=$SESSION_ID)")
        } catch (e: Exception) {
            // Log then rethrow so CarlinkManager's outer try/catch observes the failure.
            // Swallowing here would leave `mediaSessionManager` assigned to an instance with
            // mediaSession == null: AAOS would see the session registered but
            // CarlinkMediaBrowserService.onGetSession → getMediaLibrarySession() returns null
            // → permanent controller rejection. Fail-fast matches Media3's canonical pattern
            // (official sample does not try/catch MediaLibrarySession.Builder.build()).
            log("[MEDIA_SESSION] Failed to initialize: ${e.message}")
            throw e
        }
    }

    /**
     * Release session + player, cancel coroutines, reset dedup state. Call during
     * plugin detachment from the main thread.
     */
    fun release() {
        try {
            // Cancel scope outside the lock: non-blocking, and any in-flight
            // mainHandler.post will observe mediaSession == null under the lock.
            snapshotJob = null
            scope.cancel()
            synchronized(sessionLock) {
                lastArtJob = null
                // Revoke outstanding URI grants before releasing the session so consumer
                // packages don't retain access to a cache entry the session no longer
                // advertises. Context.revokeUriPermission (API 26+) is safe on min SDK 32.
                lastArtUri?.let { revokeUriFromConsumers(it) }
                // Per Media3 docs (developer.android.com/media/media3/session/serve-content
                // sample `onDestroy`), the Player is NOT released by MediaSession.release();
                // the app must release it explicitly. Release player BEFORE the session so
                // the session's controller teardown sees a still-valid Player reference.
                player?.removeListener(playerListener)
                player?.release()
                mediaSession?.release()
                mediaSession = null
                player = null
                mediaControlCallback = null
                if (currentInstance === this) currentInstance = null

                // Reset dedup / art state so a subsequent initialize() starts fresh.
                currentDurationMs = 0L
                lastArtHash = 0
                lastArtUri = null
                lastArtBytes = null
                lastPushedPlaying = null
                lastPushedPositionMs = 0L
                lastPushedTimeNanos = 0L
                lastPushedDurationMs = 0L
            }
            log("[MEDIA_SESSION] Released")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Error during release: ${e.message}")
        }
    }

    /** Register the transport-control sink (CarlinkManager routes to USB commands). */
    fun setMediaControlCallback(callback: MediaControlCallback?) {
        mediaControlCallback = callback
    }

    /**
     * Push now-playing metadata from a CarPlay/AA MEDIA_DATA frame. Safe to call from
     * the USB read thread. Builds a [MediaMetadata] and routes it through the Player
     * (the session syncs to connected controllers automatically).
     *
     * @param title song title
     * @param artist artist
     * @param album album name
     * @param appName source app ("Spotify", "Apple Music", ...) — goes into subtitle
     * @param albumArt raw JPEG/PNG bytes from the adapter (nullable)
     * @param duration total track duration in ms (0 if unknown)
     */
    fun updateMetadata(
        title: String?,
        artist: String?,
        album: String?,
        appName: String?,
        albumArt: ByteArray?,
        duration: Long = 0L,
    ) {
        // Hold sessionLock across the entire read+decide+publish so release()
        // cannot null out player/mediaSession in the middle. The lock is reentrant;
        // the inner publishMediaMetadata's synchronized(sessionLock) re-acquisition
        // is legal (JVM monitor counter). scope.launch inside the lock only enqueues
        // — the coroutine body runs on Dispatchers.IO after the lock is released.
        synchronized(sessionLock) {
            val p = player ?: return
            val hash = albumArt?.let { if (it.isEmpty()) 0 else it.contentHashCode() } ?: 0
            currentDurationMs = if (duration > 0) duration else 0L

            // PRIMARY carrier: raw ByteArray for MediaMetadata.setArtworkData.
            // Fallback to lastArtBytes on null/empty or unchanged hash so we keep
            // the prior song's art visible during the ~100 ms gap between AA's
            // text-only JSON frame and the follow-up ALBUM_COVER_AA frame.
            val artBytesForNow: ByteArray? = when {
                albumArt == null || albumArt.isEmpty() -> lastArtBytes
                hash == lastArtHash -> lastArtBytes
                else -> albumArt
            }
            if (artBytesForNow != null && artBytesForNow !== lastArtBytes) {
                lastArtBytes = artBytesForNow
            }

            // URI on the synchronous path: only reuse if this frame's hash matches
            // the previously-published art. Never publish a stale-song URI for new
            // bytes.
            val artUriForNow: Uri? = if (hash == lastArtHash) lastArtUri else null

            publishMetadata(
                player = p,
                title = title,
                artist = artist,
                album = album,
                appName = appName,
                duration = duration,
                artBytes = artBytesForNow,
                artUri = artUriForNow,
            )

            // ADDITIVE carrier: off-main file write → URI published once verified.
            // Cancellation caveat (unchanged from legacy): lastArtJob?.cancel() stops
            // the coroutine mid-flight, but a mainHandler.post already queued still
            // runs. All fields used inside the post are captured at launch time, so
            // the stale post publishes a CONSISTENT older-song tuple — never a
            // mismatched hash/URI/text pair. Relies on single-producer (USB read
            // thread).
            if (albumArt != null && hash != lastArtHash) {
                lastArtJob?.cancel()
                val titleCapture = title
                val artistCapture = artist
                val albumCapture = album
                val appNameCapture = appName
                val durationCapture = duration
                val bytesCapture = artBytesForNow
                lastArtJob = scope.launch {
                    val uri = try {
                        albumArtCache.put(albumArt)
                    } catch (e: Exception) {
                        log("[MEDIA_SESSION] AlbumArtCache.put failed: ${e.message}")
                        null
                    }
                    if (uri == null) return@launch  // Inline bytes already render; skip URI publish.
                    mainHandler.post {
                        synchronized(sessionLock) {
                            val p2 = player ?: return@synchronized
                            // Revoke the previous art's grants before replacing — any
                            // already-issued controllers will re-resolve through the new
                            // URI on the metadata update that publishMetadata will fire.
                            lastArtUri?.takeIf { it != uri }?.let { revokeUriFromConsumers(it) }
                            lastArtHash = hash
                            lastArtUri = uri
                            publishMetadata(
                                player = p2,
                                title = titleCapture,
                                artist = artistCapture,
                                album = albumCapture,
                                appName = appNameCapture,
                                duration = durationCapture,
                                artBytes = bytesCapture,
                                artUri = uri,
                            )
                        }
                    }
                }
            }
            log(
                "[MEDIA_SESSION] Metadata updated: $title - $artist " +
                    "(inlineBytes=${artBytesForNow != null}, uriPending=${albumArt != null && hash != lastArtHash})",
            )
        }
    }

    /**
     * Build a [MediaMetadata] and hand it to the Player. Single place for the
     * dual-carrier art logic + URI grants.
     *
     * Media3 note: the setter names differ from legacy — `setAlbumTitle` (not
     * `setAlbum`), `setSubtitle` (not `setDisplaySubtitle`), `setDurationMs` (not
     * `putLong(METADATA_KEY_DURATION)`). [setArtworkData] requires a PictureType —
     * we use [MediaMetadata.PICTURE_TYPE_FRONT_COVER] (ID3 v2.4 tag 3, "Cover (front)")
     * per the ID3 spec convention for album art.
     */
    private fun publishMetadata(
        player: UsbAdapterPlayer,
        title: String?,
        artist: String?,
        album: String?,
        appName: String?,
        duration: Long,
        artBytes: ByteArray?,
        artUri: Uri?,
    ) {
        synchronized(sessionLock) {
            try {
                val builder = MediaMetadata.Builder()
                    .setTitle(title ?: "Unknown")
                    // We set BOTH setTitle and setDisplayTitle because, per Media3
                    // 1.10.0 source observed on 2026-05-03, the
                    // LegacyConversions.convertToMediaMetadataCompat path appeared to
                    // populate METADATA_KEY_TITLE only from `title` and
                    // METADATA_KEY_DISPLAY_TITLE only from `displayTitle` — i.e. the two
                    // fields appeared independent, not auto-cross-populated. Some
                    // GM/AAOS observers in the firmware tested preferred DISPLAY_TITLE,
                    // so setting both was the safe choice. Re-verify if Media3's
                    // conversion logic changes on upgrade. (Line numbers omitted —
                    // they drift across releases; grep the function name if needed.)
                    .setDisplayTitle(title ?: "Unknown")
                    .setArtist(artist ?: "Unknown")
                    .setAlbumTitle(album ?: "")
                    .setSubtitle(appName ?: "Carlink")
                if (duration > 0) {
                    builder.setDurationMs(duration)
                }
                if (artBytes != null && artBytes.isNotEmpty()) {
                    builder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
                if (artUri != null) {
                    // Grant read permission BEFORE attaching the URI — some consumers
                    // fetch the URI asynchronously as soon as they observe metadata
                    // change, so the grant must already be in place.
                    grantUriToConsumers(artUri)
                    builder.setArtworkUri(artUri)
                }
                player.updateMediaMetadata(builder.build())
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to publish metadata: ${e.message}")
            }
        }
    }

    private fun grantUriToConsumers(uri: Uri) {
        var granted = 0
        var skipped = 0
        for (pkg in URI_GRANT_CONSUMERS) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                granted++
                if (BuildConfig.DEBUG) Log.d(TAG, "[MEDIA_SESSION] grantUriPermission OK pkg=$pkg uri=$uri")
            } catch (e: Exception) {
                skipped++
                if (BuildConfig.DEBUG) Log.d(TAG, "[MEDIA_SESSION] grantUriPermission skipped pkg=$pkg: ${e.message}")
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[MEDIA_SESSION] grantUriToConsumers: granted=$granted skipped=$skipped uri=$uri")
    }

    /**
     * Revoke read permission previously granted via [grantUriToConsumers]. Called when a
     * URI is about to be superseded by a new art URI, on session stop, and on release —
     * grants persist until explicit revoke or device reboot (per
     * developer.android.com/reference/android/content/Context#revokeUriPermission(String,Uri,int),
     * API 26+). Without this, the grant table grows unboundedly for the process lifetime.
     *
     * Uses the per-package overload so we only revoke grants WE issued through
     * [grantUriToConsumers]; the no-argument overload would also drop grants that came in
     * via clipboard / activity launches and is documented as "potentially dangerous".
     */
    private fun revokeUriFromConsumers(uri: Uri) {
        var revoked = 0
        for (pkg in URI_GRANT_CONSUMERS) {
            try {
                context.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                revoked++
            } catch (_: Exception) {
                // Grant already gone / package uninstalled; ignore.
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[MEDIA_SESSION] revokeUriFromConsumers: revoked=$revoked uri=$uri")
    }

    /**
     * Push playback state (play/pause + position) from a CarPlay/AA MEDIA_DATA frame.
     * Dedup suppresses redundant pushes (position ticks within 2 s of the AAOS
     * extrapolated value). Safe to call from any thread.
     */
    fun updatePlaybackState(playing: Boolean, position: Long = 0L) {
        synchronized(sessionLock) {
            val p = player ?: return
            val stateChanged = playing != lastPushedPlaying
            val now = System.nanoTime()
            val elapsedMs = (now - lastPushedTimeNanos) / 1_000_000L
            val expectedPosition = if (lastPushedPlaying == true) {
                lastPushedPositionMs + elapsedMs
            } else {
                lastPushedPositionMs
            }
            val seekDetected = kotlin.math.abs(position - expectedPosition) > seekThresholdMs
            // durationStale forces a push when currentDurationMs has been updated by a later
            // updateMetadata call but neither stateChanged nor seekDetected fires. Without
            // this, the player's durationMs stays at its first-push value (often C.TIME_UNSET
            // when the first MEDIA_DATA tick lacked MediaSongDuration) until the next track
            // change incidentally re-pushes via seekDetected.
            val durationStale = currentDurationMs > 0 && currentDurationMs != lastPushedDurationMs
            if (!stateChanged && !seekDetected && !durationStale) return

            try {
                val durationMs = if (currentDurationMs > 0) currentDurationMs else C.TIME_UNSET
                p.updatePlaybackState(Player.STATE_READY, playing, position, durationMs)
                lastPushedPlaying = playing
                lastPushedPositionMs = position
                lastPushedTimeNanos = now
                lastPushedDurationMs = if (durationMs == C.TIME_UNSET) 0L else durationMs
                val reason = when {
                    stateChanged -> "state change"
                    seekDetected -> "seek"
                    else -> "duration refresh"
                }
                log(
                    "[MEDIA_SESSION] Playback: ${if (playing) "PLAYING" else "PAUSED"} " +
                        "($reason, pos=${position}ms, dur=${durationMs}ms)",
                )
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to update playback state: ${e.message}")
            }
        }
    }

    /**
     * Switch the session into the CONNECTING state — Player reports STATE_BUFFERING +
     * placeholder metadata. AAOS Media Center still lists the session as an available
     * source during this phase. Called on CarlinkManager's CONNECTING state.
     */
    /**
     * Mark the session ACTIVE — a phone is attached and projection is starting.
     *
     * Called on CarlinkManager's DEVICE_CONNECTED state (adapter PLUGGED event received
     * from the phone-attached iAP2 / AA handshake, BEFORE media frames arrive). Session
     * flips the platform android.media.session.MediaSession.isActive → true, becoming
     * a valid playback-primary candidate. Player enters STATE_BUFFERING with placeholder
     * metadata pending real MEDIA_DATA frames.
     *
     * Distinct from [setInactive]: this is the trigger that makes Carlink eligible for
     * homescreen Media card / cluster Media card binding. Without this transition,
     * AAOS consumers treat Carlink as not-a-valid-media-source and fall back to other
     * apps (Radio, etc.).
     *
     * LIMITATION: this transition does NOT recover CarLauncher's homescreen Media card
     * after a force-stop — see class KDoc "KNOWN OS DEFICIENCY". CarLauncher caches
     * the pre-force-stop MediaController reference and does not re-bind on the new
     * session token.
     */
    fun setProjectionActive() {
        setProjectionActive(connectingPhase = true)
    }

    /**
     * Variant of [setProjectionActive] that publishes phase-specific placeholder text.
     * Distinguishes the CONNECTING phase ("Connecting to phone...") from the
     * DEVICE_CONNECTED phase ("Waiting for media...") so all consumer surfaces
     * (cardview, CarMediaApp, cluster) display meaningful state across the adapter
     * lifecycle, not just generic "Connecting...".
     *
     * @param connectingPhase  true for CarlinkManager.State.CONNECTING (USB handshake
     *                         in progress); false for CarlinkManager.State.DEVICE_CONNECTED
     *                         (handshake complete, awaiting first MEDIA_DATA frame).
     */
    fun setProjectionActive(connectingPhase: Boolean) {
        synchronized(sessionLock) {
            val p = player ?: return
            try {
                // Flip active FIRST so getState() returns a non-empty playlist before
                // the playback-state and metadata updates hit invalidateState.
                p.setSessionActive(true)
                // playWhenReady=true with STATE_BUFFERING signals "preparing to play
                // imminently" to AOSP CarMediaService.MediaControllerCallback. Combined
                // with the CONNECTING-time call site (CarlinkManager.updateMediaSessionState),
                // this matches v113's USB-attach arbitration weight and triggers
                // CarMediaService's "Changing media source due to playback state change"
                // promotion path before any other source can claim primary playback.
                p.updatePlaybackState(Player.STATE_BUFFERING, true, 0L, C.TIME_UNSET)
                val artist = if (connectingPhase) "Connecting to phone..." else "Waiting for media..."
                p.updateMediaMetadata(buildPlaceholderMetadata(artist))
                // Reset dedup so the next real playback frame isn't elided.
                lastPushedPlaying = null
                lastPushedPositionMs = 0L
                lastPushedTimeNanos = 0L
                lastPushedDurationMs = 0L
                log("[MEDIA_SESSION] State: ACTIVE ($artist, playWhenReady=true)")
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to set projection-active state: ${e.message}")
            }
        }
    }

    /**
     * Build a state-aware placeholder MediaMetadata. All AAOS consumer surfaces
     * (CarMediaApp, cardview audio card, cluster) read TITLE / DISPLAY_TITLE /
     * ARTIST / DISPLAY_SUBTITLE — the same set across surfaces. Setting the full
     * set at construction means surfaces with different fallback chains all
     * render identical text. The `artist` parameter conveys adapter link state
     * across the lifecycle: "Adapter not detected", "Connecting to phone...",
     * "Waiting for media...", or any other phase string the caller chooses.
     *
     * Why this matters for the cluster blank-card bug: the silv cluster's
     * processMetadata smali at AudioClusterPresentationService.smali:10820
     * (:cond_4b3) silently logs and returns when MediaMetadata is null. The
     * carlink session must therefore ALWAYS expose non-null metadata —
     * including the moments before the first real MEDIA_DATA frame arrives.
     */
    private fun buildPlaceholderMetadata(artist: String): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle("Carlink")
            .setDisplayTitle("Carlink")
            .setArtist(artist)
            .setSubtitle(artist)
            .build()

    /**
     * Refresh the placeholder MediaMetadata's artist/subtitle line without touching
     * PlaybackState or session-active flags. Used by [CarlinkManager.setStatusText]
     * to mirror the main-UI status string into the MediaSession so cluster, cardview,
     * and CarMediaApp surfaces show the same user-facing state text the user sees
     * in the app's main UI as the adapter transitions through its lifecycle
     * (Searching for adapter, Initializing, Waiting for phone, Phone connected,
     * Reconnecting, etc.).
     *
     * Caller is expected to gate on `state != STREAMING` — once real CarPlay/AA
     * track metadata is flowing via [publishMetadata], status text would clobber it.
     * No-op if the player isn't constructed yet.
     */
    fun updatePlaceholderArtist(text: String) {
        synchronized(sessionLock) {
            val p = player ?: return
            try {
                p.updateMediaMetadata(buildPlaceholderMetadata(text))
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to update placeholder artist: ${e.message}")
            }
        }
    }

    /**
     * Mark the session INACTIVE — no phone is projecting.
     *
     * Called on CarlinkManager's DISCONNECTED or CONNECTING state (app cold start,
     * adapter disconnected, adapter attached but phone not plugged, phone unplugged).
     * Session reports an empty playlist + STATE_IDLE; the platform
     * android.media.session.MediaSession.isActive bit flips to false. AAOS
     * CarMediaService/CarLauncher/ClusterMediaActivity treat Carlink as not a valid
     * playback-primary source and are free to promote any other source.
     *
     * The session record itself stays registered (no session.release()) so reactivation
     * on next PLUGGED event is fast — the SAME session token transitions back to
     * active, avoiding the consumer-rebinding issues that fresh session IDs cause.
     *
     * Note: keeping the session registered only helps when the process stays alive.
     * A force-stop tears the session down unconditionally, and on relaunch the new
     * session gets a new token — at which point CarLauncher's stale-controller bug
     * kicks in (see class KDoc "KNOWN OS DEFICIENCY"). There is no app-side mitigation.
     */
    fun setInactive() {
        synchronized(sessionLock) {
            val p = player ?: return
            try {
                // Flip the gate first; the Player's inactive branch will substitute
                // PLACEHOLDER_METADATA + STATE_READY in getState() regardless of what
                // we cache below. The explicit updates below ensure listeners observe
                // a clean transition (onPlaybackStateChanged + onMediaMetadataChanged
                // fire to placeholder values rather than carrying stale active-session
                // metadata into the next active phase).
                p.setSessionActive(false)
                p.updatePlaybackState(Player.STATE_READY, false, 0L, C.TIME_UNSET)
                p.updateMediaMetadata(buildPlaceholderMetadata("Not connected"))
                // Reset dedup + art state. Revoke any still-published URI grant so
                // consumers don't retain access to album art from an ended session.
                lastArtUri?.let { revokeUriFromConsumers(it) }
                currentDurationMs = 0L
                lastArtHash = 0
                lastArtUri = null
                lastArtBytes = null
                lastArtJob?.cancel()
                lastArtJob = null
                lastPushedPlaying = null
                lastPushedPositionMs = 0L
                lastPushedTimeNanos = 0L
                lastPushedDurationMs = 0L
                log("[MEDIA_SESSION] State: INACTIVE (placeholder, ready-paused)")
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to set inactive state: ${e.message}")
            }
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
        logCallback.log(message)
    }

    /**
     * Launch a coroutine that emits one [SNAPSHOT] line every [SNAPSHOT_INTERVAL_MS] until
     * [release] cancels [scope]. Reads player state on the application looper (per
     * [SimpleBasePlayer] contract) via [Dispatchers.Main.immediate]. Debug-only — gated
     * at the call site in [initialize].
     */
    private fun startStateSnapshotLogging() {
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                withContext(Dispatchers.Main.immediate) {
                    val p: UsbAdapterPlayer
                    val ms: MediaLibrarySession
                    synchronized(sessionLock) {
                        p = player ?: return@withContext
                        ms = mediaSession ?: return@withContext
                    }
                    val controllers = try {
                        ms.connectedControllers.size
                    } catch (e: Exception) {
                        -1
                    }
                    val md = p.mediaMetadata
                    log(
                        "[MEDIA_SESSION] [SNAPSHOT] state=${p.playbackState} " +
                            "playing=${p.isPlaying} pwr=${p.playWhenReady} " +
                            "pos=${p.contentPosition}ms dur=${p.duration}ms " +
                            "title=${md.title} artist=${md.artist} ctrls=$controllers",
                    )
                }
            }
        }
    }

    companion object {
        /** Session ID — must be unique within the app package. */
        private const val SESSION_ID = "CarlinkMediaSession"

        /** Periodic state snapshot interval. 10s balances signal vs. log noise. */
        private const val SNAPSHOT_INTERVAL_MS = 10_000L

        /** Root mediaId for the (empty) browse tree — projection app. */
        private const val ROOT_ID = "carlink_root"

        /** Single info-only child returned by [onGetChildren] so the OS native media
         *  browser shows an explanatory line instead of an indefinite "Loading" spinner.
         *  isBrowsable=false, isPlayable=false — purely informational. */
        private const val BROWSE_INFO_ITEM_ID = "carlink_browse_info"

        /**
         * Single live [MediaSessionManager] instance. Set by [initialize]; cleared by
         * [release]. Accessed by [CarlinkMediaBrowserService.onGetSession] to obtain the
         * live [MediaLibrarySession] without holding an explicit cross-object reference.
         *
         * StaticFieldLeak suppressed: the instance reachable through this static holds
         * a [Context] field, but [MainActivity] constructs [MediaSessionManager] with
         * `applicationContext` (see MainActivity.kt around the "applicationContext"
         * comment in [initializeCarlinkManager] — revision 2026-04-23 static-analysis
         * cleanup F2). Because the stored Context is process-scoped rather than
         * Activity-scoped, the field cannot leak an Activity across configuration
         * changes or re-creation. Lint's detector sees only the declared `Context`
         * type and cannot distinguish application vs Activity context at compile time,
         * hence the suppression is required to silence the false positive.
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var currentInstance: MediaSessionManager? = null

        private val instanceLock = Any()

        /**
         * Get-or-create the process-wide [MediaSessionManager] singleton. Called from
         * [CarlinkMediaBrowserService.onCreate] so the session exists before any AAOS
         * `onGetSession` probe arrives. Subsequent callers (e.g. MainActivity) get the
         * same instance back — `initialize()` is idempotent.
         *
         * Boot-race fix (2026-05-03): previously the manager was constructed from
         * MainActivity, which is not started by AAOS at boot. AAOS auto-launched the
         * MBS, probed `onGetSession` within ~50 ms, and the latch-based wait timed out
         * after 1500 ms returning `null` — a permanent controller rejection per Media3
         * docs. Owning the lifecycle in the MBS closes that window: `Service.onCreate`
         * runs synchronously before any binder dispatch, so the session is alive by
         * the time `onGetSession` is called.
         */
        fun getOrCreate(context: Context, logCallback: LogCallback): MediaSessionManager =
            synchronized(instanceLock) {
                currentInstance ?: MediaSessionManager(context, logCallback).also {
                    currentInstance = it
                }
            }

        /** Return the live singleton, or `null` if [getOrCreate] hasn't run yet. */
        fun instance(): MediaSessionManager? = currentInstance

        /** Release the singleton (called from [CarlinkMediaBrowserService.onDestroy]). */
        fun releaseInstance() {
            synchronized(instanceLock) { currentInstance }?.release()
        }

        /**
         * Return the currently-live [MediaLibrarySession], or `null` if [getOrCreate]
         * has not been called or the session was released.
         *
         * Atomic snapshot: the [currentInstance] read and the subsequent [mediaSession]
         * read happen as one critical section on the instance's [sessionLock], so
         * [release] cannot null [mediaSession] between the two reads.
         */
        fun getMediaLibrarySession(): MediaLibrarySession? {
            val inst = currentInstance ?: return null
            return synchronized(inst.sessionLock) { inst.mediaSession }
        }
    }
}
