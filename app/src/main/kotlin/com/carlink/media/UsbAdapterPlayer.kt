package com.carlink.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.carlink.BuildConfig
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val TAG = "CARLINK_PLAYER"

/**
 * Media3 [Player] that mirrors phone playback state coming from the Carlinkit USB adapter.
 *
 * This is a "mirror player" — no audio decode or playback happens locally. The connected
 * phone plays media over the USB link; the adapter relays metadata + transport state frames
 * (CarPlay / Android Auto MEDIA_DATA messages), and AAOS Media Center + steering-wheel
 * control gestures are routed back to the adapter as USB commands via [Callback].
 *
 * Why [SimpleBasePlayer]?
 * Media3's [Player] interface has ~40 abstract methods. [SimpleBasePlayer] reduces this to
 * one required override — [getState] — plus opt-in `handle*` methods for each advertised
 * [Player.Commands] action. It is the officially documented base class for non-playback /
 * state-mirror [Player] implementations (see CompositionPlayer, FakePlayer as references
 * in the Media3 source tree).
 *
 * Backwards compatibility with GM AAOS:
 * When wrapped by [androidx.media3.session.MediaSession], Media3 internally registers a
 * platform-level `android.media.session.MediaSession` so legacy observers (e.g. GM's
 * GMCarMediaService at `/system/priv-app/GMCarMediaService/`, which uses
 * `android.media.session.MediaSessionManager.getActiveSessions(ComponentName)` per
 * firmware-verified analysis) see this session identically to a pre-migration
 * `MediaSessionCompat`. The ClusterService → IClusterHmi widget pipeline remains intact.
 *
 * Threading:
 * - The player is pinned to [Looper.getMainLooper] at construction. Per Media3 contract
 *   (see `Player.getApplicationLooper`), all `handle*` callbacks run on that looper and all
 *   [getState] / [invalidateState] calls must be issued from that looper.
 * - External callers (USB read thread via CarlinkManager.handleMessage) call
 *   [updatePlaybackState] and [updateMediaMetadata]. These are thread-safe entry points
 *   that route the mutation + [invalidateState] back to the main looper via [Handler.post],
 *   so `invalidateState` never fires from the wrong thread (which would throw
 *   IllegalStateException per SimpleBasePlayer's javadoc).
 * - Internal state fields are guarded by [stateLock] so a post from the USB thread and a
 *   framework-driven [getState] on the main thread see consistent snapshots.
 *
 * Advertised commands:
 * Minimum set required by AAOS Media Center + typical steering-wheel routing: PLAY_PAUSE,
 * STOP, SEEK_TO_NEXT/PREVIOUS (both the "command" and "media-item" variants), SET_MEDIA_ITEM,
 * PREPARE, plus the three GET_* commands needed for controllers to read back metadata.
 * Intra-track seeking is NOT advertised: the phone is the authority for playback position;
 * we only report what it tells us.
 */
@UnstableApi
class UsbAdapterPlayer(
    private val callback: Callback,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /**
     * Routes MediaSession control gestures to the USB adapter. Implementation is expected
     * to enqueue a single-byte command frame on the adapter write path.
     */
    interface Callback {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSkipToNext()
        fun onSkipToPrevious()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    // Guarded by stateLock. Written from the main thread after postToMain; read from the
    // main thread inside getState(). Cross-thread writes are queued through mainHandler.
    //
    // playbackState maps Media3 [Player.State] values:
    //   STATE_IDLE      — stopped / no source (AAOS marks session "inactive")
    //   STATE_BUFFERING — connecting / waiting for data
    //   STATE_READY     — has source, can play (AAOS marks session "active")
    //   STATE_ENDED     — playback finished
    // Media3 has no public API named MediaSession.setActive. Based on Media3 1.10.0
    // source observed on 2026-05-03, the platform-side
    // android.media.session.MediaSession.isActive flag appears to be set true once at
    // session start and to stay true until release (see CORRECTION block below for the
    // observation details). AAOS observers in the firmware tested as of 2026-05-03
    // appeared to gate switcher visibility on metadata + timeline content driven by this
    // field, so it must be set accurately for CONNECTING / STREAMING / DISCONNECTED
    // transitions on those observed builds. Both behaviors are point-in-time observations
    // and should be re-verified if Media3 or the AAOS host firmware is upgraded.
    private var playbackState: Int = Player.STATE_IDLE
    private var isPlaying: Boolean = false
    private var positionMs: Long = 0L
    private var durationMs: Long = C.TIME_UNSET
    private var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY

    // Projection-state gate. Selects which MediaMetadata getState() reports — placeholder
    // ("Carlink"/"Not connected") when false, live phone metadata when true. The session
    // ALWAYS reports a non-empty playlist + STATE_READY regardless of this flag (v99-
    // equivalent contract restored 2026-05-03 to address an observed post-Media3 GM cluster
    // Media-card regression on the firmware/build tested at that date).
    //
    // CORRECTION (audit follow-up, 2026-05-03): an earlier comment in this file claimed
    // that Media3 derives platform android.media.session.MediaSession.isActive from
    // `!timeline.isEmpty() || playbackState != IDLE`, and that the placeholder branch
    // existed to keep that flag true. That claim was not corroborated by the Media3 1.10.0
    // source as read on 2026-05-03 — the observed implementation around
    // MediaSessionLegacyStub.start() calls `sessionCompat.setActive(true)` unconditionally,
    // and a grep of the same file did not surface a `setActive(false)` call before
    // `release()`. Within the constraints of that observation, the platform isActive flag
    // appears to stay true for the entire session lifetime regardless of [Player.State] or
    // timeline emptiness. This may change in a newer Media3 release; re-verify if upgrading.
    //
    // Re-attribution of the cluster regression cause (as of 2026-05-03 audit): the
    // empty-timeline + STATE_IDLE branch correlated with the cluster Media-card dropping
    // Carlink, but the proximate cause appears to be downstream consumer behavior
    // (GMCarMediaService / cluster's own metadata-bundle visibility evaluation drops
    // sources with no playable identity in the firmware tested), not platform isActive
    // flipping. The placeholder branch is still load-bearing on observed firmware — it
    // gives controllers a stable, non-empty source identity to bind to during GM's
    // ~88-second lazy-bind window — but the rationale we currently rely on is "expose
    // visible metadata," not "keep platform isActive=true." Treat both the cause analysis
    // and the firmware behavior as point-in-time observations subject to change.
    //
    // Starts false. Becomes true on PLUGGED event via [setSessionActive]. Returns to
    // false on UNPLUGGED / DISCONNECTED.
    private var isSessionActive: Boolean = false

    // COMMAND_SET_MEDIA_ITEM is intentionally NOT advertised. Per SimpleBasePlayer docs a
    // handle* method is only invoked when the corresponding command is available, and its
    // returned future signals "completion of all immediate State changes caused by this
    // call." Advertising SET_MEDIA_ITEM while handleSetMediaItems is a no-op would claim
    // success to controllers without actually changing the single synthetic item returned
    // by getState — visible-state inconsistency. The phone owns media-item selection.
    private val availableCommands: Player.Commands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_STOP)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_PREPARE)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_TIMELINE)
        .build()

    /**
     * Push a playback-state update originating from a USB MEDIA_DATA frame. Safe to call
     * from any thread; the actual state mutation + [invalidateState] are posted to the
     * main looper.
     *
     * @param playbackState one of [Player.STATE_IDLE], [Player.STATE_BUFFERING],
     *                      [Player.STATE_READY], [Player.STATE_ENDED]. AAOS derives the
     *                      "active media source" state from this, so map accurately.
     * @param playing current play/pause state reported by the phone
     * @param positionMs current playback position in milliseconds (phone-authoritative)
     * @param durationMs total track duration in milliseconds, or a non-positive value if
     *                   unknown (mapped to [C.TIME_UNSET])
     */
    fun updatePlaybackState(
        playbackState: Int,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        postToMain {
            // Invariant guard: SimpleBasePlayer's verifyApplicationThread / invalidateState
            // require the application looper. postToMain routes here, but a future refactor
            // that moves state mutation OUT of postToMain would silently violate the
            // contract on release builds until the first controller crash. Debug assertion
            // catches regressions locally without runtime cost on release APKs.
            if (BuildConfig.DEBUG) check(Looper.myLooper() == applicationLooper) {
                "UsbAdapterPlayer state mutation off the application looper"
            }
            synchronized(stateLock) {
                this.playbackState = playbackState
                this.isPlaying = playing
                this.positionMs = positionMs
                this.durationMs = if (durationMs > 0) durationMs else C.TIME_UNSET
            }
            invalidateState()
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[PLAYER] state=$playbackState playing=$playing pos=${positionMs}ms dur=${durationMs}ms invalidated",
                )
            }
        }
    }

    /**
     * Push a metadata update (title / artist / album / artwork). Safe to call from any
     * thread. Framework fires onMediaMetadataChanged to all bound controllers after
     * [getState] is re-read.
     */
    fun updateMediaMetadata(metadata: MediaMetadata) {
        postToMain {
            if (BuildConfig.DEBUG) check(Looper.myLooper() == applicationLooper) {
                "UsbAdapterPlayer state mutation off the application looper"
            }
            synchronized(stateLock) {
                this.mediaMetadata = metadata
            }
            invalidateState()
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[PLAYER] metadata title=${metadata.title} artist=${metadata.artist} " +
                        "album=${metadata.albumTitle} artBytes=${metadata.artworkData?.size ?: 0} " +
                        "artUri=${metadata.artworkUri} invalidated",
                )
            }
        }
    }

    /**
     * Toggle projection-state gate. Safe to call from any thread.
     *
     * @param active true when a phone is attached and projecting (live phone metadata
     *   surfaced via [getState]); false when no projection is running ([getState] returns
     *   the placeholder branch — single placeholder MediaItem at STATE_READY +
     *   playWhenReady=false). Based on Media3 1.10.0 source observed on 2026-05-03, the
     *   platform android.media.session.MediaSession.isActive flag is not expected to be
     *   toggled by changes to this player's reported state — Media3 appeared to keep it
     *   true throughout the session lifetime in the observed source. AAOS observers
     *   detect "no projection" via the placeholder metadata payload in the firmware
     *   tested. Both the Media3 internal behavior and the AAOS observer behavior are
     *   point-in-time observations; re-verify before relying on either across upgrades.
     */
    fun setSessionActive(active: Boolean) {
        postToMain {
            if (BuildConfig.DEBUG) check(Looper.myLooper() == applicationLooper) {
                "UsbAdapterPlayer state mutation off the application looper"
            }
            synchronized(stateLock) {
                this.isSessionActive = active
            }
            invalidateState()
            if (BuildConfig.DEBUG) Log.d(TAG, "[PLAYER] sessionActive=$active invalidated")
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    override fun getState(): State {
        val snapshot = synchronized(stateLock) {
            StateSnapshot(playbackState, isPlaying, positionMs, durationMs, mediaMetadata, isSessionActive)
        }
        // INACTIVE path: single placeholder MediaItem at STATE_READY + playWhenReady=false.
        // Per Media3 1.10.0 source observations on 2026-05-03 (around
        // MediaSessionLegacyStub.start()), Media3 appears to set platform
        // android.media.session.MediaSession.isActive=true once at session start with no
        // observed setActive(false) call before release(). On that basis the branch's
        // purpose is NOT to keep platform isActive=true (that appears to be free) — it is
        // to expose a visible, named source so AAOS observers (GM CarMediaService,
        // CarLauncher Media card, ClusterMediaActivity) keep listing Carlink as a
        // present, ready-but-paused candidate even before a phone is plugged in. The
        // v99-equivalent always-visible contract appears to rely on metadata presence,
        // not on the platform active flag — re-verify if Media3 internals change.
        if (!snapshot.isActive) {
            val placeholderItem = MediaItem.Builder()
                .setMediaId(PLACEHOLDER_ITEM_ID)
                .setMediaMetadata(PLACEHOLDER_METADATA)
                .build()
            val placeholderItemData = MediaItemData.Builder(PLACEHOLDER_ITEM_ID)
                .setMediaItem(placeholderItem)
                .setMediaMetadata(PLACEHOLDER_METADATA)
                .setDurationUs(C.TIME_UNSET)
                .build()
            return State.Builder()
                .setAvailableCommands(availableCommands)
                .setPlaylist(listOf(placeholderItemData))
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setIsLoading(false)
                .build()
        }

        // Derive the MediaItemData uid from the metadata content so the uid changes when
        // the song changes. SimpleBasePlayer uses MediaItemData.uid (via equals) as the
        // identity signal that drives Player.Listener.onMediaItemTransition — reusing a
        // constant uid across songs suppresses track-transition callbacks for any
        // controller that relies on them (legacy MediaControllerCompat.onMetadataChanged
        // still fires, so GM AAOS is unaffected, but a pure-Media3 controller would miss
        // transitions). Position-only updates do not change title/artist/album so the uid
        // stays stable across playback ticks.
        val itemUid = itemUidFor(snapshot.metadata)
        val mediaItem = MediaItem.Builder()
            .setMediaId(itemUid)
            .setMediaMetadata(snapshot.metadata)
            .build()
        val durationUs = if (snapshot.durationMs == C.TIME_UNSET) {
            C.TIME_UNSET
        } else {
            snapshot.durationMs * 1000L
        }
        val mediaItemData = MediaItemData.Builder(itemUid)
            .setMediaItem(mediaItem)
            .setMediaMetadata(snapshot.metadata)
            .setDurationUs(durationUs)
            .build()
        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            // REMOTE — "started or paused because of a remote change" — is the correct
            // reason for a mirror Player driven by external transport state (the phone
            // over USB). USER_REQUEST is reserved for local setPlayWhenReady calls.
            .setPlayWhenReady(snapshot.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(snapshot.playbackState)
            .setContentPositionMs(snapshot.positionMs)
            .setIsLoading(snapshot.playbackState == Player.STATE_BUFFERING)
            .build()
    }

    private fun itemUidFor(metadata: MediaMetadata): String {
        // Title+artist+album identity triplet. Position-only frames keep the same triplet
        // so the uid is stable across playback ticks; a song change flips at least one of
        // the three. Incremental AA frames (title-only, then artist-only) will rotate the
        // uid multiple times during the ~30 ms burst — benign because the listener debounce
        // on the controller side collapses them.
        //
        // Hash uses a delimited-string concatenation (T|…|A|…|B|…) rather than XOR of
        // per-field hashCodes: XOR is commutative/self-cancelling and would collapse the
        // field-permutation edge case (e.g., title="X" artist="" == title="" artist="X").
        // Delimiter prefixes T/A/B also prevent value-boundary collisions ("ab|c" vs "a|bc").
        val title = metadata.title?.toString() ?: ""
        val artist = metadata.artist?.toString() ?: ""
        val album = metadata.albumTitle?.toString() ?: ""
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) return PLACEHOLDER_ITEM_ID
        val hash = "T|$title|A|$artist|B|$album".hashCode().toLong() and 0xFFFFFFFFL
        return "carlink-$hash"
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (BuildConfig.DEBUG) Log.d(TAG, "[PLAYER] handleSetPlayWhenReady($playWhenReady)")
        if (playWhenReady) callback.onPlay() else callback.onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (BuildConfig.DEBUG) Log.d(TAG, "[PLAYER] handleStop")
        callback.onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
    ): ListenableFuture<*> {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[PLAYER] handleSeek index=$mediaItemIndex pos=${positionMs}ms cmd=$seekCommand")
        }
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                callback.onSkipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                callback.onSkipToPrevious()
            else -> {
                // Intra-track seeks are not supported — phone owns the position.
            }
        }
        return Futures.immediateVoidFuture()
    }

    // handleSetMediaItems is intentionally NOT overridden: COMMAND_SET_MEDIA_ITEM is not
    // advertised (see availableCommands), so SimpleBasePlayer never invokes it.

    override fun handlePrepare(): ListenableFuture<*> {
        if (BuildConfig.DEBUG) Log.d(TAG, "[PLAYER] handlePrepare")
        return Futures.immediateVoidFuture()
    }

    private data class StateSnapshot(
        val playbackState: Int,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val metadata: MediaMetadata,
        val isActive: Boolean,
    )

    companion object {
        // Placeholder uid used when no metadata has arrived yet (pre-stream placeholder).
        // Real items derive their uid from metadata content via [itemUidFor] so the uid
        // rotates on track change and drives Player.Listener.onMediaItemTransition.
        private const val PLACEHOLDER_ITEM_ID = "carlink-placeholder"

        // Placeholder metadata served while the session is INACTIVE (no phone projecting).
        // Non-null title/artist is a visibility contract — some AAOS OEMs drop sessions
        // with null/empty metadata from the source switcher (v99-era field-verified).
        private val PLACEHOLDER_METADATA: MediaMetadata = MediaMetadata.Builder()
            .setTitle("Carlink")
            .setArtist("Not connected")
            .setDisplayTitle("Carlink")
            .setSubtitle("Carlink")
            .build()
    }
}
