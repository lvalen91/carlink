package com.carlink

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.view.Surface
import androidx.core.content.edit
import com.carlink.BuildConfig
import com.carlink.audio.DualStreamAudioManager
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.gnss.GnssForwarder
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logVideoUsb
import com.carlink.logging.logWarn
import com.carlink.media.CarlinkMediaBrowserService
import com.carlink.media.MediaSessionManager
import com.carlink.navigation.NavigationStateManager
import com.carlink.platform.AudioConfig
import com.carlink.platform.PlatformDetector
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.AdapterDriver
import com.carlink.protocol.AudioCommand
import com.carlink.protocol.AudioDataMessage
import com.carlink.protocol.BluetoothPairedListMessage
import com.carlink.protocol.BoxSettingsMessage
import com.carlink.protocol.CommandMapping
import com.carlink.protocol.CommandMessage
import com.carlink.protocol.InfoMessage
import com.carlink.protocol.MediaDataMessage
import com.carlink.protocol.MediaType
import com.carlink.protocol.Message
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.NaviFocusMessage
import com.carlink.protocol.PeerBluetoothAddressMessage
import com.carlink.protocol.PhaseMessage
import com.carlink.protocol.PhoneType
import com.carlink.protocol.PluggedMessage
import com.carlink.protocol.SessionTokenMessage
import com.carlink.protocol.StatusValueMessage
import com.carlink.protocol.AudioRoutingState
import com.carlink.protocol.StreamPurpose
import com.carlink.protocol.UnknownMessage
import com.carlink.protocol.UnpluggedMessage
import com.carlink.protocol.VideoStreamingSignal
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.MicSourceConfig
import com.carlink.ui.settings.WiFiBandConfig
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AppExecutors
import com.carlink.util.LogCallback
import com.carlink.video.H264Renderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference

/**
 * Main Carlink Manager
 *
 * Central orchestrator for the Carlink native application:
 * - USB device lifecycle management
 * - Protocol communication via AdapterDriver
 * - Video rendering via H264Renderer
 * - Audio playback via DualStreamAudioManager
 * - Microphone capture for Siri/calls
 * - MediaSession integration for AAOS
 * - NavigationStateManager bootstrap (see `init { NavigationStateManager.initialize(context.applicationContext) }`)
 *   which enables cluster-navigation routing for NAVI_JSON/NAVI_IMAGE messages.
 * - CarlinkMediaBrowserService foreground-service lifecycle: CONNECTING/STREAMING start the
 *   FGS via `CarlinkMediaBrowserService.startConnectionForeground`, DISCONNECTED stops it
 *   via `stopConnectionForeground` (see [updateMediaSessionState]).
 * - GnssForwarder start/stop driven by `config.gpsForwarding` — started at STREAMING and
 *   on PLUGGED (START_GNSS_REPORT), stopped in stop()/release()/handleError()
 *   (NOT stopped in rebootAdapter — minor leak; reboot already implies disconnect, so low-impact).
 * - Auto-reconnect with exponential backoff and Pattern A / Pattern B / Pattern C status
 *   escalation (defined inline near the SHORT_SESSION_* constants; see [scheduleReconnect]
 *   and [handleError]).
 * - Device-management surface: DevList merging from BoxSettings, [forgetDevice],
 *   [connectToDevice] (targeted AutoConnect_By_BtAddress), [refreshDeviceList].
 * - Cluster-navigation gating: NAVI_JSON / NAVI_IMAGE are dropped unless
 *   `AdapterConfigPreference.getClusterNavigationSync()` returns true at message time
 *   (read per-message, not cached — see sites in [processMediaMetadata]).
 */
class CarlinkManager(
    private val context: Context,
    initialConfig: AdapterConfig = AdapterConfig.DEFAULT,
    /**
     * MediaSession manager owned by [MainActivity] (app-scope lifetime). Injected so the
     * underlying Media3 [androidx.media3.session.MediaLibrarySession] survives tier-2
     * CarlinkManager rebuilds (e.g. Apply in AdapterConfigurationDialog). Without this
     * injection, each rebuild would release + recreate the session, triggering
     * `onSessionDestroyed` on CarLauncher's MediaControllerCompat — CarLauncher then
     * fails to rebind to the new session token and the homescreen Media card renders
     * blank until a full package reinstall. Diagnosis captured 2026-04-23 (see
     * `/Users/zeno/Downloads/carlink_diagnostics/` snapshot diff). Null when Bluetooth
     * audio mode is selected (no AAOS media source advertised).
     */
    injectedMediaSessionManager: MediaSessionManager? = null,
) {
    init {
        NavigationStateManager.initialize(context.applicationContext)
        // Enable the composer debug PNG dumper so every composed maneuver bitmap is also
        // written to /sdcard/Android/data/zeno.carlink/files/composer_test/ for offline
        // eyeball-check via `adb pull`. The pure compose path is unaffected by the dumper —
        // it's a pass-through side channel triggered from ComposedIconStore.populateFromIap2m.
        // Strip together with the [NAVI_JSON_RX] debug logging when this stabilizes.
        com.carlink.navigation.compose.ManeuverIconDebugDumper.enable(context.applicationContext)
        // Enable the runtime composer (D16). Without this, lookups return null and the
        // existing static-XML / AA-bitmap paths handle the cluster icon — same behavior as
        // before D16. Turning this ON activates the per-route precompose + cluster icon
        // override pipeline. Validation pathway: PNGs written by ManeuverIconDebugDumper
        // continue regardless of this flag.
        com.carlink.navigation.compose.ComposedIconStore.setEnabled(true)

        // VCU/VCUNH1 (CT5, AAOS 14) enum-only cluster gate. There the cluster is a separate QNX
        // safety-domain VM that renders a native Altia sprite from the Maneuver enum and MASKS the
        // app bitmap/URI (see documents/reference/gminfo/projection/cluster_maneuver_mapping.md §0/§4).
        // The roundabout WITH_ANGLE refinement (ManeuverMapper) still feeds that enum, but the
        // per-maneuver bitmap compose and the AA-bitmap shim are dead work there — skip both.
        // BEST-EFFORT / UNTESTED detection (no VCUNH1 hardware); harmless elsewhere (default off).
        val clusterIsEnumOnly = PlatformDetector.detect(context).isVcuCluster()
        if (clusterIsEnumOnly) {
            com.carlink.navigation.compose.ComposedIconStore.setBitmapComposeEnabled(false)
            NavigationStateManager.setClusterEnumOnly(true)
        }
    }

    // Config can be updated when actual surface dimensions are known
    private var config: AdapterConfig = initialConfig

    companion object {
        private const val USB_WAIT_PERIOD_MS = 3000L
        private const val PAIR_TIMEOUT_MS = 15000L

        // Auto-reconnect constants
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L // Start with 2 seconds
        private const val MAX_RECONNECT_DELAY_MS = 30000L // Cap at 30 seconds

        // Surface debouncing - wait for size to stabilize before updating codec
        private const val SURFACE_DEBOUNCE_MS = 150L

        // AA MOVE rate-limit interval (~60 fps budget).
        //
        // IMPORTANT — different semantics from AutoKit despite the shared 17 ms number:
        // AutoKit uses 17 ms as a STATIONARY-FINGER MOVE REPEATER: on DOWN it starts a
        // periodic timer that keeps re-sending the captured-at-DOWN position every
        // 17 ms until UP cancels it, so the phone keeps receiving MOVE updates even
        // when the finger is not moving. AutoKit does NOT rate-limit OS-delivered
        // MotionEvents — every ACTION_MOVE is sent to USB immediately.
        //
        // carlink_native uses 17 ms as the OPPOSITE: a rate-limit on OS-delivered
        // MOVE events (skip a MOVE send when <17 ms has elapsed since the previous
        // one). This caps the per-second packet rate but does NOT emit the synthetic
        // stationary-finger repeats AutoKit does. If AA ever drops projection on
        // long stationary touches, implementing AutoKit's repeater pattern may be
        // required.
        //
        // Wire-format cross-reference (verified against the AutoKit implementation):
        // type 0x05 SingleTouch, 16-byte payload, action codes 14/15/16 for
        // DOWN/MOVE/UP, coordinates normalized to 0-10000, flag word at offset 12
        // packs encoderType | (offScreen << 16). See [MessageSerializer.serializeSingleTouch].
        //
        // Applies only to the AA path (type 0x05 SingleTouch); CarPlay sessions use
        // 0x17 MultiTouch and do not consult this constant. 2026-04-20 UI_TOUCH traces
        // (2 CarPlay sessions, ~100min) show pointers=1 on every event; zero multi-touch.
        private const val AA_TOUCH_THROTTLE_NS = 17_000_000L

        // ---------------------------------------------------------------------------------
        // Reconnect-escalation patterns (in-source conventions)
        //
        // These "Pattern A/B/C" labels are named as-if from an external runbook, but the
        // runbook lives only in-source — define them here so the sites that reference them
        // (see handleError body, and the SCANNING_DEVICE branch in handleMessage) stay in sync.
        //
        //   Pattern A — "no initial response" errors in a row (consecutiveNoResponse >= 2).
        //               The adapter's USB write path appears dead; retrying won't help.
        //               Surfaced as "Adapter not responding — reboot adapter".
        //
        //   Pattern B — SCANNING_DEVICE command arrives after a prior PLUGGED session
        //               (hadPriorSession && reconnectAttempts > 0). The adapter is alive
        //               and scanning but the phone isn't coming back — wireless subsystem
        //               may be stuck. Surfaced as "Adapter scanning — phone not reconnecting".
        //
        //   Pattern C — STREAMING sessions that die within SHORT_SESSION_THRESHOLD_MS,
        //               SHORT_SESSION_ESCALATION_COUNT times in a row → "connection unstable"
        //               (likely ZLP or firmware instability). Surfaced as
        //               "Connection unstable — reboot adapter".
        //
        // Keep these labels/thresholds stable; remote logs filter on the "[ESCALATION]" tag.
        // ---------------------------------------------------------------------------------

        // Pattern C: STREAMING sessions shorter than this are "short-lived" (unstable adapter)
        private const val SHORT_SESSION_THRESHOLD_MS = 10_000L
        // Pattern C: this many consecutive short sessions → "connection unstable"
        private const val SHORT_SESSION_ESCALATION_COUNT = 2
    }

    /**
     * Connection state enum.
     */
    enum class State {
        DISCONNECTED,
        CONNECTING,
        DEVICE_CONNECTED,
        STREAMING,
    }

    /**
     * Media metadata information.
     */
    data class MediaInfo(
        val songTitle: String?,
        val songArtist: String?,
        val albumName: String?,
        val appName: String?,
        val albumCover: ByteArray?,
        val duration: Long,
        val position: Long,
        val isPlaying: Boolean,
    )

    /**
     * Information about a paired device from the adapter's DevList.
     */
    data class DeviceInfo(
        val btMac: String,
        val name: String,
        val type: String, // "CarPlay", "AndroidAuto", "HiCar"
        val lastConnected: String? = null, // timestamp (CarPlay only)
        val rfcomm: String? = null, // RFCOMM channel (CarPlay only)
    )

    /**
     * Callback interface for Carlink events.
     */
    interface Callback {
        fun onStateChanged(state: State)

        fun onStatusTextChanged(text: String)

        fun onHostUIPressed()

        /** Called when phone type becomes known (PLUGGED) or cleared (disconnect/error). */
        fun onPhoneTypeChanged(phoneType: PhoneType) {}

        /** Called when the adapter's paired device list changes. */
        fun onDeviceListChanged(devices: List<DeviceInfo>) {}
    }

    /**
     * Listener for device management events (device list changes, connection state).
     * Unlike [Callback], multiple listeners can be registered concurrently.
     */
    fun interface DeviceListener {
        fun onDeviceListChanged(devices: List<DeviceInfo>)
    }

    private val deviceListeners = mutableListOf<DeviceListener>()

    fun addDeviceListener(listener: DeviceListener) {
        synchronized(deviceListeners) { deviceListeners.add(listener) }
    }

    fun removeDeviceListener(listener: DeviceListener) {
        synchronized(deviceListeners) { deviceListeners.remove(listener) }
    }

    private fun notifyDeviceListeners() {
        val snapshot = synchronized(deviceListeners) { deviceListeners.toList() }
        snapshot.forEach { it.onDeviceListChanged(_deviceList) }
    }

    /**
     * AA center-crop parameters for SurfaceView oversize/clip layout and touch remapping.
     * Null when not applicable (CarPlay, 16:9 display, or no config yet).
     */
    data class AaCropParams(
        val tierWidth: Int,
        val tierHeight: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
    )

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main)

    // Current state
    private val currentState = AtomicReference(State.DISCONNECTED)
    val state: State get() = currentState.get()

    // Session-scoped unknown data counters — reset on connect, dumped on disconnect
    private var unknownMessageTypeCount = 0
    private var unknownMediaSubtypeCount = 0
    private var unknownCommandCount = 0
    private var unknownAudioCommandCount = 0
    private var unknownPhoneTypeCount = 0
    private var unknownBoxSettingsKeyCount = 0
    private val unknownMessageTypes = mutableSetOf<Int>()     // raw type IDs seen
    private val unknownMediaSubtypes = mutableSetOf<Int>()    // raw subtype IDs seen
    private val unknownCommandIds = mutableSetOf<Int>()       // raw command IDs seen
    private val unknownAudioCommandIds = mutableSetOf<Int>()  // raw audio cmd IDs seen

    // Video frame logging throttle — log every 30th frame to reduce logcat spam
    private var videoFrameCount = 0L

    // Callback
    private var callback: Callback? = null

    // USB
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDeviceWrapper? = null

    // Wake lock to prevent CPU sleep during USB streaming
    // PARTIAL_WAKE_LOCK keeps CPU running but allows screen to turn off
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Carlink::UsbStreamingWakeLock",
        )

    // Protocol
    private var adapterDriver: AdapterDriver? = null

    // Video
    private var h264Renderer: H264Renderer? = null
    private var videoSurface: Surface? = null
    private var lastVideoDiscardWarningTime = 0L // Throttle discard warnings

    /** True once we've inferred phone type from video header (mid-session rejoin). */
    private var videoPhoneTypeInferred = false

    /** True when codec start is deferred until phone type is known (PLUGGED or video inference). */
    // Codec start decision sites — keep in sync:
    //   (1) initialize() debounce fires → startCodecIfDeferred (in the debounce callback
    //       around the `if (codecDeferred && currentPhoneType != null)` check).
    //   (2) PLUGGED CarPlay branch (~L1610-1612 — the `if (message.phoneType != ANDROID_AUTO)`
    //       guard before `startCodecIfDeferred()`).
    //   (3) videoProcessor fallback (~L2673-2675 — `if (codecDeferred) { startCodecIfDeferred() }`
    //       in createVideoProcessor.processVideoDirect). This is the actual codec-start path
    //       for mid-session rejoin; it fires earlier than the video-header keyframe-nudge block.
    //   (4) Keyframe nudge on video-header phone-type inference (~L2727 — the non-AA
    //       `scheduleDelayedKeyframe` branch). NOTE: this does NOT start the codec —
    //       scheduleDelayedKeyframe only schedules periodic FRAME commands on a timer.
    //       The codec has already been started by site (3) by the time this runs.
    //   (5) startCodecIfDeferred itself (the single implementation all the above call).
    // Any NEW codec-start trigger must be added to this list AND to the function it calls.
    private var codecDeferred = true

    /** Actual video surface dimensions (may differ from config due to Compose inset behavior). */
    private var actualSurfaceWidth = 0
    private var actualSurfaceHeight = 0

    // Video state from adapter header — tracked for AA touch flags (AutoKit packs these in touch payload)
    // encoderType: 1=H264, 2=H265(default/initial), 4=MJPEG
    // offScreen: 0=on-screen, 1=off-screen
    // Concurrency: read by sendMultiTouch on Dispatchers.IO when packing the AA touch-frame
    // flag word. Multiple writers: videoProcessor on USB read thread (primary), stop() on
    // main thread, handleError() on the call-site thread. All writes are simple constants
    // (not RMW), so @Volatile is still sufficient — single-writer claim was incorrect.
    @Volatile private var videoEncoderType = 2

    @Volatile private var videoOffScreen = 0

    // Audio
    private var audioManager: DualStreamAudioManager? = null
    private var audioInitialized = false

    // Microphone
    private var microphoneManager: MicrophoneCaptureManager? = null
    private var isMicrophoneCapturing = false
    private var currentMicDecodeType = 5 // 16kHz mono
    private var currentMicAudioType = 3 // Siri/voice input
    private var lastIncomingDecodeType = 5 // Track adapter's current audio format (from incoming AudioData)
    private var micSendTimer: Timer? = null

    /**
     * Voice mode tracking for proper microphone lifecycle management.
     *
     * CRITICAL: CarPlay sends PHONECALL_START before SIRI_STOP when making calls via Siri.
     * Without tracking, SIRI_STOP would kill the phone call's microphone.
     *
     * Observed sequence (from USB capture analysis):
     *   217.13s: PHONECALL_START  → mic should stay active
     *   217.26s: SIRI_STOP        → must NOT stop mic (phone call active)
     *
     * NOTE: the specific timestamps above were observed on real hardware during development;
     * the corresponding USB capture is not checked in to this repo. Treat them as anchoring
     * folklore — the *ordering* (PHONECALL_START before SIRI_STOP, ~130ms apart) is the load-
     * bearing invariant, not the absolute timestamps.
     */
    private enum class VoiceMode { NONE, SIRI, PHONECALL }

    private var activeVoiceMode = VoiceMode.NONE

    // Audio routing state flags for two-factor PCM routing (replaces currentStreamPurpose)
    @Volatile private var isSiriAudioActive = false
    @Volatile private var isPhoneCallAudioActive = false
    @Volatile private var isAlertAudioActive = false

    // GNSS
    private var gnssForwarder: GnssForwarder? = null

    // MediaSession
    // App-scope-owned (see constructor KDoc on [injectedMediaSessionManager]). This
    // CarlinkManager only borrows the reference — [initialize] attaches the transport
    // callback, [release] detaches it. The MediaLibrarySession itself is NEVER released
    // from here; that's MainActivity.onDestroy's job.
    private var mediaSessionManager: MediaSessionManager? = injectedMediaSessionManager

    // Timers
    private var pairTimeout: Timer? = null
    private var frameIntervalJob: Job? = null

    // Phone type tracking for keyframe request decisions
    /** Current phone type (CarPlay, Android Auto, etc.) from the PLUGGED message. Null when no phone connected. */
    @Volatile var currentPhoneType: PhoneType? = null
        private set

    // Auto-reconnect on USB disconnect
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0

    // Status escalation: detect degraded adapter states and give actionable user feedback.
    // - hadPriorSession: true if PLUGGED was received at least once since last stop().
    //   Note: start() only resets it indirectly when it calls stop() internally (which
    //   requires an existing adapterDriver). After handleError() followed by a fresh
    //   start(), the flag can survive.
    //   Distinguishes "adapter broken after session died" from "normal waiting for first phone".
    // - consecutiveNoResponse: counts sequential "no initial response" errors (Pattern A: USB write dead).
    // - shortLivedStreamingCount: counts STREAMING sessions that die within SHORT_SESSION_THRESHOLD_MS
    //   (Pattern C: unstable rapid cycling from ZLP or firmware issue).
    // - lastStreamingStartMs: timestamp when STREAMING was entered, for short-session detection.
    private var hadPriorSession: Boolean = false
    private var consecutiveNoResponse: Int = 0
    private var shortLivedStreamingCount: Int = 0
    private var lastStreamingStartMs: Long = 0L

    // Phase 13 (negotiation_failed) — prevents auto-restart loop when iPhone rejects config
    private var negotiationRejected: Boolean = false

    // Targeted connect: when set, the next restart() cycle sends AutoConnect_By_BtAddress
    // instead of the normal WIFI_CONNECT (1002) auto-connect scan.
    @Volatile private var pendingConnectTarget: String? = null

    // Last targeted MAC — retained after pendingConnectTarget is consumed by start(),
    // so PLUGGED handler can set _connectedBtMac even if PeerBluetoothAddress never arrives.
    @Volatile private var lastConnectTargetMac: String? = null

    // Surface update debouncing - prevents repeated codec recreation during rapid surface size changes
    private var surfaceUpdateJob: Job? = null
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var pendingCallback: Callback? = null

    // Media metadata tracking
    private var lastMediaSongName: String? = null
    private var lastMediaArtistName: String? = null
    private var lastMediaAlbumName: String? = null
    private var lastMediaAppName: String? = null
    private var lastAlbumCover: ByteArray? = null
    private var lastDuration: Long = 0L
    private var lastPosition: Long = 0L
    private var lastIsPlaying: Boolean = true

    // Device identification and management
    // HAZARD: RMW race on _deviceList — three writers:
    //   (1) handleMessage on USB read thread — BoxSettings DevList broadcasts (~L1744) and
    //       PLUGGED "enrich" paths (~L1593).
    //   (2) handleMessage on USB read thread — BluetoothPairedListMessage handler (~L1892)
    //       merges existing entries with an incoming paired list.
    //   (3) forgetDevice on Dispatchers.IO scope (user-initiated, ~L1051).
    // @Volatile guards the visibility of the reference only; concurrent read-modify-write
    // (e.g. filter+reassign, merge-and-reassign) is NOT atomic. Today the window is narrow —
    // user-initiated forget is rare and adapter DevList/PairedList broadcasts are infrequent —
    // so the practical risk is low. Worth a lock or AtomicReference CAS if either cadence grows.
    @Volatile private var _deviceList: List<DeviceInfo> = emptyList()
    @Volatile private var _connectedBtMac: String? = null // from PeerBluetoothAddress or BoxSettings #2

    /** The adapter's list of paired wireless devices (from BoxSettings DevList). */
    val pairedDevices: List<DeviceInfo> get() = _deviceList

    /** BT MAC of the currently connected phone (null if none). */
    val connectedBtMac: String? get() = _connectedBtMac

    /** WiFi status from PluggedMessage: 0=USB wired, 1=wireless, null=unknown. */
    @Volatile var currentWifi: Int? = null
        private set

    /** Clears cached media metadata to prevent stale data on reconnect. */
    private fun clearCachedMediaMetadata() {
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaAppName = null
        lastAlbumCover = null
        lastDuration = 0L
        lastPosition = 0L
        lastIsPlaying = true
        _connectedBtMac = null
        currentWifi = null
        NavigationStateManager.clear()
    }

    // Executors
    private val executors = AppExecutors()

    // LogCallback for Java components — routes to Logger with proper tags
    private val logCallback =
        object : LogCallback {
            override fun log(message: String) {
                this@CarlinkManager.log(message)
            }

            override fun log(
                tag: String,
                message: String,
            ) {
                Logger.d(message, tag)
            }

            override fun logPerf(
                tag: String,
                message: String,
            ) {
                if (Logger.isDebugLoggingEnabled() && Logger.isTagEnabled(tag)) {
                    Logger.d(message, tag)
                }
            }
        }

    /**
     * Initialize the manager with a Surface and actual surface dimensions.
     *
     * Uses SurfaceView's Surface directly for optimal HWC overlay rendering.
     * This bypasses GPU composition for lower latency and power consumption.
     *
     * Preconditions and call contract:
     * - Must be called from the main thread.
     * - Safe to call repeatedly (size changes go through a 150 ms debounce;
     *   see [SURFACE_DEBOUNCE_MS] and the [surfaceUpdateJob] path below).
     * - The callback reference is retained until the next initialize() call or [release].
     * - NOTE: the FIRST call (h264Renderer == null) BYPASSES the debounce — it proceeds
     *   immediately to build the renderer. Only subsequent calls (h264Renderer != null)
     *   use the debounce guard to coalesce rapid surface size changes from Compose layout.
     *
     * @param surface The Surface from SurfaceView to render video to
     * @param surfaceWidth Actual width of the surface in pixels
     * @param surfaceHeight Actual height of the surface in pixels
     * @param callback Callbacks for state changes and events
     */
    fun initialize(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        callback: Callback,
    ) {
        // Round to even numbers for H.264 compatibility
        val evenWidth = surfaceWidth and 1.inv()
        val evenHeight = surfaceHeight and 1.inv()

        // Track actual surface dims for AA crop/touch calculations
        val prevSurfaceWidth = actualSurfaceWidth
        val prevSurfaceHeight = actualSurfaceHeight
        actualSurfaceWidth = evenWidth
        actualSurfaceHeight = evenHeight

        // Config resolution was pre-computed from stable WindowMetrics in MainActivity.
        // Do NOT override with surface dimensions — SurfaceView size oscillates during
        // Compose layout (systemBars insets apply asynchronously on AAOS).
        if (config.userSelectedResolution) {
            logInfo(
                "[RES] Using user-selected resolution ${config.width}x${config.height} " +
                    "(surface: ${evenWidth}x$evenHeight)",
                tag = Logger.Tags.VIDEO,
            )
        } else {
            logInfo(
                "[RES] Using pre-computed resolution ${config.width}x${config.height} " +
                    "(surface: ${evenWidth}x$evenHeight)",
                tag = Logger.Tags.VIDEO,
            )
        }

        // LIFECYCLE FIX: If renderer exists, always update surface via setOutputSurface().
        //
        // CRITICAL: Do NOT use reference equality (===) to check if Surface is "the same".
        // After app goes to background, the Surface Java object may be the same reference,
        // but the underlying native BufferQueue is DESTROYED and recreated.
        // The codec will be rendering to a dead buffer → "BufferQueue has been abandoned" error.
        //
        // Solution: Always call setOutputSurface() when initialize() is called with an existing
        // renderer. This ensures the codec always has a valid native surface.
        // See: https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface
        //
        // DEBOUNCE FIX: Surface size changes rapidly during layout (996→960→965→969→992).
        // Each change triggers codec recreation. Debounce to wait for size stabilization.
        // NOTE: the FIRST initialize() call (h264Renderer == null) takes the un-debounced
        // fast path below this block — the renderer must exist before the debounce can
        // meaningfully coalesce size changes. Only subsequent calls go through the
        // debounced `surfaceUpdateJob` path.
        if (h264Renderer != null) {
            // Store pending values
            pendingSurface = surface
            pendingSurfaceWidth = evenWidth
            pendingSurfaceHeight = evenHeight
            pendingCallback = callback

            // Cancel any pending update
            surfaceUpdateJob?.cancel()

            // Debounce: wait for surface size to stabilize before updating codec
            surfaceUpdateJob =
                scope.launch {
                    delay(SURFACE_DEBOUNCE_MS)

                    // Use the latest pending values after debounce
                    val finalSurface = pendingSurface ?: return@launch
                    val finalCallback = pendingCallback ?: return@launch

                    logInfo(
                        "[LIFECYCLE] Surface stabilized at " +
                            "${pendingSurfaceWidth}x$pendingSurfaceHeight - updating codec",
                        tag = Logger.Tags.VIDEO,
                    )

                    this@CarlinkManager.callback = finalCallback
                    this@CarlinkManager.videoSurface = finalSurface

                    // If container dimensions changed during an AA session, resend
                    // BoxSettings so the phone re-renders for the new content area.
                    // prevSurfaceWidth/Height captured before actualSurface* was overwritten.
                    val dimsChanged =
                        prevSurfaceWidth > 0 && prevSurfaceHeight > 0 &&
                            (prevSurfaceWidth != pendingSurfaceWidth || prevSurfaceHeight != pendingSurfaceHeight)
                    if (dimsChanged && currentPhoneType == PhoneType.ANDROID_AUTO) {
                        resendBoxSettings()
                        // Notify UI so oversized surface recalculates for new crop params
                        finalCallback.onPhoneTypeChanged(PhoneType.ANDROID_AUTO)
                    }

                    if (codecDeferred && currentPhoneType != null) {
                        // AA resize complete — start codec with the new oversized surface
                        startCodecIfDeferred()
                    } else if (!codecDeferred) {
                        // Resume with new surface - this calls setOutputSurface() internally
                        h264Renderer?.resume(finalSurface)
                    }
                }
            return
        }

        // First-time initialization - create new renderer
        this.callback = callback
        this.videoSurface = surface

        logInfo(
            "[RES] Initializing with surface ${evenWidth}x$evenHeight @ ${config.fps}fps, ${config.dpi}dpi",
            tag = Logger.Tags.VIDEO,
        )

        // Detect platform for optimal audio configuration
        // Pass user-configured sample rate from AdapterConfig (overrides platform default)
        val platformInfo = PlatformDetector.detect(context)
        val audioConfig = AudioConfig.forPlatform(platformInfo, userSampleRate = config.sampleRate)

        logInfo(
            "[PLATFORM] Using AudioConfig: sampleRate=${audioConfig.sampleRate}Hz, " +
                "bufferMult=${audioConfig.bufferMultiplier}x, prefill=${audioConfig.prefillThresholdMs}ms",
            tag = Logger.Tags.AUDIO,
        )
        logInfo(
            "[PLATFORM] Using VideoDecoder: " +
                "${platformInfo.hardwareH264DecoderName ?: "generic (createDecoderByType)"}" +
                if (platformInfo.requiresIntelMediaCodecFixes()) {
                    " [Intel VPU workaround enabled]"
                } else {
                    ""
                },
            tag = Logger.Tags.VIDEO,
        )

        // Initialize H264 renderer with Surface for direct HWC rendering
        h264Renderer =
            H264Renderer(
                config.width,
                config.height,
                surface,
                logCallback,
                executors,
                platformInfo.hardwareH264DecoderName,
            )

        // Set keyframe callback - after codec reset, we need to request a new IDR frame
        // from the adapter. Without SPS/PPS + keyframe, the decoder cannot produce output.
        h264Renderer?.setKeyframeRequestCallback {
            logInfo("[KEYFRAME] Requesting keyframe after codec reset", tag = Logger.Tags.VIDEO)
            adapterDriver?.sendCommand(CommandMapping.FRAME)
        }

        // Set CSD extraction callback — persist SPS/PPS for future codec pre-warming
        h264Renderer?.setCsdExtractedCallback { sps, spsLen, pps, ppsLen ->
            val btMac = connectedBtMac ?: return@setCsdExtractedCallback
            val cacheKey = "${btMac}_${config.width}x${config.height}"
            val prefs = context.getSharedPreferences("carlink_csd_cache", Context.MODE_PRIVATE)

            val spsData = sps.copyOf(spsLen)
            val ppsData = if (pps != null && ppsLen > 0) pps.copyOf(ppsLen) else ByteArray(0)

            prefs.edit {
                putString("sps_$cacheKey", android.util.Base64.encodeToString(spsData, android.util.Base64.NO_WRAP))
                putString("pps_$cacheKey", android.util.Base64.encodeToString(ppsData, android.util.Base64.NO_WRAP))
            }

            logInfo("[DEVICE] CSD cached for $cacheKey: SPS=${spsLen}B, PPS=${ppsLen}B", tag = Logger.Tags.VIDEO)
        }

        // Defer codec start until phone type is known (PLUGGED message).
        // For AA, the UI will resize the SurfaceView to tier AR first (oversized + clip),
        // which triggers surface destruction/creation. Starting the codec AFTER the resize
        // avoids the 60s black screen from losing the active decoder's IDR reference.
        // For CarPlay, the codec starts immediately at PLUGGED since no resize is needed.
        codecDeferred = true

        logInfo("Video subsystem initialized, codec deferred until phone type known", tag = Logger.Tags.VIDEO)

        // Initialize audio manager with platform-specific config
        audioManager =
            DualStreamAudioManager(
                context,
                logCallback,
                audioConfig,
            )

        // Initialize microphone manager
        microphoneManager =
            MicrophoneCaptureManager(
                context,
                logCallback,
            )

        // Initialize GNSS forwarder for GPS → adapter → CarPlay pipeline (only if enabled)
        if (config.gpsForwarding) {
            gnssForwarder =
                GnssForwarder(
                    context = context,
                    sendGnssData = { adapterDriver?.sendGnssData(it) ?: false },
                    logCallback = ::log,
                )
        }

        // MediaSession lifetime is owned by MainActivity (see constructor KDoc). We only
        // attach our transport-control callback so routed Play/Pause/Skip events reach
        // this CarlinkManager via sendKey(). If the app is running in Bluetooth audio
        // mode MainActivity injects null here (no AAOS media source advertised), and
        // the callback attach becomes a safe no-op. Never construct a MediaSessionManager
        // from this method — that would bypass the app-scope ownership invariant and
        // resurrect the blank-homescreen-card bug fixed by the 2026-04-23 refactor.
        val session = mediaSessionManager
        if (session != null) {
            session.setMediaControlCallback(
                object : MediaSessionManager.MediaControlCallback {
                    override fun onPlay() {
                        sendKey(CommandMapping.PLAY)
                    }

                    override fun onPause() {
                        sendKey(CommandMapping.PAUSE)
                    }

                    override fun onStop() {
                        sendKey(CommandMapping.PAUSE)
                    }

                    override fun onSkipToNext() {
                        sendKey(CommandMapping.NEXT)
                    }

                    override fun onSkipToPrevious() {
                        sendKey(CommandMapping.PREV)
                    }
                },
            )
            logInfo("MediaSession transport callback attached (ADAPTER audio mode)", tag = Logger.Tags.ADAPTR)
        } else {
            logInfo("MediaSession not provided (BLUETOOTH audio mode or owner skipped)", tag = Logger.Tags.ADAPTR)
        }

        logInfo("CarlinkManager initialized", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Start connection to the adapter.
     *
     * Preconditions:
     * - Assumes [initialize] has run at least once so `h264Renderer` is available.
     *   If not, logs a warning and continues in audio-only mode (video data arriving before
     *   a Surface is ready will be discarded — see the guard at the top of this function
     *   and [createVideoProcessor] which warn-and-drops when `h264Renderer == null`).
     * - If an AdapterDriver already exists, calls [stop] internally before starting fresh,
     *   so it is safe to invoke repeatedly (e.g. after a user "reconnect" tap).
     */
    suspend fun start() {
        // Guard: Ensure H264Renderer is initialized before starting connection
        // This prevents video data from being discarded when app starts via MediaBrowserService
        // before MainActivity/Surface is ready
        if (h264Renderer == null) {
            logWarn(
                "H264Renderer not initialized - Surface not ready. " +
                    "Video will be discarded until initialize() is called with valid Surface.",
                tag = Logger.Tags.VIDEO,
            )
        }

        // Stop any existing connection before entering CONNECTING state
        // so that FGS started at CONNECTING isn't immediately stopped by stop()→DISCONNECTED
        if (adapterDriver != null) {
            stop()
        }

        setState(State.CONNECTING)
        setStatusText("Searching for adapter...")
        resetUnknownCounters()

        // Reset video renderer (only if initialized and codec is active).
        // When codecDeferred=true (fresh init or reconnect), skip reset — the codec
        // will be started by startCodecIfDeferred() at PLUGGED time with the correct
        // surface. Calling reset() here would prematurely start the codec before the
        // surface has stabilized (Compose layout may destroy/recreate the SurfaceView).
        if (!codecDeferred) {
            h264Renderer?.reset()
        }

        // Initialize audio
        if (!audioInitialized) {
            audioInitialized = audioManager?.initialize() ?: false
            if (audioInitialized) {
                logInfo("Audio playback initialized", tag = Logger.Tags.AUDIO)
            }
        }

        // Find device
        log("Searching for Carlinkit device...")
        val device = findDevice()
        if (device == null) {
            logError("Failed to find Carlinkit device", tag = Logger.Tags.USB)
            setState(State.DISCONNECTED)
            setStatusText("Adapter not found")
            return
        }

        log("Device found, opening")
        usbDevice = device
        setStatusText("Adapter found — opening...")

        // AltVideo (USB 0x2C) gate — evaluated each session start (cheap; PlatformDetector
        // is not cached). Both checks must pass:
        //   1. BuildConfig.DEBUG — production APKs are bit-identical to pre-feature behavior.
        //   2. PlatformDetector.isAaosEmulator() — Build.PRODUCT starts with "sdk_gcar".
        //      Real GM IHU + Pixel + everything else stays inert. ClusterHomeDisplay
        //      (the consumer priv-app) currently runs only on the AAOS emulator.
        // The same boolean is mirrored to MessageSerializer (gates naviScreenInfo JSON
        // -> adapter never emits 0x2C without it) and to NaviVideoSingleton (gates the
        // UsbDeviceWrapper demux split — defense in depth).
        val naviGateEnabled =
            BuildConfig.DEBUG && PlatformDetector.detect(context).isAaosEmulator()
        MessageSerializer.includeNaviScreenInfo = naviGateEnabled
        com.carlink.ipc.NaviVideoSingleton.enabled = naviGateEnabled
        logInfo(
            if (naviGateEnabled) {
                "[NAVI_GATE] AltVideo (0x2C) forwarding ENABLED — debug build on AAOS emulator. " +
                    "naviScreenInfo will be sent in BoxSettings; 0x2C frames will be forwarded to " +
                    "zeno.carlink.ipc.NaviVideoSourceService consumers."
            } else {
                "[NAVI_GATE] AltVideo (0x2C) forwarding disabled (debug=${BuildConfig.DEBUG}, " +
                    "emulator=${PlatformDetector.detect(context).isAaosEmulator()})"
            },
            tag = Logger.Tags.VIDEO,
        )

        if (!device.openWithPermission()) {
            logError("Failed to open USB device", tag = Logger.Tags.USB)
            setState(State.DISCONNECTED)
            setStatusText("USB permission denied")
            return
        }

        // Clear any stale adapter session left by a prior force-kill or crash.
        // The adapter firmware retains session state across USB reconnects. If the previous
        // app process was killed without sending DisconnectPhone+CloseDongle, the adapter
        // stays in PLUGGED/STREAMING state and ignores our OPEN command. Sending these
        // teardown commands before constructing AdapterDriver is safe on idle adapters (no-op).
        log("Clearing stale adapter session state")
        device.write(MessageSerializer.serializeDisconnectPhone())
        device.write(MessageSerializer.serializeCloseDongle())
        // Empirical 200ms floor — shorter values showed "adapter busy" errors on fast
        // reconnect paths. 3-host evidence for firmware 2025.10.15.1127CAY:
        // - POTATO GM AAOS (5 samples): CMD_STOP_PHONE_CONNECTION use-time 277-287ms,
        //   mean ~283ms. 200ms sleep is BELOW the envelope; raise to 300ms if races recur.
        // - AAOS emulator v120 (1 sample): 229ms use-time.
        // - macOS Carlink app SKIPS the cmd entirely and lets adapter self-teardown via
        //   "Host No Response" timeout — a viable alternative strategy. Cited:
        //   /Volumes/POTATO/cpc200/20260420/*.log, adapter_tty_215842_20APR26.log:845,
        //   adapter_tty_214729_20APR26.log:4983 (Host-No-Response path).
        Thread.sleep(200)

        // Create video processor for direct USB -> codec data flow
        // This bypasses message parsing for zero-copy performance (DIRECT_HANDOFF)
        val videoProcessor = createVideoProcessor()

        // Create and start adapter driver
        adapterDriver =
            AdapterDriver(
                usbDevice = device,
                messageHandler = ::handleMessage,
                errorHandler = ::handleError,
                logCallback = ::log,
                videoProcessor = videoProcessor,
            )

        // Determine initialization mode based on first-run state and pending changes
        val adapterConfigPref = AdapterConfigPreference.getInstance(context)
        val currentVersionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val initMode = adapterConfigPref.getInitializationMode(currentVersionCode)
        val pendingChanges = adapterConfigPref.getPendingChangesSync()

        // Refresh user-configurable settings from preference store before starting
        // This ensures changes made in Settings screen are applied on next connection
        // (display settings like width/height are kept from original config)
        val userConfig = adapterConfigPref.getUserConfigSync()
        val refreshedConfig =
            config.copy(
                audioTransferMode = userConfig.audioTransferMode,
                // Sample rate is hardcoded to 48kHz - not user configurable
                sampleRate = 48000,
                micType =
                    when (userConfig.micSource) {
                        MicSourceConfig.APP -> "os"
                        MicSourceConfig.PHONE -> "box"
                    },
                wifiType =
                    when (userConfig.wifiBand) {
                        WiFiBandConfig.BAND_5GHZ -> "5ghz"
                        WiFiBandConfig.BAND_24GHZ -> "24ghz"
                    },
                callQuality = userConfig.callQuality.value,
                fps = userConfig.fps.fps,
                handDriveMode = userConfig.handDrive.value,
                gpsForwarding = userConfig.gpsForwarding,
            )
        config = refreshedConfig // Update stored config for other uses

        log("[INIT] Mode: ${adapterConfigPref.getInitializationInfo(currentVersionCode)}")
        log("[INIT] Audio mode: ${if (refreshedConfig.audioTransferMode) "BLUETOOTH" else "ADAPTER"}")

        setStatusText("Initializing adapter...")
        val initSuccess = adapterDriver?.start(refreshedConfig, initMode.name, pendingChanges, actualSurfaceWidth, actualSurfaceHeight) ?: false

        // If a targeted connect was requested (user selected a specific device),
        // override the adapter's wifiConnect auto-connect timer with the target MAC.
        val targetMac = pendingConnectTarget
        if (targetMac != null) {
            pendingConnectTarget = null
            logInfo("[DEVICE_MGMT] Overriding auto-connect with targeted connect: $targetMac", tag = Logger.Tags.ADAPTR)
            adapterDriver?.overrideAutoConnectWithTarget(targetMac)
            setStatusText("Connecting to device...")
        } else {
            setStatusText("Waiting for phone...")
        }

        // Mark first init completed, store version, and clear pending changes
        // ONLY clear pending changes if all init messages were sent successfully —
        // otherwise the changes will be retried on next connection attempt.
        CoroutineScope(Dispatchers.IO).launch {
            if (initSuccess) {
                if (initMode == AdapterConfigPreference.InitMode.FULL) {
                    adapterConfigPref.markFirstInitCompleted()
                }
                adapterConfigPref.updateLastInitVersionCode(currentVersionCode)
                if (pendingChanges.isNotEmpty()) {
                    adapterConfigPref.clearPendingChanges()
                }
            } else {
                logWarn(
                    "[INIT] Init messages failed — pending changes preserved for retry",
                    tag = Logger.Tags.ADAPTR,
                )
            }
        }

        // Start pair timeout
        clearPairTimeout()
        pairTimeout =
            Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            adapterDriver?.sendCommand(CommandMapping.WIFI_PAIR)
                        }
                    },
                    PAIR_TIMEOUT_MS,
                )
            }
    }

    /**
     * Stop and disconnect.
     *
     * @param reboot when true, issues `rebootAdapter()` (0xCD) in addition to the graceful
     *   teardown; used by the Misc-changed config path in AdapterConfigurationDialog so a
     *   settings change that requires a firmware re-init doesn't leave the adapter in a
     *   stale state. When false (default), sends only the graceful-teardown message.
     */
    fun stop(reboot: Boolean = false) {
        logDebug("[LIFECYCLE] stop() called - clearing keyframe schedule and phoneType", tag = Logger.Tags.VIDEO)
        clearPairTimeout()
        cancelDelayedKeyframe()
        cancelReconnect() // Cancel any pending auto-reconnect
        negotiationRejected = false // Clear rejection flag for fresh connection
        hadPriorSession = false // Reset escalation — user-initiated fresh start
        consecutiveNoResponse = 0
        shortLivedStreamingCount = 0
        currentPhoneType = null // Clear phone type on disconnect
        currentWifi = null
        videoPhoneTypeInferred = false
        codecDeferred = true // Reset for next connection
        videoEncoderType = 2 // Reset to H265/initial
        videoOffScreen = 0
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata() // Clear stale metadata to prevent race conditions on reconnect
        activeVoiceMode = VoiceMode.NONE // Reset voice mode on disconnect
        isSiriAudioActive = false
        isPhoneCallAudioActive = false
        isAlertAudioActive = false // Reset purpose on disconnect
        stopMicrophoneCapture()

        // Stop GPS forwarding before stopping adapter (only if forwarder was created)
        if (gnssForwarder != null) {
            adapterDriver?.sendCommand(CommandMapping.STOP_GNSS_REPORT)
            gnssForwarder?.stop()
        }

        // Graceful teardown: notify adapter before killing connection.
        // Must happen before adapterDriver?.stop() (send() checks isRunning).
        adapterDriver?.sendGracefulTeardown(reboot = reboot)

        adapterDriver?.stop()
        adapterDriver = null

        usbDevice?.close()
        usbDevice = null

        // Stop audio
        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
            logInfo("Audio released on stop", tag = Logger.Tags.AUDIO)
        }

        dumpUnknownSummary()
        setState(State.DISCONNECTED)
    }

    /**
     * Disconnect the phone's CarPlay/AA session without stopping the adapter.
     * Sends protocol command 0x0F (DISCONNECT_PHONE) only.
     */
    fun disconnectPhone() {
        // NOTE: missing tag = Logger.Tags.* — intentional? All neighboring lifecycle logs
        // use tag='MAIN'; consider adding on next touch.
        logInfo("[LIFECYCLE] disconnectPhone() — sending 0x0F to end phone session")
        scope.launch(Dispatchers.IO) {
            adapterDriver?.disconnectPhone()
        }
    }

    // ==================== Device Management ====================

    /**
     * Request the adapter to send a fresh list of paired devices.
     * The adapter responds with BoxSettings (0x19) containing an updated DevList.
     * The UI is notified via [Callback.onDeviceListChanged].
     */
    fun refreshDeviceList() {
        logInfo("[DEVICE_MGMT] Requesting fresh device list", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) {
            adapterDriver?.sendGetBtOnlineList()
        }
    }

    /**
     * Connect to a specific paired device by BT MAC address.
     *
     * If currently streaming, disconnects the active phone first and waits for
     * the UNPLUGGED → restart cycle before sending the targeted connect.
     * If idle, sends the connect request immediately.
     *
     * @param btMac Target device BT MAC (format: "XX:XX:XX:XX:XX:XX")
     */
    fun connectToDevice(btMac: String) {
        logInfo("[DEVICE_MGMT] Connect to device: $btMac (current state=$state, wifi=$currentWifi)", tag = Logger.Tags.ADAPTR)

        // Set the pending target BEFORE disconnecting so the UNPLUGGED → restart cycle
        // sends AutoConnect_By_BtAddress instead of WIFI_CONNECT (1002).
        pendingConnectTarget = btMac
        lastConnectTargetMac = btMac

        scope.launch(Dispatchers.IO) {
            if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
                logInfo("[DEVICE_MGMT] Disconnecting current phone before targeted connect to $btMac", tag = Logger.Tags.ADAPTR)
                adapterDriver?.disconnectPhone()
                // UNPLUGGED handler will call restart() which checks pendingConnectTarget
            } else {
                // Not currently connected — send targeted connect directly
                val sent = adapterDriver?.sendAutoConnectByBtAddress(btMac) ?: false
                logInfo("[DEVICE_MGMT] AutoConnect_By_BtAddress($btMac) sent=$sent (direct, no active session)", tag = Logger.Tags.ADAPTR)
                pendingConnectTarget = null
            }
        }
    }

    /**
     * Remove a device from the adapter's paired list (DevList → DeletedDevList).
     * The adapter will no longer auto-connect to this device.
     * Refreshes the device list after removal.
     *
     * @param btMac Target device BT MAC (format: "XX:XX:XX:XX:XX:XX")
     */
    fun forgetDevice(btMac: String) {
        logInfo("[DEVICE_MGMT] Forget device: $btMac (list size=${_deviceList.size})", tag = Logger.Tags.ADAPTR)

        // Optimistically remove from local list immediately for responsive UI.
        // The adapter may take 10-20s to process and confirm via GET_BT_ONLINE_LIST.
        _deviceList = _deviceList.filter { it.btMac != btMac }
        callback?.onDeviceListChanged(_deviceList)
        notifyDeviceListeners()

        scope.launch(Dispatchers.IO) {
            val sent = adapterDriver?.sendForgetBluetoothAddr(btMac) ?: false
            logInfo("[DEVICE_MGMT] ForgetBluetoothAddr($btMac) sent=$sent", tag = Logger.Tags.ADAPTR)
            // Refresh list from adapter to confirm removal (adapter response may be slow)
            delay(1000)
            adapterDriver?.sendGetBtOnlineList()
        }
    }

    /**
     * Restart the connection.
     * Uses Dispatchers.IO for stop()/start() — both do blocking USB I/O
     * (bulkTransfer, thread joins) that must never run on the main thread.
     */
    suspend fun restart() {
        setStatusText("Restarting...")
        withContext(Dispatchers.IO) { stop() }
        delay(2000)
        withContext(Dispatchers.IO) { start() }
    }

    /**
     * Send a key command.
     */
    private fun sendKey(command: CommandMapping): Boolean = adapterDriver?.sendCommand(command) ?: false

    // AA MOVE rate-limit timestamp. carlink_native skips a MOVE send when <17ms
    // elapsed since the previous one. See [AA_TOUCH_THROTTLE_NS] for the full
    // explanation — and the important distinction from AutoKit's use of the same
    // 17ms number (AutoKit's 17ms is a stationary-finger MOVE repeater, NOT a
    // rate limit).
    @Volatile private var lastAaTouchSendTimeNs = 0L

    /**
     * Send a multi-touch event (CarPlay) or single-touch event (Android Auto).
     * AA uses type 0x05 with 0..10000 ints; CarPlay uses type 0x17 with 0..1 floats.
     * Dispatched to IO — USB bulkTransfer must never block the UI thread.
     *
     * On the AA path, MOVE events are rate-limited to one per [AA_TOUCH_THROTTLE_NS]
     * window. DOWN and UP are always sent immediately. This is a carlink_native-side
     * rate limit, NOT an imitation of AutoKit; see the constant's KDoc for details.
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>) {
        val driver = adapterDriver ?: return
        val isAA = currentPhoneType == PhoneType.ANDROID_AUTO
        scope.launch(Dispatchers.IO) {
            if (isAA && touches.isNotEmpty()) {
                // AA: send primary pointer as single-touch (type 0x05, 0..10000 ints)
                val primary = touches.first()

                // Rate-limit MOVE events to one per [AA_TOUCH_THROTTLE_NS] window.
                // Not an AutoKit-compatibility behavior — AutoKit sends every MOVE
                // immediately. See the constant's KDoc for the full comparison.
                if (primary.action == MultiTouchAction.MOVE) {
                    val now = System.nanoTime()
                    if (now - lastAaTouchSendTimeNs < AA_TOUCH_THROTTLE_NS) return@launch
                    lastAaTouchSendTimeNs = now
                }

                val xi = (primary.x * 10000).toInt()
                val yi = (primary.y * 10000).toInt()
                if (BuildConfig.DEBUG) {
                    logDebug(
                        "[AA_TOUCH] type=0x05 action=${primary.action} x=$xi y=$yi" +
                            " (norm=${primary.x},${primary.y})",
                        tag = Logger.Tags.TOUCH,
                    )
                }
                driver.sendSingleTouch(
                    x = xi,
                    y = yi,
                    action = primary.action,
                    encoderType = videoEncoderType,
                    offScreen = videoOffScreen,
                )
            } else {
                driver.sendMultiTouch(touches)
            }
        }
    }

    fun rebootAdapter() {
        // NOTE: Does NOT reset `videoPhoneTypeInferred` — if the phone type was already
        // inferred from video header flags in the prior session, the flag persists into
        // the next. This is intentional: the physical hardware identity doesn't change
        // across a reboot, so the inference is still valid and re-inferring wastes a frame.
        //
        // NOTE: `hadPriorSession` is intentionally NOT reset here (it's only reset in
        // stop()). Preserving it keeps Pattern A/B/C escalation context across mid-session
        // failures, so a user who hits "reboot adapter" after several short sessions still
        // gets the right escalation messaging on the next attempt.
        //
        // Known gap: cancelDelayedKeyframe() is NOT called here. Any pending CarPlay periodic
        // FRAME job (frameIntervalJob) continues for the old adapterDriver (which is now
        // null) — sendCommand is null-safe so it's harmless, but the job wakes every 30s
        // until a subsequent stop()/handleError()/PLUGGED clears it.
        //
        // Escalation counters (consecutiveNoResponse, shortLivedStreamingCount) are
        // deliberately preserved here — reboot is user-initiated recovery, not a clean-slate
        // reset. If you want reboot to clear escalation state too, reset them here.
        logWarn("[LIFECYCLE] Reboot adapter requested", tag = Logger.Tags.ADAPTR)
        cancelReconnect()
        stopMicrophoneCapture()
        adapterDriver?.rebootAdapter()
        adapterDriver?.stop()
        adapterDriver = null
        usbDevice?.close()
        usbDevice = null
        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
        }
        codecDeferred = true // Reset for next connection
        currentPhoneType = null
        currentWifi = null
        pendingConnectTarget = null
        lastConnectTargetMac = null
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        activeVoiceMode = VoiceMode.NONE
        isSiriAudioActive = false
        isPhoneCallAudioActive = false
        isAlertAudioActive = false
        clearCachedMediaMetadata()
        setState(State.DISCONNECTED)
    }

    /**
     * Release all resources.
     *
     * IRREVERSIBLE: cancels the coroutine scope via `scope.cancel()`. No coroutine launched
     * on [scope] can be revived afterwards — a new [CarlinkManager] instance must be created
     * for subsequent use. Must be the LAST call on this instance.
     *
     * Releases owned subsystems in LIFO order relative to initialization: stop() first (which
     * winds down the session and adapter driver), then H264Renderer, DualStreamAudioManager,
     * MicrophoneCaptureManager, GnssForwarder, MediaSessionManager, finally scope.cancel().
     */
    fun release() {
        stop()

        h264Renderer?.stop()
        h264Renderer = null

        audioManager?.release()
        audioManager = null

        microphoneManager?.stop()
        microphoneManager = null

        gnssForwarder?.stop()
        gnssForwarder = null

        // MediaSession is app-scope owned by MainActivity — do NOT release here.
        //
        // Detaching the transport callback is sufficient: this CarlinkManager instance
        // must stop receiving routed Play/Pause/Skip events (it's about to be garbage),
        // but the MediaLibrarySession itself must survive because CarLauncher's
        // homescreen Media card holds a MediaControllerCompat bound to the session
        // token. Releasing the session would fire onSessionDestroyed on every bound
        // controller; AOSP CarLauncher does not rebind to newly-minted tokens (see
        // MediaSessionManager KDoc "KNOWN OS DEFICIENCY" + documents/diagnostics/ diff
        // from 2026-04-23 which empirically demonstrates the blank-card failure mode).
        //
        // The app-scope MediaSessionManager is released exactly once from
        // MainActivity.onDestroy.
        mediaSessionManager?.setMediaControlCallback(null)
        mediaSessionManager = null

        // Cancel coroutine scope to stop any in-flight coroutines that hold
        // references to this manager and its context
        scope.cancel()

        logInfo("CarlinkManager released", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Handle USB device detachment event.
     * Called by MainActivity when USB_DEVICE_DETACHED broadcast is received.
     *
     * This provides immediate detection of physical adapter removal,
     * rather than waiting for USB transfer errors.
     */
    fun onUsbDeviceDetached() {
        logWarn("[USB] Device detached broadcast received", tag = Logger.Tags.USB)

        // Only handle if we have an active connection
        if (state == State.DISCONNECTED) {
            logInfo("[USB] Already disconnected, ignoring detach", tag = Logger.Tags.USB)
            return
        }

        // Trigger recovery through the error handler path
        // This ensures consistent recovery behavior
        handleError("USB device physically disconnected")
    }

    /**
     * Resets the H.264 video decoder/renderer.
     *
     * This operation resets the MediaCodec decoder without disconnecting the USB device.
     * Useful for recovering from video decoding errors or codec issues.
     *
     */
    fun resetVideoDecoder() {
        logInfo("[DEVICE_OPS] Resetting H264 video decoder", tag = Logger.Tags.VIDEO)
        h264Renderer?.reset()
        logInfo("[DEVICE_OPS] H264 video decoder reset completed", tag = Logger.Tags.VIDEO)
        // reset() already sends a keyframe request via keyframeCallback — no additional FRAME needed
    }

    /**
     * Handle Surface destruction - pause codec IMMEDIATELY.
     *
     * CRITICAL: This is called when SurfaceView's Surface is destroyed, which happens
     * BEFORE onStop() is called. If we wait for onStop(), the codec will try to render
     * to a dead surface causing "BufferQueue has been abandoned" errors.
     *
     * Call this from VideoSurface's onSurfaceDestroyed callback.
     */
    fun onSurfaceDestroyed() {
        logInfo("[LIFECYCLE] Surface destroyed - pausing codec immediately", tag = Logger.Tags.VIDEO)

        // Cancel any pending surface updates
        surfaceUpdateJob?.cancel()
        surfaceUpdateJob = null
        pendingSurface = null

        // Clear surface reference - it's now invalid
        videoSurface = null

        // Stop codec immediately - surface is dead
        h264Renderer?.stop()
    }

    /**
     * Start the codec if it was deferred. Called when:
     * - PLUGGED(CarPlay): start immediately with current surface
     * - AA surface resize complete: new surface available at tier AR
     * - Video arrives before PLUGGED: fallback start with current surface
     */
    fun startCodecIfDeferred() {
        if (!codecDeferred) return
        val renderer = h264Renderer ?: return
        // Use CarlinkManager.videoSurface (updated by debounce) — NOT renderer's
        // internal surface, which may be null if surfaceDestroyed/Created happened
        // during Compose layout after initialize() but before PLUGGED arrived.
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logWarn(
                "[LIFECYCLE] Deferred codec start skipped — no valid surface",
                tag = Logger.Tags.VIDEO,
            )
            return // Leave codecDeferred=true; debounce or resumeVideo() will retry
        }
        codecDeferred = false
        logInfo("[LIFECYCLE] Starting deferred codec", tag = Logger.Tags.VIDEO)
        renderer.resume(surface)
    }

    /**
     * Compute AA center-crop parameters from the current adapter config.
     * Returns null for CarPlay, 16:9 displays, or when config has no native dims.
     * Uses config.width/height (video surface dims) to match serializeBoxSettings AR calc.
     */
    fun getAaCropParams(): AaCropParams? {
        // Use actual surface dims (ground truth from Compose layout), not config dims
        // (which come from WindowMetrics and may differ due to AAOS dock inset behavior).
        val surfW = if (actualSurfaceWidth > 0) actualSurfaceWidth else config.width
        val surfH = if (actualSurfaceHeight > 0) actualSurfaceHeight else config.height
        if (surfW <= 0 || surfH <= 0) return null

        val (tierWidth, tierHeight) =
            if (surfH > surfW) {
                when {
                    surfW >= 1080 -> Pair(1080, 1920)
                    surfW >= 720 -> Pair(720, 1280)
                    else -> Pair(480, 800)
                }
            } else {
                when {
                    surfW >= 1920 -> Pair(1920, 1080)
                    surfW >= 1280 -> Pair(1280, 720)
                    else -> Pair(800, 480)
                }
            }
        val displayAR = surfW.toFloat() / surfH.toFloat()
        val tierAR = tierWidth.toFloat() / tierHeight.toFloat()
        // Two-way fit: baked bars sit on whichever tier axis the display does NOT bind.
        // displayAR >= tierAR → bars top/bottom (crop vertically); else → bars left/right.
        return if (displayAR >= tierAR) {
            val contentHeight = ((tierWidth.toFloat() / displayAR).toInt() and 0xFFFE).coerceAtMost(tierHeight)
            if (contentHeight >= tierHeight) return null // exact AR match — no crop
            AaCropParams(tierWidth, tierHeight, tierWidth, contentHeight, 0, (tierHeight - contentHeight) / 2)
        } else {
            val contentWidth = ((tierHeight.toFloat() * displayAR).toInt() and 0xFFFE).coerceAtMost(tierWidth)
            if (contentWidth >= tierWidth) return null // exact AR match — no crop
            AaCropParams(tierWidth, tierHeight, contentWidth, tierHeight, (tierWidth - contentWidth) / 2, 0)
        }
    }

    /**
     * Resend BoxSettings to the adapter with current surface dimensions.
     * Called when display mode changes during an AA session so the phone
     * re-renders content for the new container size.
     */
    fun resendBoxSettings() {
        val driver = adapterDriver ?: return
        val w = actualSurfaceWidth
        val h = actualSurfaceHeight
        if (w <= 0 || h <= 0) return
        logInfo(
            "[LIFECYCLE] Resending BoxSettings for new surface ${w}x$h",
            tag = Logger.Tags.VIDEO,
        )
        val msg = MessageSerializer.serializeBoxSettings(config, surfaceWidth = w, surfaceHeight = h)
        scope.launch(Dispatchers.IO) { driver.send(msg) }
    }

    /**
     * Pause video decoding when app goes to background.
     *
     * On AAOS, when the app is covered by another app (e.g., Maps, Phone), the Surface
     * may remain valid but SurfaceFlinger stops consuming frames. This causes the
     * BufferQueue to fill up, stalling the decoder. When the user returns, video
     * appears blank while audio continues normally.
     *
     * This method flushes the codec to prevent BufferQueue stalls. The USB connection
     * and audio playback continue unaffected.
     *
     * NOTE: Surface destruction is handled separately by onSurfaceDestroyed() which
     * is called when the Surface is actually destroyed (may be before or after onStop).
     *
     * Call this from Activity.onStop().
     */
    fun pauseVideo() {
        logInfo("[LIFECYCLE] Pausing video for background", tag = Logger.Tags.VIDEO)
        cancelDelayedKeyframe()
        h264Renderer?.stop()
    }

    /**
     * Resume video decoding when app returns to foreground.
     *
     * After pauseVideo(), the codec is in a flushed state. This method restarts the
     * codec and requests a keyframe so video can resume immediately.
     *
     * NOTE: The main surface update happens in initialize() when the new Surface is created.
     * If onStart() is called before the Surface is ready, we skip resume here and let
     * initialize() handle it when the Surface becomes available.
     *
     * Call this from Activity.onStart().
     */
    fun resumeVideo() {
        logInfo("[LIFECYCLE] Resuming video for foreground", tag = Logger.Tags.VIDEO)

        // If surface is null (destroyed and not yet recreated), skip resume.
        // initialize() will handle resume when new Surface becomes available.
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logInfo(
                "[LIFECYCLE] Surface not ready yet - resume will happen via initialize()",
                tag = Logger.Tags.VIDEO,
            )
            return
        }

        // Pass current surface to resume
        h264Renderer?.resume(surface)

        // Request keyframe so video recovers immediately after background
        if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
            if (currentPhoneType == PhoneType.ANDROID_AUTO) {
                // AA: single keyframe request (periodic FRAME commands reset phone UI)
                val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                logInfo("[LIFECYCLE] AA resume keyframe sent=$sent", tag = Logger.Tags.VIDEO)
            } else {
                // CarPlay: restart the periodic keyframe timer (2.5s initial + 30s periodic)
                scheduleDelayedKeyframe()
            }
        }
    }

    /**
     * Recover video after settings overlay closes.
     * Flushes the codec to release stalled BufferQueue buffers, then requests
     * a keyframe (CarPlay only — AA keyframe resets phone UI).
     */
    fun recoverVideoFromOverlay() {
        if (state != State.STREAMING) return
        logInfo("[LIFECYCLE] Recovering video after overlay close (phoneType=$currentPhoneType)", tag = Logger.Tags.VIDEO)
        h264Renderer?.flushCodec()
        // Request one keyframe for both CarPlay and AA.
        // Periodic FRAME commands reset AA phone UI, but a single recovery keyframe
        // after overlay close is necessary — the user is already seeing a frozen frame.
        // Waiting for a natural IDR (~60s) leaves the UI visually unresponsive.
        val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
        logDebug("[LIFECYCLE] Post-overlay keyframe request sent=$sent", tag = Logger.Tags.VIDEO)
    }

    // ==================== Private Methods ====================

    private fun setState(newState: State) {
        // HAZARD: the callback dispatch (`callback?.onStateChanged`) and
        // `updateMediaSessionState` fire OUTSIDE the `getAndSet` CAS. Two concurrent
        // setState calls could therefore dispatch in unspecified order, even though each
        // individual getAndSet is atomic. Today this is safe because setState is called
        // from (a) the single USB read thread (via handleMessage) and (b) the main thread
        // at non-overlapping lifecycle phases — but the invariant is NOT enforced by the
        // AtomicReference; it's a property of the callers. If a third caller site appears
        // on a different thread, wrap the whole body in a lock.
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            callback?.onStateChanged(newState)
            updateMediaSessionState(newState)
        }
    }

    private fun setStatusText(text: String) {
        callback?.onStatusTextChanged(text)
        // Mirror status text to MediaSession placeholder so cluster, cardview, and
        // CarMediaApp surfaces all show the same user-facing state the main app UI
        // shows as the adapter transitions through DISCONNECTED → CONNECTING →
        // DEVICE_CONNECTED phases (Searching for adapter, Initializing, Waiting
        // for phone, Phone connected, Reconnecting, etc.). Single source of truth.
        //
        // Gate on state != STREAMING — once real CarPlay/AA track metadata is
        // flowing via processMediaMetadata → MediaSessionManager.publishMetadata,
        // status text would clobber the live track title/artist. The setStatusText
        // call sites during STREAMING (e.g. "Adapter not responding — reconnecting")
        // are state-recovery transitions where placeholder text is no longer
        // appropriate; the next state transition (back to CONNECTING) will rearm
        // the mirror.
        if (state != State.STREAMING) {
            mediaSessionManager?.updatePlaceholderArtist(text)
        }
    }

    /**
     * Wire the app's projection state machine to the MediaSession active-flag.
     *
     * The session is INACTIVE by default (DISCONNECTED / CONNECTING) and ACTIVE only
     * once the adapter reports PLUGGED (DEVICE_CONNECTED). This keeps Carlink out of
     * AAOS's playback-primary slot when there's no phone actually projecting, letting
     * other media apps promote freely.
     *
     * KNOWN OS DEFICIENCY — stale homescreen Media card after force-stop
     * AAOS platform bug, not app-specific. Verified 2026-04-21 on AAOS emulator +
     * GM AAOS, and reproduced identically on Apple Music 5.2.1 — a first-party
     * vendor app using stock MediaBrowserService/MediaSession. Any media app in
     * the AAOS ecosystem hits this; by extension the same failure mode is expected
     * on the GM WidgetPanel media widget and GM Cluster media panel (they consume
     * MediaSession through the same plumbing).
     *
     * Symptom: force-stopping the app and relaunching leaves CarLauncher's homescreen
     * Media card blank (only the app name shows, no metadata/artwork) even though the
     * new session reports ACTIVE + PLAYING and CarMediaService has the app set as
     * playback-primary. Root cause lives inside CarLauncher's PlaybackViewModel: it
     * caches the pre-force-stop MediaController reference and never rebinds to the
     * new session token on the inactive→active transition. Neither this refactor
     * nor an emulator restart fixes it; only a full package uninstall + reinstall
     * + emulator restart clears the cached state. See MediaSessionManager class
     * KDoc "KNOWN OS DEFICIENCY" for the full analysis. This transition wiring is
     * kept because it is semantically correct, not because it resolves any visible bug.
     */
    private fun updateMediaSessionState(state: State) {
        when (state) {
            State.CONNECTING -> {
                // Adapter is attached (USB handshake in progress). Activate the
                // MediaSession NOW with STATE_BUFFERING + playWhenReady=true so AOSP
                // CarMediaService.MediaControllerCallback.onPlaybackStateChanged sees
                // a "preparing to play" arbitration signal at USB-attach time, matching
                // v113's setStateConnecting behavior. Without this, the session stays
                // STATE_IDLE through CONNECTING and only goes BUFFERING on PLUGGED —
                // by which time an already-PLAYING source (e.g., FM) is undisplaceable
                // per CarMediaService rule "newly-PLAYING displaces non-PLAYING only".
                // See verification thread + Agent 2 AOSP source citation, 2026-05-02.
                //
                // connectingPhase=true publishes "Connecting to phone..." so cluster +
                // cardview + CarMediaApp surfaces show meaningful state during handshake.
                mediaSessionManager?.setProjectionActive(connectingPhase = true)
                // Start foreground service early to maintain process priority during handshake
                CarlinkMediaBrowserService.startConnectionForeground(context)
                // Acquire wake lock to ensure USB operations aren't interrupted
                acquireWakeLock()
            }

            State.DISCONNECTED -> {
                // No adapter / no phone — session fully inactive. AAOS consumers are
                // free to promote other media apps to playback primary.
                mediaSessionManager?.setInactive()
                // Clear now-playing and stop foreground service
                CarlinkMediaBrowserService.clearNowPlaying()
                CarlinkMediaBrowserService.stopConnectionForeground(context)
                // Release wake lock - CPU can sleep now
                releaseWakeLock()
            }

            State.DEVICE_CONNECTED -> {
                // PLUGGED event received — phone is attached to adapter and iAP2/AA
                // handshake has identified it as CarPlay or Android Auto. Session was
                // already activated at CONNECTING; re-publish with phase-specific text
                // ("Waiting for media...") since the user can now see we're past the
                // handshake and just waiting for the first MEDIA_DATA frame.
                mediaSessionManager?.setProjectionActive(connectingPhase = false)
                // Keep FGS up (idempotent if already started at CONNECTING).
                CarlinkMediaBrowserService.startConnectionForeground(context)
            }

            State.STREAMING -> {
                // Defensive: ensure FGS is running (idempotent if already started at CONNECTING)
                CarlinkMediaBrowserService.startConnectionForeground(context)
                // Ensure wake lock is held during streaming
                acquireWakeLock()
                // Start GPS forwarding to CarPlay (only if enabled in settings)
                if (config.gpsForwarding) {
                    gnssForwarder?.start()
                }
                // MediaSession is already ACTIVE from the DEVICE_CONNECTED transition.
                // MEDIA_DATA frames drive metadata/playback-state via
                // MediaSessionManager.updateMetadata/updatePlaybackState — no explicit
                // active-flip needed here.
            }
        }
    }

    /**
     * Acquires a partial wake lock to prevent CPU sleep during USB streaming.
     * This ensures USB transfers and heartbeats continue when the app is backgrounded.
     */
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(2 * 60 * 60 * 1000L) // 2h safety timeout — released explicitly on DISCONNECTED
            logInfo("[WAKE_LOCK] Acquired partial wake lock for USB streaming", tag = Logger.Tags.USB)
        }
    }

    /**
     * Releases the wake lock, allowing CPU to sleep.
     */
    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            logInfo("[WAKE_LOCK] Released wake lock", tag = Logger.Tags.USB)
        }
    }

    private suspend fun findDevice(): UsbDeviceWrapper? {
        var device: UsbDeviceWrapper? = null
        var attempts = 0

        while (device == null && attempts < 10) {
            device = UsbDeviceWrapper.findFirst(context, usbManager) { log(it) }

            if (device == null) {
                attempts++
                delay(USB_WAIT_PERIOD_MS)
            }
        }

        if (device != null) {
            log("Carlinkit device found!")
        }

        return device
    }

    private fun handleMessage(message: Message) {
        // HAZARD: runs on the USB read thread (single-threaded per AdapterDriver contract).
        // Several branches below do synchronous, non-trivial work that technically violates
        // AdapterDriver's "return quickly" contract:
        //   - `tryPrestageCodecCsd` performs a SharedPreferences read + Base64 decode.
        //   - `notifyDeviceListeners` invokes listener callbacks synchronously on this thread.
        //   - `audioManager?.writeAudio` can potentially block on AudioTrack state.
        // This survives today because SharedPreferences is effectively cached by the OS after
        // first read and AudioTrack writes rarely block in the non-blocking modes we use. Any
        // new work added here should either be trivial or dispatched to a background scope.
        when (message) {
            is PluggedMessage -> {
                // Store wifi status for UI (0=USB, 1=wireless)
                currentWifi = message.wifi

                logInfo(
                    "[PLUGGED] Device plugged: phoneType=${message.phoneType}, wifi=${message.wifi}",
                    tag = Logger.Tags.VIDEO,
                )
                if (message.phoneType == PhoneType.UNKNOWN) {
                    unknownPhoneTypeCount++
                    logWarn(
                        "[PLUGGED] Unknown phoneType raw id=${message.rawPhoneType} " +
                            "(0x${message.rawPhoneType.toString(16)})",
                        tag = Logger.Tags.PROTO_UNKNOWN,
                    )
                }
                clearPairTimeout()
                cancelDelayedKeyframe() // Stop any existing timer (clean slate)

                // Reset reconnect attempts and escalation on successful connection
                reconnectAttempts = 0
                consecutiveNoResponse = 0
                shortLivedStreamingCount = 0
                hadPriorSession = true

                // Store phone type for keyframe request decisions during recovery
                currentPhoneType = message.phoneType
                logDebug("[PLUGGED] Stored currentPhoneType=$currentPhoneType", tag = Logger.Tags.VIDEO)

                // Infer connected BT MAC if not yet known (adapter may not send
                // PeerBluetoothAddress or BoxSettings #2 in every session).
                if (_connectedBtMac == null) {
                    // Priority 1: user explicitly selected this device via connectToDevice()
                    val targetMac = lastConnectTargetMac
                    if (targetMac != null) {
                        _connectedBtMac = targetMac
                        lastConnectTargetMac = null
                        logInfo("[PLUGGED] Set connectedBtMac=$targetMac from user-selected target", tag = Logger.Tags.ADAPTR)
                    } else if (_deviceList.isNotEmpty()) {
                        // Priority 2: match PLUGGED phoneType against DevList entries by type
                        val typeMatch = when (message.phoneType) {
                            PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> "CarPlay"
                            PhoneType.ANDROID_AUTO -> "AndroidAuto"
                            PhoneType.HI_CAR -> "HiCar"
                            else -> null
                        }
                        if (typeMatch != null) {
                            val matched = _deviceList.filter { it.type == typeMatch }
                            if (matched.size == 1) {
                                _connectedBtMac = matched[0].btMac
                                logInfo("[PLUGGED] Inferred connectedBtMac=${_connectedBtMac} from DevList (type=$typeMatch)", tag = Logger.Tags.ADAPTR)
                            } else {
                                logDebug("[PLUGGED] Cannot infer MAC: ${matched.size} devices match type=$typeMatch", tag = Logger.Tags.ADAPTR)
                            }
                        }
                    }
                } else {
                    // Already known — clear the target since connection succeeded
                    lastConnectTargetMac = null
                }

                // Enrich device list: if connected device has unknown type (came from
                // BluetoothPairedList which doesn't carry type), update it from PLUGGED phoneType.
                val connMac = _connectedBtMac
                if (connMac != null) {
                    val typeStr = when (message.phoneType) {
                        PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> "CarPlay"
                        PhoneType.ANDROID_AUTO -> "AndroidAuto"
                        PhoneType.HI_CAR -> "HiCar"
                        else -> null
                    }
                    if (typeStr != null) {
                        val device = _deviceList.find { it.btMac == connMac }
                        if (device != null && device.type.isEmpty()) {
                            _deviceList = _deviceList.map {
                                if (it.btMac == connMac) it.copy(type = typeStr) else it
                            }
                            logInfo("[PLUGGED] Enriched device $connMac type → $typeStr", tag = Logger.Tags.ADAPTR)
                            callback?.onDeviceListChanged(_deviceList)
                            notifyDeviceListeners()
                        }
                    }
                }

                // Set AA mode on renderer for AA-specific behaviors:
                // first-frame skip, frame cache replay, crop scaling mode
                h264Renderer?.setAndroidAutoMode(message.phoneType == PhoneType.ANDROID_AUTO)

                // For CarPlay: start codec now (no surface resize needed).
                // For AA: onPhoneTypeChanged triggers UI resize → surfaceDestroyed/Created →
                // debounce path calls startCodecIfDeferred() with the new oversized surface.
                if (message.phoneType != PhoneType.ANDROID_AUTO) {
                    startCodecIfDeferred()
                }

                callback?.onPhoneTypeChanged(message.phoneType)

                // Enable GPS forwarding for CarPlay navigation (only if enabled in settings)
                if (config.gpsForwarding) {
                    adapterDriver?.sendCommand(CommandMapping.START_GNSS_REPORT)
                }

                setState(State.DEVICE_CONNECTED)
                setStatusText("Phone connected — starting session...")

                // Proactively kick AltVideo (0x2C) streaming — step 1 of the three-step 508
                // handshake (see RE_Documention/02_Protocol_Reference/video_protocol.md
                // "Handshake Sequence"). On wireless CarPlay the adapter never initiates with
                // 508 — it sends only 506 (audio nav focus) — so without this proactive send
                // the handshake never starts and _AltScreenSetup is never invoked.
                // Step 3 (echo the adapter's 508 reply) lives in the CommandMessage handler.
                // Gated on debug+emulator + CarPlay (AA has no 0x2C path).
                if (com.carlink.ipc.NaviVideoSingleton.enabled &&
                    (message.phoneType == PhoneType.CARPLAY ||
                        message.phoneType == PhoneType.CARPLAY_WIRELESS)
                ) {
                    scope.launch {
                        // Small delay lets the adapter finish its post-PLUGGED handshake
                        // (REQUEST_VIDEO_FOCUS / REQUEST_NAVI_FOCUS arrive ~3-4s after PLUGGED).
                        // Send 508 after that so the adapter's session-init has set
                        // g_bSupportNaviScreen and the iPhone has registered featureAltScreen.
                        delay(2000)
                        val sent = adapterDriver?.sendCommand(
                            CommandMapping.REQUEST_NAVI_SCREEN_FOCUS,
                        ) ?: false
                        logInfo(
                            "[NAVI_FWD] Proactive REQUEST_NAVI_SCREEN_FOCUS (508) sent=$sent " +
                                "— kicking 0x2C AltVideo streaming",
                            tag = Logger.Tags.VIDEO,
                        )
                    }
                }
            }

            is UnpluggedMessage -> {
                if (negotiationRejected) {
                    logDebug(
                        "[PHASE] Unplugged after negotiation rejection — not restarting",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    setStatusText("Phone unplugged")
                    scope.launch {
                        restart()
                    }
                }
            }

            // VideoStreamingSignal indicates video data was processed directly by videoProcessor
            // No data to process here - just update state
            VideoStreamingSignal -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started (direct processing)", tag = Logger.Tags.VIDEO)
                    lastStreamingStartMs = System.currentTimeMillis()
                    setState(State.STREAMING)
                    setStatusText("Streaming")

                    if (currentPhoneType == PhoneType.ANDROID_AUTO) {
                        // AA needs one keyframe request at session start to stabilize video.
                        // Unlike CarPlay, AA must NOT receive periodic FRAME commands (resets phone UI).
                        val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                        logInfo("[FRAME_INTERVAL] AA one-shot keyframe request sent=$sent", tag = Logger.Tags.VIDEO)
                    } else {
                        // CarPlay: delayed keyframe request 2.5s after first video.
                        // The adapter's natural SPS+PPS+IDR arrives at session start and decodes
                        // immediately. This delayed request serves as a safety net for cold-start
                        // decoder poisoning — empirically an Intel/MediaCodec-specific risk:
                        // - POTATO GM AAOS gminfo37 OMX.Intel.hw_vd.h264: 1-of-3 sessions had
                        //   Rx=48/Dec=0 zombie reset ~4min in, recovered via 2.5s IDR + 30s periodic.
                        // - macOS Carlink (VTDecompression/AVFoundation, firmware 2025.10.15.1127CAY):
                        //   0 resets across 24-minute session with ONLY 1 FRAME cmd at start.
                        // - AAOS emulator v120 (c2.goldfish.h264.decoder): 30s periodic FRAME
                        //   confirmed, 0 poisoning events across 3min.
                        // The 30s periodic is conservative defense for Intel MediaCodec; host-side
                        // FRAME cadence is policy, not firmware/iPhone requirement.
                        scheduleDelayedKeyframe()
                    }
                }
                // Video data already processed directly by videoProcessor (DIRECT_HANDOFF)
            }

            is AudioDataMessage -> {
                clearPairTimeout()
                processAudioData(message)
            }

            is MediaDataMessage -> {
                clearPairTimeout()
                processMediaMetadata(message)
            }

            is CommandMessage -> {
                if (message.command == CommandMapping.REQUEST_HOST_UI) {
                    callback?.onHostUIPressed()
                } else if (message.command == CommandMapping.WIFI_DISCONNECTED) {
                    // WiFi status notification - adapter's WiFi hotspot has no phone connected
                    // This is informational only, NOT a session termination signal
                    // Real disconnects come via UnpluggedMessage (0x04)
                    logDebug("[WIFI] Adapter WiFi status: not connected", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.SCANNING_DEVICE) {
                    if (hadPriorSession && reconnectAttempts > 0) {
                        // Pattern B: adapter alive and scanning, but phone lost after a prior session.
                        // Adapter's wireless subsystem may be stuck.
                        setStatusText("Adapter scanning — phone not reconnecting")
                        logWarn(
                            "[ESCALATION] Pattern B: adapter scanning after prior session " +
                                "(reconnect attempt $reconnectAttempts)",
                            tag = Logger.Tags.ADAPTR,
                        )
                    } else {
                        setStatusText("Scanning for phone...")
                    }
                    logDebug("[CMD] SCANNING_DEVICE", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.BT_CONNECTED ||
                    message.command == CommandMapping.DEVICE_FOUND
                ) {
                    setStatusText("Phone found — connecting...")
                    logDebug("[CMD] ${message.command.name}", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.REQUEST_NAVI_SCREEN_FOCUS) {
                    // Step 3 of the three-step 508 handshake: adapter sent 508 back in reply to
                    // our proactive step-1 send (CommandMessage handler in PluggedMessage at ~L1805).
                    // Echo another 508 to confirm; the adapter then completes _AltScreenSetup
                    // and starts emitting Type 0x2C. See RE_Documention/02_Protocol_Reference/
                    // video_protocol.md "Handshake Sequence" for the wire diagram.
                    // Gated on NaviVideoSingleton.enabled so production APKs stay inert.
                    logDebug(
                        "[CMD] REQUEST_NAVI_SCREEN_FOCUS (id=508) received from adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    if (com.carlink.ipc.NaviVideoSingleton.enabled) {
                        val sent = adapterDriver?.sendCommand(
                            CommandMapping.REQUEST_NAVI_SCREEN_FOCUS,
                        ) ?: false
                        logInfo(
                            "[NAVI_FWD] Echoed 508 back to adapter sent=$sent — completing focus handshake",
                            tag = Logger.Tags.VIDEO,
                        )
                    }
                } else if (message.command == CommandMapping.RELEASE_NAVI_SCREEN_FOCUS) {
                    // Adapter signaled end of nav video. Echo 509 back and notify the
                    // forwarder so consumer overlays hide deterministically.
                    logDebug(
                        "[CMD] RELEASE_NAVI_SCREEN_FOCUS (id=509) received from adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    if (com.carlink.ipc.NaviVideoSingleton.enabled) {
                        adapterDriver?.sendCommand(CommandMapping.RELEASE_NAVI_SCREEN_FOCUS)
                        com.carlink.ipc.NaviVideoSingleton.forwarder.onStreamStop()
                        logInfo(
                            "[NAVI_FWD] Echoed 509 back + stopped forwarder",
                            tag = Logger.Tags.VIDEO,
                        )
                    }
                } else if (message.command == CommandMapping.INVALID) {
                    unknownCommandCount++
                    unknownCommandIds.add(message.rawId)
                    logWarn(
                        "[CMD] Unknown command id=${message.rawId} (0x${message.rawId.toString(16)})",
                        tag = Logger.Tags.PROTO_UNKNOWN,
                    )
                } else {
                    logDebug(
                        "[CMD] ${message.command.name} (id=${message.rawId})",
                        tag = Logger.Tags.ADAPTR,
                    )
                }
            }

            is BoxSettingsMessage -> {
                if (message.isPhoneInfo) {
                    // BoxSettings #2: phone connected — has MDModel, btMacAddr
                    val phoneModel = message.json.optString("MDModel", "").ifEmpty { null }
                    val btMac = message.json.optString("btMacAddr", "").ifEmpty { null }
                    if (btMac != null) {
                        _connectedBtMac = btMac
                        tryPrestageCodecCsd(btMac)
                    }
                    // Phone link metadata (sent after phone connects)
                    val linkType = message.json.optString("MDLinkType", "").ifEmpty { null }
                    val osVersion = message.json.optString("MDOSVersion", "").ifEmpty { null }
                    val linkVersion = message.json.optString("MDLinkVersion", "").ifEmpty { null }
                    val cpuTemp = message.json.optInt("cpuTemp", -1).takeIf { it >= 0 }
                    logInfo(
                        "[DEVICE] Phone identified: model=$phoneModel, bt=$btMac" +
                            (if (linkType != null) ", link=$linkType" else "") +
                            (if (osVersion != null) ", os=$osVersion" else "") +
                            (if (linkVersion != null) ", ver=$linkVersion" else "") +
                            (if (cpuTemp != null) ", cpuTemp=${cpuTemp}°C" else ""),
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    // BoxSettings #1: adapter info — has DevList of previously paired devices
                    _deviceList = parseDevList(message.json)
                    callback?.onDeviceListChanged(_deviceList)
                    notifyDeviceListeners()
                    // Adapter capabilities
                    val hiCar = message.json.optInt("HiCar", -1).takeIf { it >= 0 }
                    val supportFeatures = message.json.optString("supportFeatures", "").ifEmpty { null }
                    val channelList = message.json.optString("ChannelList", "").ifEmpty { null }
                    logInfo(
                        "[DEVICE] Paired devices: ${_deviceList.size}" +
                            (if (hiCar != null) ", HiCar=$hiCar" else "") +
                            (if (supportFeatures != null) ", features=$supportFeatures" else "") +
                            (if (channelList != null) ", channels=$channelList" else ""),
                        tag = Logger.Tags.ADAPTR,
                    )
                    _deviceList.forEachIndexed { idx, dev ->
                        logDebug(
                            "[DEVICE] DevList[$idx]: mac=${dev.btMac}, name=${dev.name}, " +
                                "type=${dev.type}, last=${dev.lastConnected ?: "n/a"}",
                            tag = Logger.Tags.ADAPTR,
                        )
                    }
                    // Hot-rejoin: if exactly 1 paired device, try cache lookup now
                    // (adapter may skip BoxSettings #2 and PeerBluetoothAddress)
                    if (_deviceList.size == 1) {
                        val mac = _deviceList[0].btMac
                        _connectedBtMac = mac
                        tryPrestageCodecCsd(mac)
                    }
                }

                // Detect unknown JSON keys from adapter firmware updates
                val knownKeys = setOf(
                    "uuid", "MFD", "boxType", "OemName", "productType", "hwVersion",
                    "supportLinkType", "WiFiChannel", "DevList", "ver", "mfd",
                    "MDModel", "btMacAddr", "buildModel", "iOSVer", "linkType",
                    "boxName", "wifiName", "btName", "oemIconLabel", "wifiPasswd",
                    "androidWorkMode", "phoneMode", "DashboardInfo",
                    "AndroidAutoSizeW", "AndroidAutoSizeH", "naviScreenInfo",
                    "AdvancedFeatures", "GNSSCapability", "callQuality",
                    "HiCar", "supportFeatures", "CusCode", "ChannelList",
                    "MDLinkType", "MDOSVersion", "MDLinkVersion", "cpuTemp",
                )
                val unknownKeys = message.json.keys().asSequence()
                    .filter { it !in knownKeys }
                    .toList()
                if (unknownKeys.isNotEmpty()) {
                    unknownBoxSettingsKeyCount += unknownKeys.size
                    val preview = unknownKeys.joinToString { key ->
                        "$key=${message.json.opt(key)}"
                    }
                    logWarn(
                        "[BOX_SETTINGS] Unknown keys: $preview",
                        tag = Logger.Tags.PROTO_UNKNOWN,
                    )
                }
            }

            is PeerBluetoothAddressMessage -> {
                _connectedBtMac = message.macAddress
                logInfo("[DEVICE] Peer BT address: ${message.macAddress}", tag = Logger.Tags.ADAPTR)
                tryPrestageCodecCsd(message.macAddress)
            }

            // Phase message — Phase 0 during STREAMING (with negotiationRejected=false) is
            // treated as a session termination that triggers restart(); Phase 0 in any other
            // state is normal session negotiation and is logged without action.
            is PhaseMessage -> {
                if (message.phase == 13) {
                    logWarn(
                        "[PHASE] Phase 13 (negotiation_failed) — Phone rejected configuration",
                        tag = Logger.Tags.ADAPTR,
                    )
                    negotiationRejected = true
                    setStatusText("Phone rejected configuration")
                } else if (message.phase == 0 && negotiationRejected) {
                    logDebug(
                        "[PHASE] Phase 0 after negotiation rejection — not restarting",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else if (message.phase == 0 && state == State.STREAMING) {
                    logWarn(
                        "[PHASE] Phase 0 during STREAMING — session terminated by adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    setStatusText("Session terminated — restarting...")
                    scope.launch { restart() }
                } else if (message.phase == 0) {
                    logDebug(
                        "[PHASE] Phase 0 during $state — ignoring (normal session negotiation)",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else if (message.phase == 7) {
                    setStatusText("Phone connecting...")
                    logDebug("[PHASE] ${message.phase} (${message.phaseName})", tag = Logger.Tags.ADAPTR)
                } else {
                    logDebug("[PHASE] ${message.phase} (${message.phaseName})", tag = Logger.Tags.ADAPTR)
                }
            }

            // Navigation focus — echo back as recommended precaution (testing inconclusive)
            is NaviFocusMessage -> {
                if (message.isRequest) {
                    logDebug("[NAVI] Navigation video focus requested — echoing 508", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.sendCommand(CommandMapping.REQUEST_NAVI_SCREEN_FOCUS)
                } else {
                    logDebug("[NAVI] Navigation video focus released — echoing 509", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.sendCommand(CommandMapping.RELEASE_NAVI_SCREEN_FOCUS)
                    // Tell forwarder to broadcast onStreamEnded to bound consumers. Idempotent
                    // and safe to call when gate is false (the forwarder no-ops on !streamActive,
                    // and on prod/non-emulator no sinks are ever registered anyway).
                    com.carlink.ipc.NaviVideoSingleton.forwarder.onStreamStop()
                }
            }

            // Diagnostic messages — logged for protocol completeness and event correlation
            is StatusValueMessage -> {
                logDebug("[STATUS] Value=${message.value} (0x${message.value.toString(16)})", tag = Logger.Tags.ADAPTR)
            }

            is SessionTokenMessage -> {
                logDebug("[SESSION] Token received (${message.payloadSize}B encrypted)", tag = Logger.Tags.ADAPTR)
            }

            is BluetoothPairedListMessage -> {
                logInfo(
                    "[DEVICE] BluetoothPairedList: ${message.devices.size} devices (existing=${_deviceList.size})",
                    tag = Logger.Tags.ADAPTR,
                )
                if (message.devices.isNotEmpty()) {
                    // MERGE into existing list — 0x12 is sent multiple times:
                    // at init (all devices) and after BT connect (just the connecting device).
                    // Never shrink the list; only add/update entries.
                    // BoxSettings DevList (0x19) is the authoritative full list.
                    val existingByMac = _deviceList.associateBy { it.btMac }.toMutableMap()
                    var changed = false
                    for ((mac, name) in message.devices) {
                        val existing = existingByMac[mac]
                        if (existing != null) {
                            // Update name if changed
                            if (existing.name != name) {
                                existingByMac[mac] = existing.copy(name = name)
                                changed = true
                            }
                        } else {
                            // New device not in current list
                            existingByMac[mac] = DeviceInfo(
                                btMac = mac,
                                name = name,
                                type = "", // Unknown from 0x12 — enriched when BoxSettings arrives
                            )
                            changed = true
                        }
                    }
                    if (changed || _deviceList.isEmpty()) {
                        _deviceList = existingByMac.values.toList()
                        _deviceList.forEachIndexed { idx, dev ->
                            logDebug(
                                "[DEVICE] PairedList[$idx]: mac=${dev.btMac}, name=${dev.name}, type=${dev.type.ifEmpty { "unknown" }}",
                                tag = Logger.Tags.ADAPTR,
                            )
                        }
                        callback?.onDeviceListChanged(_deviceList)
                        notifyDeviceListeners()
                    }
                }
            }

            is InfoMessage -> {
                logDebug("[INFO] ${message.label}: ${message.value}", tag = Logger.Tags.ADAPTR)
            }

            is UnknownMessage -> {
                unknownMessageTypeCount++
                unknownMessageTypes.add(message.header.rawType)
                logWarn(
                    "[UNKNOWN] Unrecognized message type=0x${message.header.rawType.toString(16)} " +
                        "(${message.header.length}B) payload=${message.hexPreview()}",
                    tag = Logger.Tags.PROTO_UNKNOWN,
                )
            }
        }

        // Handle audio commands for mic capture
        if (message is AudioDataMessage && message.command != null) {
            handleAudioCommand(message.command, message.decodeType, message.rawCommandId)
        }
    }

    private fun processAudioData(message: AudioDataMessage) {
        // Handle volume ducking
        message.volumeDuration?.let {
            audioManager?.setDucking(message.volume)
            return
        }

        // Skip command messages
        if (message.command != null) return

        // Skip if no audio data
        val audioData = message.data ?: return

        // Track adapter's current audio decodeType (for mic format negotiation)
        lastIncomingDecodeType = message.decodeType

        // Write audio with offset+length to avoid copy
        // Two-factor routing: state flags + format match (not purpose) to prevent transition artifacts
        audioManager?.writeAudio(
            audioData,
            message.audioDataOffset,
            message.audioDataLength,
            message.audioType,
            message.decodeType,
            AudioRoutingState(
                isSiriActive = isSiriAudioActive,
                isPhoneCallActive = isPhoneCallAudioActive,
                isAlertActive = isAlertAudioActive,
            ),
        )
    }

    private fun handleAudioCommand(command: AudioCommand, messageDecodeType: Int = 5, rawCommandId: Int = command.id) {
        logDebug(
            "[AUDIO_CMD] ${command.name} (id=${command.id} siri=$isSiriAudioActive call=$isPhoneCallAudioActive alert=$isAlertAudioActive)",
            tag = Logger.Tags.AUDIO,
        )

        when (command) {
            AudioCommand.AUDIO_TBT_START,
            AudioCommand.AUDIO_NAVI_START -> {
                // TBT_START (byte 15) and NAVI_START (byte 6) both start nav audio.
                // TBT_START arrives ~97ms before NAVI_START in turn-by-turn sequences.
                // Whichever arrives first prepares the nav path; the second is a no-op
                // since onNavStarted() is idempotent.
                val cmdName = if (command == AudioCommand.AUDIO_TBT_START) "TBT_START" else "NAVI_START"
                logInfo("[AUDIO_CMD] Navigation audio START ($cmdName)", tag = Logger.Tags.AUDIO)
                audioManager?.onNavStarted()
            }

            AudioCommand.AUDIO_NAVI_STOP -> {
                logInfo("[AUDIO_CMD] Navigation audio STOP command received", tag = Logger.Tags.AUDIO)
                // Signal nav stopped - stop accepting new packets, but don't flush yet
                // (NAVI_COMPLETE will handle final cleanup including focus abandon)
                audioManager?.onNavStopped()
            }

            AudioCommand.AUDIO_NAVI_COMPLETE -> {
                logInfo("[AUDIO_CMD] Navigation audio COMPLETE command received", tag = Logger.Tags.AUDIO)
                // Explicit end-of-prompt signal from adapter - clean shutdown + abandon nav focus
                audioManager?.stopNavTrack()
            }

            AudioCommand.AUDIO_SIRI_START -> {
                logInfo("[AUDIO_CMD] Siri started - enabling microphone (mode: SIRI)", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.SIRI
                isSiriAudioActive = true
                setPurpose(StreamPurpose.SIRI, command)
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_PHONECALL_START -> {
                val micDecodeType = lastIncomingDecodeType
                logInfo("[AUDIO_CMD] Phone call started - enabling microphone (mode: PHONECALL, decodeType=$micDecodeType)", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.PHONECALL
                isPhoneCallAudioActive = true
                endPurpose(StreamPurpose.RINGTONE, command) // Call answered — ringtone is over
                setPurpose(StreamPurpose.PHONE_CALL, command)
                startMicrophoneCapture(decodeType = micDecodeType, audioType = 3)
            }

            AudioCommand.AUDIO_SIRI_STOP -> {
                // CRITICAL: Don't stop mic if phone call is active (Siri-initiated call scenario)
                // USB capture shows: PHONECALL_START arrives ~130ms BEFORE SIRI_STOP
                // NOTE: "~130ms" was observed on real hardware during development; the specific
                // USB capture is not checked in to this repo. The guard below is what matters:
                // if activeVoiceMode == PHONECALL, we keep the mic open regardless of ordering.
                // Always clear siri routing flag (audio routing is independent of mic lifecycle)
                isSiriAudioActive = false
                // Pause SIRI AudioTrack regardless of branch — the silence-write idle
                // path keeps it PLAYING otherwise, leaving the USAGE_ASSISTANT volume
                // context active and stealing volume routing from MEDIA after Siri ends.
                audioManager?.stopSiriTrack()
                if (activeVoiceMode == VoiceMode.PHONECALL) {
                    logInfo(
                        "[AUDIO_CMD] Siri stopped but phone call active - keeping mic for call",
                        tag = Logger.Tags.MIC,
                    )
                    logDebug(
                        "[AUDIO_PURPOSE] SIRI_STOP: siri routing cleared, keeping mic for PHONE_CALL",
                        tag = Logger.Tags.AUDIO_DEBUG,
                    )
                    // Don't stop mic or end SIRI AudioFocus — PHONE_CALL handles both
                } else {
                    logInfo("[AUDIO_CMD] Siri stopped - disabling microphone", tag = Logger.Tags.MIC)
                    activeVoiceMode = VoiceMode.NONE
                    endPurpose(StreamPurpose.SIRI, command)
                    stopMicrophoneCapture()
                }
            }

            AudioCommand.AUDIO_PHONECALL_STOP -> {
                logInfo("[AUDIO_CMD] Phone call stopped - disabling microphone", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.NONE
                isPhoneCallAudioActive = false
                // Pause the phone-call AudioTrack BEFORE abandoning focus so AAOS
                // sees no active USAGE_VOICE_COMMUNICATION player. Without this,
                // the silence-write idle path keeps the track PLAYING and AAOS
                // keeps routing volume keys to the CALL group + ducking media.
                audioManager?.stopPhoneCallTrack()
                endPurpose(StreamPurpose.PHONE_CALL, command)
                stopMicrophoneCapture()
            }

            AudioCommand.AUDIO_MEDIA_START -> {
                logDebug("[AUDIO_CMD] Media audio START command received", tag = Logger.Tags.AUDIO)
                // Adapter signals media resuming — clear any residual adapter ducking from
                // a previous voice session (Siri/phone call). The adapter does not send an
                // explicit volume=1.0 restore packet; MEDIA_START is the session boundary.
                audioManager?.setDucking(1.0f)
                endPurpose(StreamPurpose.RINGTONE, command) // Safety net — abandon before MEDIA focus request
                setPurpose(StreamPurpose.MEDIA, command)
            }

            AudioCommand.AUDIO_MEDIA_STOP -> {
                logDebug("[AUDIO_CMD] Media audio STOP command received", tag = Logger.Tags.AUDIO)
                endPurpose(StreamPurpose.MEDIA, command)
            }

            AudioCommand.AUDIO_ALERT_START -> {
                logDebug("[AUDIO_CMD] Alert started", tag = Logger.Tags.AUDIO)
                isAlertAudioActive = true
                setPurpose(StreamPurpose.ALERT, command)
            }

            AudioCommand.AUDIO_ALERT_STOP -> {
                logDebug("[AUDIO_CMD] Alert stopped", tag = Logger.Tags.AUDIO)
                isAlertAudioActive = false
                endPurpose(StreamPurpose.ALERT, command)
                endPurpose(StreamPurpose.RINGTONE, command) // Call declined/missed — ringtone is over
            }

            AudioCommand.AUDIO_OUTPUT_START -> {
                logDebug("[AUDIO_CMD] Audio output START command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_OUTPUT_STOP -> {
                logDebug("[AUDIO_CMD] Audio output STOP command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_INCOMING_CALL_INIT -> {
                // No-op: RINGTONE has no AudioTrack slot and no audio data routing.
                // ALERT_START follows ~1s later and independently handles focus.
                // Previously this called setPurpose(RINGTONE) which acquired AUDIOFOCUS_GAIN_TRANSIENT
                // but was never abandoned (no INCOMING_CALL_STOP exists in the protocol), causing
                // permanent media silence after phone calls.
                logInfo("[AUDIO_CMD] Incoming call ring (AudioCmd 14)", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_INPUT_CONFIG -> {
                logDebug("[AUDIO_CMD] AUDIO_INPUT_CONFIG received (decodeType=$messageDecodeType)", tag = Logger.Tags.AUDIO)
                lastIncomingDecodeType = messageDecodeType
                // If mic is active, restart capture at the adapter's requested format
                // (mirrors Autokit: case 3 → a.i = w.a → h.h(c(w.a, true)))
                if (isMicrophoneCapturing && currentMicDecodeType != messageDecodeType) {
                    logInfo("[AUDIO_CMD] INPUT_CONFIG: switching mic from decodeType=$currentMicDecodeType to $messageDecodeType", tag = Logger.Tags.MIC)
                    stopMicrophoneCapture()
                    startMicrophoneCapture(decodeType = messageDecodeType, audioType = currentMicAudioType)
                }
            }

            AudioCommand.UNKNOWN -> {
                unknownAudioCommandCount++
                unknownAudioCommandIds.add(rawCommandId)
                logWarn(
                    "[AUDIO_CMD] Unknown audio command rawId=$rawCommandId (0x${rawCommandId.toString(16)})",
                    tag = Logger.Tags.PROTO_UNKNOWN,
                )
            }
        }
    }

    /** Request AudioFocus for a stream purpose. */
    private fun setPurpose(purpose: StreamPurpose, trigger: AudioCommand) {
        if (!config.audioTransferMode) {
            audioManager?.onPurposeChanged(purpose)
        }
        logDebug(
            "[AUDIO_PURPOSE] → $purpose (trigger: ${trigger.name} voiceMode: $activeVoiceMode)",
            tag = Logger.Tags.AUDIO_DEBUG,
        )
    }

    /** Abandon AudioFocus for a stream purpose. */
    private fun endPurpose(purpose: StreamPurpose, trigger: AudioCommand) {
        if (!config.audioTransferMode) {
            audioManager?.onPurposeEnded(purpose)
        }
        logDebug(
            "[AUDIO_PURPOSE] Ended $purpose (trigger: ${trigger.name})",
            tag = Logger.Tags.AUDIO_DEBUG,
        )
    }

    private fun startMicrophoneCapture(
        decodeType: Int,
        audioType: Int,
    ) {
        if (isMicrophoneCapturing) {
            if (currentMicDecodeType == decodeType && currentMicAudioType == audioType) {
                return
            }
            stopMicrophoneCapture()
        }

        val started = microphoneManager?.start(decodeType) ?: false
        if (started) {
            isMicrophoneCapturing = true
            currentMicDecodeType = decodeType
            currentMicAudioType = audioType

            // Start send loop
            // HAZARD: java.util.Timer.scheduleAtFixedRate silently terminates the Timer if
            // the TimerTask.run() throws an uncaught exception. If sendMicrophoneData ever
            // throws (e.g., adapterDriver?.sendAudio RuntimeException on concurrent close),
            // the mic pipeline dies permanently for this session — isMicrophoneCapturing
            // stays true, blocking restart. Wrap the run() body in try/catch if this becomes
            // a real failure mode. Compare to AdapterDriver.kt's HeartbeatTimer which does wrap.
            // 20ms period paired with the 320B/640B chunk sizes in sendMicrophoneData (both
            // derived from 20ms @ 8kHz/16kHz mono PCM). If either changes without the other,
            // the mic stream underruns.
            micSendTimer =
                Timer().apply {
                    scheduleAtFixedRate(
                        object : TimerTask() {
                            override fun run() {
                                sendMicrophoneData()
                            }
                        },
                        0,
                        20,
                    ) // 20ms interval
                }

            logInfo("Microphone capture started", tag = Logger.Tags.MIC)
        }
    }

    private fun stopMicrophoneCapture() {
        if (!isMicrophoneCapturing) return

        micSendTimer?.cancel()
        micSendTimer = null

        microphoneManager?.stop()
        isMicrophoneCapturing = false

        logInfo("Microphone capture stopped", tag = Logger.Tags.MIC)
    }

    private fun sendMicrophoneData() {
        if (!isMicrophoneCapturing) return

        val chunkSize = if (currentMicDecodeType == 3) 320 else 640 // 20ms at 8kHz or 16kHz mono
        val data = microphoneManager?.readChunk(maxBytes = chunkSize) ?: return
        if (data.isNotEmpty()) {
            adapterDriver?.sendAudio(
                data = data,
                decodeType = currentMicDecodeType,
                audioType = currentMicAudioType,
            )
        }
    }

    // DEBUG helper: emit a possibly-long iAP2 hex string across multiple log lines so the
    // full payload survives logcat's per-line ~4000 char cap. Single short strings fit in
    // one line. Strip together with processMediaMetadata's [NAVI_JSON_RX] block before
    // production release.
    private fun logIap2Field(
        name: String,
        hex: String,
    ) {
        val chunkSize = 3500
        val total = (hex.length + chunkSize - 1) / chunkSize
        if (total <= 1) {
            logInfo("[NAVI_JSON_RX:$name len=${hex.length}] $hex", tag = Logger.Tags.NAVI)
            return
        }
        for (i in 0 until total) {
            val s = i * chunkSize
            val e = minOf(s + chunkSize, hex.length)
            logInfo(
                "[NAVI_JSON_RX:$name len=${hex.length} chunk=${i + 1}/$total] ${hex.substring(s, e)}",
                tag = Logger.Tags.NAVI,
            )
        }
    }

    private fun processMediaMetadata(message: MediaDataMessage) {
        // Route NaviJSON to NavigationStateManager for cluster display (only if enabled)
        if (message.type == MediaType.NAVI_JSON) {
            // DEBUG: log full NaviJSON receipt so we can confirm the patched ARMiPhoneIAP2
            // is emitting `_iap2` / `_iap2m` recovery fields. Each _iap2* hex payload (up to
            // ~3KB for 0x5202 maneuver bursts) is emitted on its own log line and chunked at
            // 3500 chars to stay under Android's per-line logcat limit (~4000). Decoder-side
            // re-assembly: concatenate all "[NAVI_JSON_RX:_iap2m] chunk=N/M payload=..." lines
            // for the same timestamp. Strip before production-grade release.
            val keys = message.payload.keys.sorted()
            // Dump primitive Navi* values (skip _iap2/_iap2m which are huge hex — those go on
            // their own chunked lines below). Lets us correlate per-step events against the
            // 0x5202 maneuver list during the cursor-walk-matcher design pass.
            val naviPrimitives = message.payload
                .filterKeys { it.startsWith("Navi") }
                .entries
                .joinToString(", ") { (k, v) ->
                    val s = v.toString()
                    "$k=${if (s.length > 80) s.take(80) + "…" else s}"
                }
            logInfo("[NAVI_JSON_RX] keys=$keys${if (naviPrimitives.isNotEmpty()) " | $naviPrimitives" else ""}", tag = Logger.Tags.NAVI)
            // v6.1: parse raw 0x5201 RouteGuidanceUpdate for the wire cursor + state and
            // feed NavigationStateManager BEFORE onNaviJson runs (same USB read thread).
            // Set null on absent _iap2 OR parse failure to prevent stale-cursor reuse —
            // see Iap2StateParser.parse contract.
            val iap2Hex = message.payload["_iap2"] as? String
            if (iap2Hex != null) {
                logIap2Field("_iap2", iap2Hex)
                com.carlink.navigation.NavigationStateManager.setLastIap2State(
                    com.carlink.navigation.Iap2StateParser.parse(iap2Hex)
                )
            } else {
                com.carlink.navigation.NavigationStateManager.setLastIap2State(null)
            }
            (message.payload["_iap2m"] as? String)?.let { iap2m ->
                logIap2Field("_iap2m", iap2m)
                // Populate the runtime icon composer cache. Parses the burst synchronously
                // (cheap) on this USB read thread, then dispatches the per-maneuver
                // compose+render to a background worker so it never blocks video reads.
                // Per-step events later call ComposedIconStore.lookup (static fallback until
                // the background fill completes). Gated by ComposedIconStore.enabled — when
                // off, lookup returns null and the static-XML / AA-bitmap paths handle the
                // cluster icon. See app/src/main/kotlin/com/carlink/navigation/compose/.
                val dispatched = com.carlink.navigation.compose.ComposedIconStore.populateFromIap2m(iap2m)
                if (dispatched != null) {
                    logInfo("[NAVI_JSON_RX] Composer dispatched $dispatched maneuvers for background compose", tag = Logger.Tags.NAVI)
                }
            }
            // NOTE: getClusterNavigationSync() is read at message time, NOT cached —
            // a user toggling cluster navigation in AdapterConfigurationDialog takes effect
            // on the next NAVI_* message, no restart needed.
            if (AdapterConfigPreference.getInstance(context).getClusterNavigationSync()) {
                NavigationStateManager.onNaviJson(message.payload)
            }
            return
        }

        // Route AA maneuver icons to NavigationStateManager (sub-type 201)
        if (message.type == MediaType.NAVI_IMAGE) {
            // NOTE: getClusterNavigationSync() is read at message time, NOT cached —
            // a user toggling cluster navigation in AdapterConfigurationDialog takes effect
            // on the next NAVI_* message, no restart needed.
            if (AdapterConfigPreference.getInstance(context).getClusterNavigationSync()) {
                val imageData = message.payload["NaviImage"] as? ByteArray
                if (imageData != null) {
                    NavigationStateManager.onNaviImage(imageData)
                } else {
                    logWarn("[NAVI_ICON] NAVI_IMAGE message with no image data", tag = Logger.Tags.NAVI)
                }
            }
            return
        }

        // Android Auto album art arrives as a standalone MEDIA_DATA subtype 2 message (PNG).
        // Cache it and push a metadata update so MediaSession gets the cover immediately,
        // without waiting for the next subtype-1 JSON tick which carries no image bytes.
        if (message.type == MediaType.ALBUM_COVER_AA) {
            val coverBytes = message.payload["AlbumCover"] as? ByteArray
            if (coverBytes != null) {
                lastAlbumCover = coverBytes
                mediaSessionManager?.updateMetadata(
                    title = lastMediaSongName,
                    artist = lastMediaArtistName,
                    album = lastMediaAlbumName,
                    appName = lastMediaAppName,
                    albumArt = lastAlbumCover,
                    duration = lastDuration,
                )
            }
            return
        }

        // CallStatus JSON (subtype 100) — iAP2 CallStateEngine forwarding.
        // Log at INFO for release troubleshooting. Not consumed for UI — CarPlay/AA
        // projection already shows call screen, and cluster requires system privilege.
        if (message.type == MediaType.CALL_STATUS) {
            val status = message.payload["CallStatus"] as? Int ?: -1
            val direction = message.payload["CallDirection"] as? Int
            val name = message.payload["CallName"] as? String
            val number = message.payload["CallNumber"] as? String
            val statusName = when (status) {
                0 -> "idle"
                1 -> "dialing"
                2 -> "ringing"
                3 -> "connected"
                4 -> "disconnecting"
                else -> "?$status"
            }
            val dirName = when (direction) {
                1 -> "incoming"
                2 -> "outgoing"
                else -> ""
            }
            val caller = name ?: number ?: ""
            logInfo(
                "[CALL_STATUS] $statusName $dirName${if (caller.isNotEmpty()) " caller=$caller" else ""}",
                tag = Logger.Tags.PHONE,
            )
            return
        }

        // Unknown MediaData subtype — log everything for protocol discovery
        if (message.type == MediaType.UNKNOWN) {
            unknownMediaSubtypeCount++
            val subtype = message.payload["_unknownSubtype"]
            if (subtype is Int) unknownMediaSubtypes.add(subtype)
            val hex = message.payload["_hexPreview"] ?: ""
            logWarn(
                "[MEDIA_UNKNOWN] Unrecognized MediaData subtype=$subtype " +
                    "(${message.header.length}B) payload=$hex",
                tag = Logger.Tags.PROTO_UNKNOWN,
            )
            return
        }

        val payload = message.payload

        // Extract new song title (if present)
        val newSongName = (payload["MediaSongName"] as? String)?.takeIf { it.isNotEmpty() }

        // WHY we snapshot previous state before the song-change reset:
        // Android Auto sends metadata in split increments across consecutive
        // frames (frame 1: title only; frame 2: artist only; frame 3: album).
        // The prior metadataChanged predicate compared only title+cover, so
        // artist/album/appName-only updates were silently dropped — cluster
        // showed title without artist until the next song change. Capturing
        // previous* here lets metadataChanged below detect incremental changes
        // even after lastMediaArtistName/etc. have been overwritten.
        val previousSongName = lastMediaSongName
        val previousArtist = lastMediaArtistName
        val previousAlbum = lastMediaAlbumName
        val previousAppName = lastMediaAppName
        val previousDuration = lastDuration

        // Detect song change — clear all cached metadata to prevent stale data mixing
        if (newSongName != null && newSongName != previousSongName) {
            lastMediaSongName = null
            lastMediaArtistName = null
            lastMediaAlbumName = null
            lastAlbumCover = null
            lastDuration = 0L
            lastPosition = 0L
            // Keep appName - typically doesn't change mid-session
        }

        // Extract text metadata
        newSongName?.let {
            lastMediaSongName = it
        }
        val newArtist = (payload["MediaArtistName"] as? String)?.takeIf { it.isNotEmpty() }
        newArtist?.let { lastMediaArtistName = it }
        val newAlbum = (payload["MediaAlbumName"] as? String)?.takeIf { it.isNotEmpty() }
        newAlbum?.let { lastMediaAlbumName = it }
        val newAppName = (payload["MediaAPPName"] as? String)?.takeIf { it.isNotEmpty() }
        newAppName?.let { lastMediaAppName = it }

        // Process album cover after song change detection
        val albumCover = payload["AlbumCover"] as? ByteArray
        if (albumCover != null) {
            lastAlbumCover = albumCover
        }

        // Extract playback fields (missing before: position, duration, play status)
        val duration = (payload["MediaSongDuration"] as? Number)?.toLong() ?: lastDuration
        val position = (payload["MediaSongPlayTime"] as? Number)?.toLong() ?: lastPosition
        val playStatus = (payload["MediaPlayStatus"] as? Number)?.toInt()
        val isPlaying = if (playStatus != null) playStatus == 1 else lastIsPlaying

        // Cache playback fields
        lastDuration = duration
        lastPosition = position
        lastIsPlaying = isPlaying

        val mediaInfo =
            MediaInfo(
                songTitle = lastMediaSongName,
                songArtist = lastMediaArtistName,
                albumName = lastMediaAlbumName,
                appName = lastMediaAppName,
                albumCover = lastAlbumCover,
                duration = duration,
                position = position,
                isPlaying = isPlaying,
            )

        // WHY this broader predicate: AA's incremental metadata stream would
        // otherwise suppress legitimate artist/album/appName/duration-only
        // updates. Position-only ticks (~95% of messages) still short-circuit
        // because none of the guarded fields change between them.
        val metadataChanged =
            (newSongName != null && newSongName != previousSongName) ||
                (newArtist != null && newArtist != previousArtist) ||
                (newAlbum != null && newAlbum != previousAlbum) ||
                (newAppName != null && newAppName != previousAppName) ||
                albumCover != null ||
                (duration > 0 && duration != previousDuration)

        if (metadataChanged) {
            // Full metadata update (title/artist/album/cover/duration)
            mediaSessionManager?.updateMetadata(
                title = mediaInfo.songTitle,
                artist = mediaInfo.songArtist,
                album = mediaInfo.albumName,
                appName = mediaInfo.appName,
                albumArt = mediaInfo.albumCover,
                duration = duration,
            )

            // Update foreground notification with current now-playing
            CarlinkMediaBrowserService.updateNowPlaying(mediaInfo.songTitle, mediaInfo.songArtist)
        }

        // Always update playback state (position ticks are the common case)
        mediaSessionManager?.updatePlaybackState(playing = isPlaying, position = position)

        if (BuildConfig.DEBUG) {
            val songChanged = newSongName != null && newSongName != previousSongName
            logDebug(
                "[MEDIA_DATA] " +
                    (if (songChanged) "[SONG_CHANGE] " else "") +
                    "title=${newSongName ?: "·"} artist=${newArtist ?: "·"} album=${newAlbum ?: "·"} " +
                    "app=${newAppName ?: "·"} art=${albumCover?.size ?: 0}B " +
                    "dur=${duration}ms pos=${position}ms playing=$isPlaying " +
                    "→ pushedMetadata=$metadataChanged",
                tag = Logger.Tags.MEDIA,
            )
        }
    }

    /**
     * Error handler for adapter communication failures.
     *
     * Performs full session cleanup (matching stop()) so that start() called
     * from reconnect sees a clean slate. Without this, start() finds a non-null
     * adapterDriver, calls stop(), which calls cancelReconnect() — killing the
     * very coroutine that invoked start().
     *
     * For USB disconnects, schedules auto-reconnect with exponential backoff.
     */
    private fun handleError(error: String) {
        // NOTE: `hadPriorSession` is intentionally NOT reset here (it's only reset in
        // stop()). This preserves escalation context across mid-session failures so that
        // Pattern A/B/C status messages can correctly distinguish "adapter broken after a
        // prior session" from "never connected".
        clearPairTimeout()

        logError("Adapter error: $error", tag = Logger.Tags.ADAPTR)

        // Full session state reset (mirrors stop() minus cancelReconnect/graceful teardown)
        cancelDelayedKeyframe()
        negotiationRejected = false
        pendingConnectTarget = null
        lastConnectTargetMac = null
        currentPhoneType = null
        currentWifi = null
        videoPhoneTypeInferred = false
        codecDeferred = true
        videoEncoderType = 2
        videoOffScreen = 0
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata()
        activeVoiceMode = VoiceMode.NONE
        isSiriAudioActive = false
        isPhoneCallAudioActive = false
        isAlertAudioActive = false
        stopMicrophoneCapture()
        gnssForwarder?.stop()

        // Stop adapter driver (heartbeat, reading loop) and close USB.
        // Skip graceful teardown — USB is likely dead.
        adapterDriver?.stop()
        adapterDriver = null
        usbDevice?.close()
        usbDevice = null

        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
        }

        // Pattern A: track consecutive "no initial response" errors (adapter USB write dead)
        // Pattern C: track short-lived STREAMING sessions (unstable adapter)
        val isNoResponse = error.contains("no initial response")
        if (isNoResponse) {
            consecutiveNoResponse++
        } else {
            consecutiveNoResponse = 0
        }

        if (lastStreamingStartMs > 0) {
            val sessionDuration = System.currentTimeMillis() - lastStreamingStartMs
            if (sessionDuration < SHORT_SESSION_THRESHOLD_MS) {
                shortLivedStreamingCount++
            }
            lastStreamingStartMs = 0L
        }

        setState(State.DISCONNECTED)

        // Schedule auto-reconnect for USB disconnect errors
        if (isUsbDisconnectError(error)) {
            // Escalate status based on observed patterns
            if (consecutiveNoResponse >= 2) {
                // Pattern A: adapter USB write dead — retrying won't help
                setStatusText("Adapter not responding — reboot adapter")
                logWarn("[ESCALATION] Pattern A: $consecutiveNoResponse consecutive no-response errors", tag = Logger.Tags.USB)
            } else if (shortLivedStreamingCount >= SHORT_SESSION_ESCALATION_COUNT) {
                // Pattern C: sessions keep dying within seconds
                setStatusText("Connection unstable — reboot adapter")
                logWarn("[ESCALATION] Pattern C: $shortLivedStreamingCount short-lived sessions", tag = Logger.Tags.USB)
            } else if (isNoResponse) {
                setStatusText("Adapter not responding — reconnecting...")
            }
            scheduleReconnect()
        }
    }

    /**
     * Checks if an error indicates USB disconnect (physical or transfer failure).
     */
    private fun isUsbDisconnectError(error: String): Boolean {
        val lowerError = error.lowercase()
        return lowerError.contains("disconnect") ||
            lowerError.contains("detach") ||
            lowerError.contains("transfer") ||
            lowerError.contains("usb")
    }

    /**
     * Schedule an auto-reconnect attempt with exponential backoff.
     *
     * After USB disconnect, attempts to reconnect automatically:
     * - Attempt 1: 2 seconds delay
     * - Attempt 2: 4 seconds delay
     * - Attempt 3: 8 seconds delay
     * - Attempt 4: 16 seconds delay
     * - Attempt 5: 30 seconds delay (capped)
     *
     * Gives up after MAX_RECONNECT_ATTEMPTS to prevent infinite loops.
     */
    private fun scheduleReconnect() {
        // Cancel any existing reconnect attempt
        reconnectJob?.cancel()

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logWarn(
                "[RECONNECT] Max attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up. " +
                    "noResponse=$consecutiveNoResponse shortSessions=$shortLivedStreamingCount hadPrior=$hadPriorSession",
                tag = Logger.Tags.USB,
            )
            val giveUpMessage = when {
                consecutiveNoResponse >= 2 -> "Adapter not responding — reboot adapter"
                shortLivedStreamingCount >= SHORT_SESSION_ESCALATION_COUNT -> "Connection unstable — reboot adapter"
                hadPriorSession -> "Phone not reconnecting — reboot adapter"
                else -> "Adapter not responding — unplug and replug adapter"
            }
            // After give-up: reconnectAttempts is reset to 0, but shortLivedStreamingCount
            // / consecutiveNoResponse are NOT reset. A user who manually taps reconnect
            // after give-up will immediately trip Pattern A/C escalation on the first
            // failure. Intentional so the escalation context survives the "give up" → "user
            // retries" boundary; but the UX can surprise.
            reconnectAttempts = 0
            setStatusText(giveUpMessage)
            // Stop FGS since we're no longer attempting to reconnect
            CarlinkMediaBrowserService.stopConnectionForeground(context)
            return
        }

        // Maintain foreground priority during reconnect delay to prevent LMK kill
        CarlinkMediaBrowserService.startConnectionForeground(context)

        // Calculate delay with exponential backoff, capped at max
        val delay =
            minOf(
                INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempts),
                MAX_RECONNECT_DELAY_MS,
            )
        reconnectAttempts++

        logInfo(
            "[RECONNECT] Scheduling attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms",
            tag = Logger.Tags.USB,
        )

        setStatusText("Reconnecting ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

        reconnectJob =
            scope.launch {
                delay(delay)

                // Only attempt if still disconnected
                if (state == State.DISCONNECTED) {
                    logInfo("[RECONNECT] Attempting reconnection...", tag = Logger.Tags.USB)
                    try {
                        withContext(Dispatchers.IO) { start() }
                    } catch (e: Exception) {
                        logError("[RECONNECT] Reconnection failed: ${e.message}", tag = Logger.Tags.USB)
                        // handleError will be called by start() failure, which will schedule next attempt
                    }
                } else {
                    logInfo("[RECONNECT] Already connected, cancelling reconnect", tag = Logger.Tags.USB)
                    reconnectAttempts = 0
                }
            }
    }

    /**
     * Cancel any pending reconnect attempt.
     */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    private fun clearPairTimeout() {
        pairTimeout?.cancel()
        pairTimeout = null
    }

    /**
     * Schedule keyframe requests for CarPlay sessions.
     *
     * 1. Initial delayed request (2.5s): The adapter sends a natural SPS+PPS+IDR at session
     *    start which the codec decodes immediately. This delayed request serves as a cold-start
     *    safety net — if the decoder is poisoned (observed on Intel hardware, first session only),
     *    the fresh IDR on a now-warm codec clears the poisoned state.
     *
     * 2. Periodic interval (30s): Passive self-healing against mid-session decoder corruption
     *    from platform instability. The GM Info 3.7 Intel Atom x7-A3960 platform has a
     *    poorly designed VPU/USB subsystem where silent decoder corruption can occur from
     *    USB bulk stalls, GHS hypervisor interrupts, or Intel VPU firmware bugs — factors
     *    outside the app's control. The watchdog only catches complete decode failure (Rx>0,
     *    Dec=0), NOT progressive quality degradation from corrupted reference frames.
     *    A periodic IDR is the only fix for silent corruption.
     *    NOTE: the GM Info 3.7 / Intel Atom x7-A3960 rationale was observed on real hardware
     *    during development; there is no in-tree capture or datasheet reference that proves
     *    the VPU/USB failure modes listed above. Treat it as the author's best explanation
     *    for why the periodic keyframe exists — the *behavior* (30s IDR cures silent
     *    corruption in the field) is what's load-bearing.
     *
     *    CarPlay encoder teardown is invisible to the user at any reasonable interval.
     *    The 30s value is configurable per-platform — 2s was used historically with no
     *    user-visible impact. Shorter intervals trade iPhone encoder overhead for faster
     *    corruption recovery. Adjust the delay value below as needed.
     *
     * Cancels any pending request first. AA must NOT use this — FRAME resets phone UI.
     */
    @Synchronized
    private fun scheduleDelayedKeyframe() {
        frameIntervalJob?.cancel()
        frameIntervalJob =
            scope.launch(Dispatchers.IO) {
                logInfo("[FRAME_INTERVAL] CarPlay keyframe scheduled (2.5s initial, 30s periodic)", tag = Logger.Tags.VIDEO)

                // Initial delayed keyframe — cold-start safety net
                delay(2500)
                val initialSent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                logInfo("[FRAME_INTERVAL] CarPlay initial keyframe sent=$initialSent", tag = Logger.Tags.VIDEO)

                // Periodic keyframe — mid-session self-healing for unstable platforms (GM Intel VPU)
                var requestCount = 0
                while (isActive) {
                    delay(30000)
                    requestCount++
                    val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                    logDebug("[FRAME_INTERVAL] CarPlay periodic keyframe #$requestCount sent=$sent", tag = Logger.Tags.VIDEO)
                }
            }
    }

    /**
     * Cancel any pending delayed keyframe request.
     */
    @Synchronized
    private fun cancelDelayedKeyframe() {
        val wasActive = frameIntervalJob?.isActive == true
        if (wasActive) {
            logDebug("[FRAME_INTERVAL] Cancelling pending delayed keyframe", tag = Logger.Tags.VIDEO)
            frameIntervalJob?.cancel()
        }
        frameIntervalJob = null
    }

    /**
     * Create a video processor for direct USB -> codec data flow.
     * [DIRECT_HANDOFF]: Data is already in a buffer. Feed codec directly or drop.
     *
     * Video header structure (20 bytes):
     * - offset 0: width (4 bytes)
     * - offset 4: height (4 bytes)
     * - offset 8: encoderState (4 bytes) - flags bitmask: bit 0=offScreen, bits 2-3=encoderType (raw→0=H265, 1=H264, 2=MJPEG; mapping verified against AutoKit source). Echoed in touch-payload flag word for AutoKit compat.
     * - offset 12: pts (4 bytes) - SOURCE PRESENTATION TIMESTAMP (milliseconds, logged only — codec uses elapsed-time PTS)
     * - offset 16: flags (4 bytes) - usually 0 (reserved)
     */
    private fun createVideoProcessor(): UsbDeviceWrapper.VideoDataProcessor {
        return object : UsbDeviceWrapper.VideoDataProcessor {
            override fun processVideoDirect(
                data: ByteArray,
                dataLength: Int,
                sourcePtsMs: Int,
            ) {
                val renderer =
                    h264Renderer ?: run {
                        // Data already read by UsbDeviceWrapper — just discard by returning
                        val now = System.currentTimeMillis()
                        if (now - lastVideoDiscardWarningTime > 2000) {
                            lastVideoDiscardWarningTime = now
                            logWarn("Video frame discarded - H264Renderer not initialized.", tag = Logger.Tags.VIDEO)
                        }
                        return
                    }

                // Start codec on first video if still deferred (PLUGGED arrived but was CarPlay,
                // or fallback for protocols that send video before PLUGGED).
                if (codecDeferred) {
                    startCodecIfDeferred()
                }

                if (++videoFrameCount % 30 == 0L) {
                    logVideoUsb { "processVideoDirect: frame=$videoFrameCount dataLength=$dataLength, pts=$sourcePtsMs" }
                }

                // Parse encoder state from video header for touch flags (AutoKit compatibility).
                // Offset 8: bitmask convention — bit 0 = offScreen, bits 2-3 = encoderType.
                // Confirmed against AutoKit: its video-header parser extracts `(i9 >> 2) & 3` and
                // applies the exact mapping below (0→H265, 1→H264, 2→MJPEG), then stores both
                // values in member fields and packs them into the touch payload at offset 12
                // as `encoderType | (offScreen << 16)` — identical to [MessageSerializer.serializeSingleTouch].
                // Values echoed in touch payload only — not used for codec decisions.
                if (dataLength >= 12) {
                    val flags =
                        (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8) or
                            ((data[10].toInt() and 0xFF) shl 16) or ((data[11].toInt() and 0xFF) shl 24)
                    videoOffScreen = flags and 1
                    val rawEncoder = (flags shr 2) and 3
                    videoEncoderType =
                        when (rawEncoder) {
                            0 -> 2

                            // H265
                            1 -> 1

                            // H264
                            2 -> 4

                            // MJPEG
                            else -> 2
                        }
                }

                // Infer phone type from video header if PLUGGED was missed (mid-session rejoin).
                // Video header: [0..3]=width, [4..7]=height (LE int32). AA tiers are exactly
                // 800x480, 1280x720, or 1920x1080. CarPlay uses arbitrary display dims.
                if (!videoPhoneTypeInferred && currentPhoneType == null && dataLength >= 8) {
                    videoPhoneTypeInferred = true
                    val w =
                        (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) or
                            ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 24)
                    val h =
                        (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) or
                            ((data[6].toInt() and 0xFF) shl 16) or ((data[7].toInt() and 0xFF) shl 24)
                    val isAaTier = (w == 1920 && h == 1080) || (w == 1280 && h == 720) || (w == 800 && h == 480)
                    if (isAaTier) {
                        logInfo("[VIDEO] Inferred ANDROID_AUTO from video header ${w}x$h (mid-session)", tag = Logger.Tags.VIDEO)
                        currentPhoneType = PhoneType.ANDROID_AUTO
                        h264Renderer?.setAndroidAutoMode(true)
                        callback?.onPhoneTypeChanged(PhoneType.ANDROID_AUTO)
                    } else {
                        logInfo("[VIDEO] Inferred CARPLAY from video header ${w}x$h (mid-session)", tag = Logger.Tags.VIDEO)
                        currentPhoneType = PhoneType.CARPLAY
                        h264Renderer?.setAndroidAutoMode(false)
                        callback?.onPhoneTypeChanged(PhoneType.CARPLAY)
                        scheduleDelayedKeyframe()
                    }
                }

                // Skip 20-byte video header, feed H.264 data directly to codec
                if (dataLength > 20) {
                    renderer.feedDirect(data, 20, dataLength - 20)
                }
            }
        }
    }

    private fun tryPrestageCodecCsd(btMac: String) {
        val cacheKey = "${btMac}_${config.width}x${config.height}"
        val prefs = context.getSharedPreferences("carlink_csd_cache", Context.MODE_PRIVATE)
        val spsB64 = prefs.getString("sps_$cacheKey", null) ?: return
        val ppsB64 = prefs.getString("pps_$cacheKey", null) ?: return

        val sps = android.util.Base64.decode(spsB64, android.util.Base64.NO_WRAP)
        val pps = android.util.Base64.decode(ppsB64, android.util.Base64.NO_WRAP)

        logInfo("[DEVICE] CSD cache hit for $cacheKey — pre-warming codec", tag = Logger.Tags.VIDEO)
        h264Renderer?.configureWithCsd(sps, pps)
    }

    private fun parseDevList(json: JSONObject): List<DeviceInfo> {
        val arr = json.optJSONArray("DevList") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id", "")
            if (id.isEmpty()) return@mapNotNull null
            DeviceInfo(
                btMac = id,
                name = obj.optString("name", id), // fallback to MAC if no name
                type = obj.optString("type", ""),
                lastConnected = obj.optString("time", "").ifEmpty { null },
                rfcomm = obj.optString("rfcomm", "").ifEmpty { null },
            )
        }
    }

    private fun log(message: String) {
        logDebug(message, tag = Logger.Tags.ADAPTR)
    }

    // NOTE: [dumpUnknownSummary] fires only from [stop]. Both [handleError] and
    // [rebootAdapter] silently discard the per-session unknown-message counters when they
    // reset state. If you ever need PROTO_UNKNOWN forensics from an error-recovery path
    // (e.g. debugging why a session died from an unrecognized command), add a
    // dumpUnknownSummary() call on those exit paths too — today only "clean" stops emit
    // the [SESSION_SUMMARY] log.
    private fun resetUnknownCounters() {
        unknownMessageTypeCount = 0
        unknownMediaSubtypeCount = 0
        unknownCommandCount = 0
        unknownAudioCommandCount = 0
        unknownPhoneTypeCount = 0
        unknownBoxSettingsKeyCount = 0
        unknownMessageTypes.clear()
        unknownMediaSubtypes.clear()
        unknownCommandIds.clear()
        unknownAudioCommandIds.clear()
        videoFrameCount = 0L
    }

    private fun dumpUnknownSummary() {
        val total = unknownMessageTypeCount + unknownMediaSubtypeCount +
            unknownCommandCount + unknownAudioCommandCount +
            unknownPhoneTypeCount + unknownBoxSettingsKeyCount
        if (total == 0) return

        val parts = mutableListOf<String>()
        if (unknownMessageTypeCount > 0)
            parts += "msgTypes=${unknownMessageTypeCount}x${unknownMessageTypes.map { "0x${it.toString(16)}" }}"
        if (unknownMediaSubtypeCount > 0)
            parts += "mediaSubtypes=${unknownMediaSubtypeCount}x$unknownMediaSubtypes"
        if (unknownCommandCount > 0)
            parts += "commands=${unknownCommandCount}x${unknownCommandIds.map { "0x${it.toString(16)}" }}"
        if (unknownAudioCommandCount > 0)
            parts += "audioCmds=${unknownAudioCommandCount}x$unknownAudioCommandIds"
        if (unknownPhoneTypeCount > 0)
            parts += "phoneTypes=${unknownPhoneTypeCount}x"
        if (unknownBoxSettingsKeyCount > 0)
            parts += "boxSettingsKeys=${unknownBoxSettingsKeyCount}x"
        logWarn(
            "[SESSION_SUMMARY] Unknown data received this session ($total total): ${parts.joinToString(", ")}",
            tag = Logger.Tags.PROTO_UNKNOWN,
        )
    }
}
