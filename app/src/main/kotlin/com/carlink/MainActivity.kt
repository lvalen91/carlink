package com.carlink

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carlink.cluster.ClusterBindingState
import com.carlink.logging.FileLogManager
import com.carlink.logging.LogPreset
import com.carlink.logging.Logger
import com.carlink.logging.LoggingPreferences
import com.carlink.logging.apply
import com.carlink.logging.logWarn
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.media.MediaSessionManager
import com.carlink.util.LogCallback
import com.carlink.util.WindowMetricsCompat
import com.carlink.navigation.NavigationStateManager
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.KnownDevices
import com.carlink.ui.MainScreen
import com.carlink.ui.SettingsScreen
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModePreference
import com.carlink.ui.theme.CarlinkTheme
import com.carlink.util.IconAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Main Activity — Entry Point for Carlink Native.
 *
 * The sole Activity in the app; owns every long-lived session object.
 *
 * Responsibilities:
 *  - Boot sequencing (logging → cluster component toggle → display mode → deferred
 *    CarlinkManager init) on the main thread.
 *  - Ownership of [CarlinkManager] and [FileLogManager] (nullable to survive being
 *    destroyed before init completes).
 *  - Runtime permission chain (mic → location; Android system dialogs are sequential,
 *    so the mic callback triggers the location request rather than firing in parallel).
 *  - Display-mode policy: four [DisplayMode] branches control system-bar visibility,
 *    cutout clipping, and SafeArea metadata. See [applyDisplayMode] + [initializeCarlinkManager].
 *  - In-place session reinit on display-mode change via [reinitializeForDisplayMode]
 *    (tier-2 restart; replaces the historical Process.killProcess approach).
 *  - USB attach (onNewIntent, via manifest intent-filter) + detach (BroadcastReceiver)
 *    handling for faster disconnect detection than USB-transfer error paths provide.
 *  - Cluster binding lifecycle: [launchCarAppActivity] starts the Templates Host →
 *    cluster chain, [restartClusterBinding] tears it down and re-establishes.
 *  - Compose UI host: the top-level [CarlinkApp] composable keeps [MainScreen]
 *    continuously composed (to preserve the VideoSurface / HWC plane) and slides
 *    [SettingsScreen] on top via AnimatedVisibility rather than replacing it.
 */
class MainActivity : ComponentActivity() {
    // Nullable to prevent UninitializedPropertyAccessException if Activity
    // is destroyed before initialization completes (e.g., low memory kill)
    private var carlinkManager: CarlinkManager? = null
    private var fileLogManager: FileLogManager? = null

    /**
     * App-scope MediaSession reference. Owned and lifecycle-managed by
     * [CarlinkMediaBrowserService] — this Activity only reads the live singleton via
     * [MediaSessionManager.instance] / [MediaSessionManager.getOrCreate] to pass into
     * [CarlinkManager]. Never released from here; the MBS releases on its own
     * `onDestroy`. Survival across tier-2 CarlinkManager rebuilds is now a property of
     * the Service lifecycle (Service stays alive across Activity destruction), not a
     * property of this field.
     *
     * Boot-race fix (2026-05-03): previously this Activity created the manager, which
     * meant the session didn't exist until the user tapped the launcher icon — AAOS
     * auto-launches the MBS at boot and probes onGetSession within ~50ms, so the
     * Activity-scoped initialize never won the race. Ownership moved to MBS.onCreate.
     */
    private var mediaSessionManager: MediaSessionManager? = null

    /**
     * LogCallback routed to the app's [com.carlink.logging.Logger] + any active file log.
     * Passed to [MediaSessionManager] so its lifecycle events surface through the same
     * infrastructure that [CarlinkManager] uses. Stable singleton — one instance per
     * Activity lifetime, safe to share across rebuilds.
     */
    private val mediaSessionLogCallback =
        object : LogCallback {
            override fun log(message: String) {
                logInfo(message, tag = "MEDIA_SESSION")
            }

            override fun log(tag: String, message: String) {
                logInfo(message, tag = tag)
            }
        }
    // Main-thread-only invariant: written in reinitializeForDisplayMode() and read from
    // initializeCarlinkManager()/onResume(). Both sides dispatch on the main thread
    // (decorView.post, postDelayed, lifecycle callbacks), so no @Volatile needed.
    private var currentDisplayMode: DisplayMode = DisplayMode.SYSTEM_UI_VISIBLE

    // Observable state for Compose — replacement triggers recomposition of CarlinkApp
    private val carlinkManagerState = mutableStateOf<CarlinkManager?>(null)
    private val displayModeState = mutableStateOf(DisplayMode.SYSTEM_UI_VISIBLE)

    // Pending reinit handler — tracked for cancellation on rapid display mode changes
    private var pendingReinitRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Permission launchers — chained: mic callback triggers location request
    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Location permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
        }

    // Android permission dialogs are sequential, not overlappable — launching both
    // in parallel would drop the second. The mic callback triggers location, and
    // both denials are survivable: CarlinkManager still constructs; mic features
    // (Siri, AA voice, phone call capture) fail silently, and GPS forwarding
    // (AdapterConfig.gpsForwarding) is a no-op without ACCESS_FINE_LOCATION.
    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Microphone permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
            // Chain: request location after mic dialog completes
            requestLocationPermission()
        }

    /**
     * BroadcastReceiver for USB device detachment events.
     *
     * Provides immediate detection when the Carlinkit adapter is physically
     * disconnected, enabling faster recovery than waiting for USB transfer errors
     * to surface. Filters to known Carlinkit VID/PID pairs before signaling
     * [CarlinkManager.onUsbDeviceDetached] — other USB device events are ignored.
     */
    private val usbDetachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                    device?.let {
                        // Only handle if it's a known Carlinkit device
                        if (KnownDevices.isKnownDevice(it.vendorId, it.productId)) {
                            logWarn(
                                "[USB_DETACH] Carlinkit device detached: VID=0x${it.vendorId.toString(16)} " +
                                    "PID=0x${it.productId.toString(16)} path=${it.deviceName}",
                                tag = "MAIN",
                            )
                            // Notify CarlinkManager of the detachment (null-safe)
                            carlinkManager?.onUsbDeviceDetached()
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        enableEdgeToEdge()

        // Keep screen on during projection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize logging
        initializeLogging()

        // Apply cluster service component state before Templates Host discovers it
        AdapterConfigPreference.getInstance(this).applyClusterComponentState(this)

        // Load display mode preference and apply BEFORE calculating display dimensions
        // This ensures correct viewport sizing - fullscreen immersive uses full screen (1920x1080),
        // other modes use usable area excluding visible system bars
        loadAndApplyDisplayMode()

        // Defer CarlinkManager creation to the next main-looper tick so the decorView is
        // attached before initializeCarlinkManager() reads insets. WindowMetricsCompat
        // .stableWindowInsets requires an attached decorView; otherwise it returns CONSUMED
        // (all-zero) insets and the resolution mis-computes.
        // NOTE: currentWindowMetrics.bounds is the FULL display (2400x960) regardless of
        // decorFitsSystemWindows — verified via dumpsys (mBounds/mAppBounds = 2400x960 in
        // SYSTEM_UI_VISIBLE). The 788 usable height comes from subtracting the system-bar
        // insets exactly ONCE in initializeCarlinkManager()'s when-branch, not from a clipped
        // bounds. (Prior comment claimed bounds reflected a post-clip 2400x788 rect — false;
        // that would double-count with the inset subtraction and yield 616.)
        // Compose renders `if (manager != null)` while we wait, so the UI boots with
        // the loading overlay and swaps in as soon as the manager is ready.
        window.decorView.post {
            if (!isDestroyed && !isFinishing) {
                initializeCarlinkManager()
            }
        }

        // Request permissions if needed (location is chained after mic dialog)
        requestMicrophonePermission()

        // Register USB detachment receiver for immediate disconnect detection
        registerUsbDetachReceiver()

        // Launch CarAppActivity to trigger Templates Host → cluster binding chain.
        // Skipped entirely when cluster navigation is disabled — no reason to start
        // the CarAppActivity → RendererService → CarlinkClusterService chain.
        if (AdapterConfigPreference.getInstance(this).getClusterNavigationSync()) {
            // Delayed to avoid interrupting USB permission dialog on first connect.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing) {
                    launchCarAppActivity()
                }
            }, 4000)
        }

        // Set up Compose UI
        // CarlinkManager is observed via mutableStateOf — replacement during display mode
        // reinit triggers full recomposition without Activity restart.
        setContent {
            val manager = carlinkManagerState.value
            val displayMode = displayModeState.value
            CarlinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (manager != null) {
                        CarlinkApp(
                            carlinkManager = manager,
                            fileLogManager = fileLogManager,
                            displayMode = displayMode,
                            onResetCluster = ::restartClusterBinding,
                            onReinitForDisplayMode = ::reinitializeForDisplayMode,
                            // Reset Connection now rebuilds the full manager via the same
                            // path display-mode changes use. Turns a probabilistic repair
                            // into a deterministic one: new SurfaceView, fresh HWC plane,
                            // fresh WindowMetrics, renegotiated Open() — every time.
                            onResetConnection = { reinitializeForDisplayMode(currentDisplayMode) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore display mode when returning to app
        // System may have shown bars while app was in background
        applyDisplayMode(currentDisplayMode)
    }

    override fun onStart() {
        super.onStart()
        // Resume video decoding when app returns to foreground
        // On AAOS, Surface may remain valid while app is in background, but
        // BufferQueue can stall. Resume codec and request keyframe for immediate video.
        logInfo("[LIFECYCLE] onStart - resuming video", tag = "MAIN")
        carlinkManager?.resumeVideo()
    }

    override fun onStop() {
        super.onStop()
        // Pause video decoding when app goes to background
        // On AAOS, when another app covers this app (Maps, Phone, etc.), the Surface
        // may remain valid but SurfaceFlinger stops consuming frames. This causes
        // BufferQueue to fill up, stalling the decoder. Flushing prevents this.
        // USB connection and audio continue unaffected.
        logInfo("[LIFECYCLE] onStop - pausing video", tag = "MAIN")
        carlinkManager?.pauseVideo()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Only re-launch cluster binding for actual USB re-attach events.
        // The "bring back" REORDER_TO_FRONT intent from launchCarAppActivity()
        // also arrives here (singleTop) — must NOT re-trigger the launch cycle.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Only launch cluster binding if cluster navigation is enabled
            if (AdapterConfigPreference.getInstance(this).getClusterNavigationSync()) {
                logInfo("[LIFECYCLE] onNewIntent: USB_DEVICE_ATTACHED — re-launching cluster binding", tag = "MAIN")
                launchCarAppActivity()
            }

            // Auto-connect when adapter re-enumerates (e.g., after reboot or replug)
            val manager = carlinkManager
            if (manager != null && manager.state == CarlinkManager.State.DISCONNECTED) {
                logInfo("[LIFECYCLE] Manager disconnected — auto-starting connection", tag = "MAIN")
                CoroutineScope(Dispatchers.IO).launch {
                    manager.start()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister USB detachment receiver
        unregisterUsbDetachReceiver()

        // Release the CarlinkManager (it detaches its transport callback from the
        // MediaSession but does NOT release it — see CarlinkManager.release KDoc).
        // The MediaSession itself is owned by CarlinkMediaBrowserService; it releases
        // on its own onDestroy. Just drop the local reference here.
        carlinkManager?.release()
        mediaSessionManager = null
        fileLogManager?.release()

        logInfo("MainActivity destroyed", tag = "MAIN")
    }

    private fun initializeLogging() {
        // Initialize file logging
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val appVersion = "${packageInfo.versionName}+${packageInfo.longVersionCode}"

        fileLogManager =
            FileLogManager(
                context = this,
                sessionPrefix = "carlink",
                appVersion = appVersion,
            )

        // Configure debug-only logging based on build type
        // In release builds, verbose pipeline logging is disabled for performance
        // Users can re-enable via Pipeline Debug preset in settings
        val isDebugBuild = BuildConfig.DEBUG
        Logger.setDebugLoggingEnabled(isDebugBuild)

        // Apply default log preset based on build type
        // Release: SILENT (errors only) - user can override via settings
        // Debug: NORMAL (standard logging)
        if (!isDebugBuild) {
            LogPreset.SILENT.apply()
        }

        // Auto-restore user's log preset and file logging from previous session.
        // Must restore preset BEFORE enabling file logging — otherwise SILENT
        // filters everything and the log file stays header-only.
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = LoggingPreferences.getInstance(this@MainActivity)
            val preset = prefs.logLevelFlow.first()
            preset.apply()
            val enabled = prefs.loggingEnabledFlow.first()
            if (enabled) {
                fileLogManager?.enable()
            }
        }

        logInfo("Carlink Native starting - version $appVersion", tag = "MAIN")
        logInfo("[LOGGING] Debug logging: ${if (isDebugBuild) "ENABLED" else "DISABLED (release build)"}", tag = "MAIN")
    }

    private fun initializeCarlinkManager() {
        // Get window metrics to determine USABLE area (excluding system UI).
        // WindowMetricsCompat falls back to Display.getRealMetrics + WindowInsetsCompat
        // on API 29 (AAOS 10); the API 30+ path is unchanged.
        val bounds = WindowMetricsCompat.displayBounds(windowManager)
        val windowInsets = WindowMetricsCompat.stableWindowInsets(windowManager, window.decorView)

        // Separate inset sources for per-mode SafeArea computation
        val systemBarInsets =
            windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val cutoutInsets =
            windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())

        // Compute video resolution and SafeArea insets per display mode
        val videoWidth: Int
        val videoHeight: Int
        val safeInsetTop: Int
        val safeInsetBottom: Int
        val safeInsetLeft: Int
        val safeInsetRight: Int

        when (currentDisplayMode) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // System bars + cutouts both reduce video area. No SafeArea needed.
                videoWidth = bounds.width() - systemBarInsets.left - systemBarInsets.right -
                    cutoutInsets.left - cutoutInsets.right
                videoHeight = bounds.height() - systemBarInsets.top - systemBarInsets.bottom -
                    cutoutInsets.top - cutoutInsets.bottom
                safeInsetTop = 0
                safeInsetBottom = 0
                safeInsetLeft = 0
                safeInsetRight = 0
            }

            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Nav bar visible (subtract from video), status bar hidden (cutout exposed top/sides)
                videoWidth = bounds.width() - systemBarInsets.left - systemBarInsets.right
                videoHeight = bounds.height() - systemBarInsets.bottom
                safeInsetTop = cutoutInsets.top
                safeInsetBottom = 0 // nav bar covers bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }

            DisplayMode.NAV_BAR_HIDDEN -> {
                // Status bar visible (subtract from video), nav bar hidden (cutout exposed bottom/sides)
                videoWidth = bounds.width()
                videoHeight = bounds.height() - systemBarInsets.top
                safeInsetTop = 0 // status bar covers top
                safeInsetBottom = cutoutInsets.bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }

            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                // Full screen, all cutout areas exposed
                videoWidth = bounds.width()
                videoHeight = bounds.height()
                safeInsetTop = cutoutInsets.top
                safeInsetBottom = cutoutInsets.bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }
        }

        // Get DPI from display metrics
        val displayMetrics = resources.displayMetrics
        val dpi = displayMetrics.densityDpi

        // Round to even numbers for H.264 compatibility
        val evenWidth = videoWidth and 1.inv()
        val evenHeight = videoHeight and 1.inv()

        // Load icons from assets for adapter initialization
        val (icon120, icon180, icon256) = IconAssets.loadIcons(this)
        val iconsLoaded = icon120 != null && icon180 != null && icon256 != null

        // Load bundled aa_gps_fix.sh — pushed to adapter /tmp on every init (full + minimal).
        // Inline single-asset load; no dedicated util warranted for one file.
        val gpsFixScriptBytes =
            try {
                this.assets.open("aa_gps_fix.sh").use { it.readBytes() }
            } catch (e: java.io.IOException) {
                logWarn("[GPS_FIX] Failed to load aa_gps_fix.sh asset: ${e.message}")
                null
            }

        // Load bundled patched ARMiPhoneIAP2 — pushed to adapter /tmp/bin on every init to
        // preempt phone_link_deamon's first-spawn factory copy. 233 KiB, single SendFile.
        val patchedIap2BinaryBytes =
            try {
                this.assets.open("ARMiPhoneIAP2.patched").use { it.readBytes() }
            } catch (e: java.io.IOException) {
                logWarn("[IAP2_PATCH] Failed to load ARMiPhoneIAP2.patched asset: ${e.message}")
                null
            }

        // Platform-specific overrides. PlatformDetector.detect is cheap (Build props + one
        // MediaCodecList scan); CarlinkManager.initialize re-detects but that's idempotent.
        // gminfo37 (and the AAOS emulator for development parity): hide the CarPlay OEM
        // "Exit" icon — GM AAOS has its own back-nav; the OEM tile collides visually.
        // Other platforms keep the OEM icon visible (existing factory behavior). Wired into
        // /etc/airplay.conf via AdapterConfig.oemIconVisible which generateAirplayConfig now
        // honors.
        val platformInfo = com.carlink.platform.PlatformDetector.detect(this)
        val oemIconVisibleForPlatform = !platformInfo.requiresImmersiveDefaults()

        // Load user-configured adapter settings from sync cache (instant, no I/O blocking)
        // These are optional - only configured settings are sent to the adapter
        val userConfig = AdapterConfigPreference.getInstance(this).getUserConfigSync()

        // Apply video resolution preference (must be before ViewArea/SafeArea construction)
        // AUTO = use detected usable dimensions, otherwise use user-selected resolution
        val userSelectedResolution = !userConfig.videoResolution.isAuto
        val (configWidth, configHeight) =
            if (userConfig.videoResolution.isAuto) {
                Pair(evenWidth, evenHeight)
            } else {
                // User selected a specific resolution - use it for adapter config
                // Note: Surface size remains the actual display size for touch normalization
                Pair(userConfig.videoResolution.width, userConfig.videoResolution.height)
            }

        // Build binary ViewArea/SafeArea data using the configured resolution.
        // ViewArea/SafeArea must match OPEN message dimensions (safeArea ⊆ viewArea ⊆ display).
        val viewAreaData = buildViewAreaData(configWidth, configHeight)
        val safeAreaData =
            if (userSelectedResolution) {
                // Scale cutout insets from display coordinates to custom resolution coordinates.
                // Linear scaling assumes cutouts are rectangular window-edge margins (true on
                // AAOS displays we've seen). Non-uniform X/Y scaling is OK here because cutout
                // insets are axis-aligned.
                val scaleX = configWidth.toFloat() / evenWidth.toFloat()
                val scaleY = configHeight.toFloat() / evenHeight.toFloat()
                buildSafeAreaData(
                    configWidth,
                    configHeight,
                    (safeInsetTop * scaleY).toInt(),
                    (safeInsetBottom * scaleY).toInt(),
                    (safeInsetLeft * scaleX).toInt(),
                    (safeInsetRight * scaleX).toInt(),
                )
            } else {
                buildSafeAreaData(configWidth, configHeight, safeInsetTop, safeInsetBottom, safeInsetLeft, safeInsetRight)
            }

        // Map user config enums to AdapterConfig values
        val micType =
            when (userConfig.micSource) {
                com.carlink.ui.settings.MicSourceConfig.APP -> "os"
                com.carlink.ui.settings.MicSourceConfig.PHONE -> "box"
            }
        val wifiType =
            when (userConfig.wifiBand) {
                com.carlink.ui.settings.WiFiBandConfig.BAND_5GHZ -> "5ghz"
                com.carlink.ui.settings.WiFiBandConfig.BAND_24GHZ -> "24ghz"
            }

        val config =
            AdapterConfig(
                width = configWidth,
                height = configHeight,
                fps = userConfig.fps.fps,
                dpi = dpi,
                // Mark if user explicitly selected a resolution (non-AUTO)
                userSelectedResolution = userSelectedResolution,
                icon120Data = icon120,
                icon180Data = icon180,
                icon256Data = icon256,
                gpsFixScriptData = gpsFixScriptBytes,
                patchedIap2BinaryData = patchedIap2BinaryBytes,
                oemIconVisible = oemIconVisibleForPlatform,
                // User-configured audio transfer mode (false=adapter, true=bluetooth)
                audioTransferMode = userConfig.audioTransferMode,
                // Hardcoded to 48kHz - professional quality audio for GM AAOS
                sampleRate = 48000,
                // User-configured mic, wifi, call quality, and media delay
                micType = micType,
                wifiType = wifiType,
                callQuality = userConfig.callQuality.value,
                mediaDelay = userConfig.mediaDelay.delayMs,
                viewAreaData = viewAreaData,
                safeAreaData = safeAreaData,
                gpsForwarding = userConfig.gpsForwarding,
            )

        logInfo(
            "[WINDOW] Bounds: ${bounds.width()}x${bounds.height()}, " +
                "Video: ${evenWidth}x$evenHeight, " +
                "Cutout: T:${cutoutInsets.top} B:${cutoutInsets.bottom} " +
                "L:${cutoutInsets.left} R:${cutoutInsets.right}, " +
                "DisplayMode: ${currentDisplayMode.name}",
            tag = "MAIN",
        )
        logInfo("Display config: ${config.width}x${config.height}@${config.fps}fps, ${config.dpi}dpi", tag = "MAIN")
        logInfo(
            "Icons loaded: $iconsLoaded " +
                "(120: ${icon120?.size ?: 0}B, 180: ${icon180?.size ?: 0}B, " +
                "256: ${icon256?.size ?: 0}B)",
            tag = "MAIN",
        )
        logInfo(
            "[ADAPTER_CONFIG] User config: " +
                "audioTransferMode=${if (userConfig.audioTransferMode) "bluetooth" else "adapter"}, " +
                "sampleRate=48000Hz (hardcoded), mic=$micType, wifi=$wifiType, " +
                "callQuality=${userConfig.callQuality.name}, " +
                "mediaDelay=${userConfig.mediaDelay.name}(${userConfig.mediaDelay.delayMs}ms), " +
                "resolution=${userConfig.videoResolution.toStorageString()} (adapter: ${configWidth}x$configHeight)",
            tag = "MAIN",
        )

        applyAudioTransferModeToMediaSession(config.audioTransferMode)

        carlinkManager = CarlinkManager(this, config, mediaSessionManager)
        carlinkManagerState.value = carlinkManager
        displayModeState.value = currentDisplayMode
    }

    /**
     * Map the new config's `audioTransferMode` onto the app-scope [MediaSessionManager]
     * singleton (owned by [CarlinkMediaBrowserService]).
     *
     * - ADAPTER mode: take a reference to the singleton so [CarlinkManager] can publish
     *   metadata. The MBS already initialized it at boot; we never call `initialize()`
     *   from the Activity anymore.
     * - BLUETOOTH mode: drop our local reference and flip the session to placeholder
     *   via [MediaSessionManager.setInactive]. Crucially we do NOT call `release()` —
     *   destroying the session token here would re-trigger the boot-race-equivalent
     *   stale-card bug if the user toggles back to ADAPTER.
     *
     * Defensive bootstrap: if the singleton somehow isn't present yet (MBS not started
     * for any reason), we call [MediaSessionManager.getOrCreate] + `initialize()` to
     * cover the gap. In normal flow this branch is never taken.
     *
     * @param audioTransferMode `false` = ADAPTER (audio routed via USB, media session
     *   actively published), `true` = BLUETOOTH (audio via phone BT, session stays
     *   registered but inactive).
     */
    private fun applyAudioTransferModeToMediaSession(audioTransferMode: Boolean) {
        if (!audioTransferMode) {
            if (mediaSessionManager == null) {
                mediaSessionManager = MediaSessionManager.instance()
                    ?: try {
                        // Defensive — should not be reached in normal flow because MBS.onCreate
                        // already bootstrapped the singleton. Pass applicationContext for the
                        // same StaticFieldLeak-free lifetime guarantees as the MBS path.
                        MediaSessionManager.getOrCreate(applicationContext, mediaSessionLogCallback).apply {
                            initialize()
                        }.also {
                            logWarn(
                                "[MEDIA_SESSION] singleton missing — bootstrapped from Activity (unexpected)",
                                tag = "MAIN",
                            )
                        }
                    } catch (e: Exception) {
                        logWarn(
                            "[MEDIA_SESSION] bootstrap failed — running without AAOS media integration: ${e.message}",
                            tag = "MAIN",
                        )
                        null
                    }
                if (mediaSessionManager != null) {
                    logInfo("[MEDIA_SESSION] Singleton acquired (ADAPTER mode)", tag = "MAIN")
                }
            }
        } else {
            mediaSessionManager?.let {
                logInfo("[MEDIA_SESSION] Audio mode switched to BLUETOOTH — flipping session inactive", tag = "MAIN")
                it.setInactive()
            }
            mediaSessionManager = null
        }
    }

    /**
     * Reinitialize the adapter session for a new display mode WITHOUT killing the app.
     *
     * Replaces the old Process.killProcess() approach. Performs a Tier-2 session restart:
     * 1. Save new display mode preference
     * 2. Stop adapter session (graceful teardown)
     * 3. Release old CarlinkManager
     * 4. Apply new system bar visibility
     * 5. Recalculate resolution from fresh WindowMetrics
     * 6. Create new CarlinkManager with new AdapterConfig
     * 7. Compose recomposes via carlinkManagerState → new surface → initialize() → start()
     *
     * The adapter sees a clean disconnect + reconnect with correct new resolution.
     * End state is identical to kill+relaunch.
     */
    fun reinitializeForDisplayMode(newMode: DisplayMode) {
        // Cancel any pending reinit from a previous rapid display mode change
        pendingReinitRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingReinitRunnable = null

        val displayModeChanged = newMode != currentDisplayMode
        logInfo(
            "[DISPLAY_REINIT] Begin — switching from ${currentDisplayMode.name} to ${newMode.name}" +
                " (displayModeChanged=$displayModeChanged)",
            tag = "MAIN",
        )

        // When display mode changes, reset video resolution to Auto.
        // The old custom resolution was calculated for the old display bounds —
        // keeping it would cause stretch/shrink with the new bounds.
        // Write sync cache directly (not DataStore) to guarantee getUserConfigSync()
        // reads the updated value when initializeCarlinkManager() runs after 200ms.
        if (displayModeChanged) {
            val adapterConfigPref = AdapterConfigPreference.getInstance(this)
            val currentRes = adapterConfigPref.getUserConfigSync().videoResolution
            if (!currentRes.isAuto) {
                logInfo(
                    "[DISPLAY_REINIT] Resetting video resolution from ${currentRes.toStorageString()} to AUTO" +
                        " (display mode changed, old resolution invalid for new bounds)",
                    tag = "MAIN",
                )
                // Sync cache write is immediate — guarantees initializeCarlinkManager reads AUTO
                adapterConfigPref.setVideoResolutionSync(
                    com.carlink.ui.settings.VideoResolutionConfig.AUTO,
                )
            }
        }

        // 1. Tear down current adapter session
        val oldManager = carlinkManager
        if (oldManager != null) {
            logInfo("[DISPLAY_REINIT] Stopping current adapter session", tag = "MAIN")
            // Clear Compose reference FIRST — stops surface callbacks into old manager
            carlinkManagerState.value = null
            oldManager.release()
            carlinkManager = null
            logInfo("[DISPLAY_REINIT] Old CarlinkManager released", tag = "MAIN")
        }

        // 2. Apply new display mode (shows/hides system bars)
        currentDisplayMode = newMode
        applyDisplayMode(newMode)
        logInfo("[DISPLAY_REINIT] Applied display mode: ${newMode.name}", tag = "MAIN")

        // 3. Wait for system-bar animation + WindowMetrics to settle after bar visibility
        //    change, then rebuild CarlinkManager with fresh resolution. 200ms (not the
        //    more naive "one frame" = 16ms) covers the animated system-bar transition
        //    (typically 100–150ms on AAOS) plus the async WM attribute-change propagation
        //    triggered by `window.attributes = lp` in applyDisplayMode().
        val reinitRunnable = Runnable {
            if (!isDestroyed && !isFinishing) {
                logInfo("[DISPLAY_REINIT] Rebuilding CarlinkManager with new metrics", tag = "MAIN")
                initializeCarlinkManager()
                logInfo("[DISPLAY_REINIT] Complete — CarlinkManager recreated", tag = "MAIN")
            }
            pendingReinitRunnable = null
        }
        pendingReinitRunnable = reinitRunnable
        mainHandler.postDelayed(reinitRunnable, 200)
    }

    /** Build HU_VIEWAREA_INFO (24 bytes): [screen_w, screen_h, view_w, view_h, originX, originY] */
    private fun buildViewAreaData(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width)
            .putInt(height) // screen dims
            .putInt(width)
            .putInt(height) // viewarea dims (same)
            .putInt(0)
            .putInt(0) // origin
            .array()

    /** Build HU_SAFEAREA_INFO (20 bytes): [safe_w, safe_h, originX, originY, drawOutside] */
    private fun buildSafeAreaData(
        videoW: Int,
        videoH: Int,
        insetTop: Int,
        insetBottom: Int,
        insetLeft: Int,
        insetRight: Int,
    ): ByteArray {
        val safeW = videoW - insetLeft - insetRight
        val safeH = videoH - insetTop - insetBottom
        val hasInsets = (insetTop or insetBottom or insetLeft or insetRight) != 0
        return ByteBuffer
            .allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(safeW)
            .putInt(safeH)
            .putInt(insetLeft)
            .putInt(insetTop)
            .putInt(if (hasInsets) 1 else 0) // wallpaper outside safe area only when cutouts exist
            .array()
    }

    private fun requestMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Microphone permission already granted", tag = "MAIN")
                // Already granted — chain to location request directly
                requestLocationPermission()
            }

            else -> {
                logInfo("Requesting microphone permission", tag = "MAIN")
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                // Location will be requested in mic launcher callback
            }
        }
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Location permission already granted", tag = "MAIN")
            }

            else -> {
                logInfo("Requesting location permission", tag = "MAIN")
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    /**
     * Loads display mode preference and applies it.
     * Uses synchronous SharedPreferences cache to avoid ANR.
     *
     * No try/catch: a SharedPreferences read exception here would propagate through
     * onCreate and crash the Activity. Low probability (the file is tiny and local),
     * but a failure path exists. The read's default is `DisplayMode.SYSTEM_UI_VISIBLE`
     * (see DisplayModePreference.getDisplayModeSync), matching the field initializer.
     */
    private fun loadAndApplyDisplayMode() {
        // Read preference from sync cache (instant, no I/O blocking)
        // This ensures the viewport is correctly sized before the first build
        currentDisplayMode = DisplayModePreference.getInstance(this).getDisplayModeSync()
        displayModeState.value = currentDisplayMode

        applyDisplayMode(currentDisplayMode)
        logInfo("[DISPLAY_MODE] Applied mode: ${currentDisplayMode.name}", tag = "MAIN")
    }

    /**
     * Applies the specified display mode by showing/hiding system bars.
     *
     * @param mode The display mode to apply
     */
    private fun applyDisplayMode(mode: DisplayMode) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val lp = window.attributes
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is API 30; SHORT_EDGES (API 28) is the
        // pre-30 equivalent. gminfo3.7 has no display cutout, so the two are identical
        // on the target hardware.
        val edgeToEdgeCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

        when (mode) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // Edge-to-edge (window stays full-display); Compose windowInsetsPadding in
                // MainScreen subtracts the system bars exactly once. Using decorFits=true here
                // double-counts the bars on warm re-dispatch (788 -> 616). [FIX UNDER TEST]
                WindowCompat.setDecorFitsSystemWindows(window, false)
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = lp
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }

            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Edge-to-edge: video extends into hidden-bar + cutout regions.
                // SafeArea metadata tells the phone to keep clickable UI out of those zones.
                WindowCompat.setDecorFitsSystemWindows(window, false)
                lp.layoutInDisplayCutoutMode = edgeToEdgeCutoutMode
                window.attributes = lp
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            DisplayMode.NAV_BAR_HIDDEN -> {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                lp.layoutInDisplayCutoutMode = edgeToEdgeCutoutMode
                window.attributes = lp
                windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                lp.layoutInDisplayCutoutMode = edgeToEdgeCutoutMode
                window.attributes = lp
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /**
     * Registers the USB detachment BroadcastReceiver.
     *
     * This enables immediate detection of physical adapter removal,
     * providing faster recovery than waiting for USB transfer errors.
     */
    private fun registerUsbDetachReceiver() {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, filter)
        }
        logInfo("[USB_DETACH] Registered USB detachment receiver", tag = "MAIN")
    }

    /**
     * Launches CarAppActivity in a separate task to trigger Templates Host binding.
     * Combined with taskAffinity="zeno.carlink.templates" and singleTask launch mode,
     * this opens in its own task stack without disturbing MainActivity.
     *
     * Guarded by [ClusterBindingState.sessionAlive] — only launches if no live session
     * exists. The cluster session can be torn down by the Templates Host / Car App Host
     * around USB re-enumeration (the specific "RendererServiceBinder.terminate()"
     * mechanism previously cited in comments is not present in this repo — treat the
     * teardown as black-box Host behavior). Must therefore be callable from both
     * onCreate() and onNewIntent().
     *
     * Post-launch, re-elevates MainActivity with FLAG_ACTIVITY_REORDER_TO_FRONT after
     * 1s. That 1s is the upper-bound pinned by [ClusterMainSession.RelayScreen]'s KDoc
     * ("visible for ~1s before MainActivity returns to front") — change here and the
     * RelayScreen duration changes too.
     */
    private fun launchCarAppActivity() {
        if (ClusterBindingState.sessionAlive) {
            logInfo("[CLUSTER] Cluster session still alive — will retry after teardown", tag = "MAIN")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing && !ClusterBindingState.sessionAlive) {
                    logInfo("[CLUSTER] Old session torn down — retrying launch", tag = "MAIN")
                    launchCarAppActivity()
                }
            }, 4000)
            return
        }

        try {
            val intent =
                Intent().apply {
                    setClassName(this@MainActivity, "androidx.car.app.activity.CarAppActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
            startActivity(intent)
            logInfo("[CLUSTER] Launched CarAppActivity for Templates Host binding", tag = "MAIN")

            // Bring MainActivity back quickly — binding is IPC-based and doesn't
            // need CarAppActivity in the foreground. 1s is enough for the handshake.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing) {
                    val bringBack =
                        Intent(this@MainActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        }
                    startActivity(bringBack)
                    logInfo("[CLUSTER] Brought MainActivity back to foreground", tag = "MAIN")
                }
            }, 1000)
        } catch (e: Exception) {
            logWarn("[CLUSTER] Failed to launch CarAppActivity: ${e.message}", tag = "MAIN")
        }
    }

    /**
     * Tears down the cluster binding chain and re-establishes it.
     * Clears nav state → kills CarAppActivity task → waits for teardown → relaunches.
     *
     * [ActivityManager.getAppTasks] returns only the calling package's tasks, so the
     * CarAppActivity match is guaranteed to be ours. `singleTask` on the manifest
     * declaration guarantees at most one matching task — the `break` after first match
     * is intentional.
     */
    fun restartClusterBinding() {
        logWarn("[CLUSTER] restartClusterBinding() — tearing down and re-establishing binding chain", tag = "MAIN")
        NavigationStateManager.clear()
        val am = getSystemService(ActivityManager::class.java)
        for (appTask in am.appTasks) {
            if (appTask.taskInfo.baseActivity?.className == "androidx.car.app.activity.CarAppActivity") {
                logInfo("[CLUSTER] Finishing CarAppActivity task (taskId=${appTask.taskInfo.taskId})", tag = "MAIN")
                appTask.finishAndRemoveTask()
                break
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed && !isFinishing) {
                logInfo("[CLUSTER] Re-launching CarAppActivity after teardown", tag = "MAIN")
                launchCarAppActivity()
            }
        }, 2000)
    }

    /**
     * Unregisters the USB detachment BroadcastReceiver.
     */
    private fun unregisterUsbDetachReceiver() {
        try {
            unregisterReceiver(usbDetachReceiver)
            logInfo("[USB_DETACH] Unregistered USB detachment receiver", tag = "MAIN")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered or already unregistered
            logWarn("[USB_DETACH] Receiver already unregistered: ${e.message}", tag = "MAIN")
        }
    }
}

/**
 * Main Composable App with Overlay Navigation
 *
 * ARCHITECTURE: Uses overlay/stack pattern instead of screen replacement.
 * MainScreen stays composed when SettingsScreen is pushed on top.
 *
 * WHY: The VideoSurface in MainScreen uses a SurfaceView.
 * When MainScreen is replaced (disposed), the Surface is destroyed,
 * causing "BufferQueue has been abandoned" errors if the MediaCodec is still
 * running. By keeping MainScreen always in composition and overlaying
 * SettingsScreen on top, the video continues playing uninterrupted.
 *
 * BEHAVIOR:
 * - MainScreen is ALWAYS rendered (video keeps playing)
 * - SettingsScreen slides in ON TOP when showSettings is true
 * - Back button closes SettingsScreen overlay
 * - Video is never stopped during navigation
 */
@Composable
fun CarlinkApp(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    displayMode: DisplayMode,
    onResetCluster: () -> Unit,
    onReinitForDisplayMode: (DisplayMode) -> Unit = {},
    onResetConnection: () -> Unit = {},
) {
    // Survives normal recomposition via remember{}, but discarded on manager rebuild
    // because CarlinkApp itself is gated by `if (manager != null)` at the Activity
    // setContent — tier-2 reinit disposes the whole composable, so every reinit
    // lands back in MainScreen regardless of prior overlay state. Intentional UX.
    var showSettings by remember { mutableStateOf(false) }

    // Log screen changes and recover video when overlay closes.
    // Unlike Activity onStop/onStart, the settings overlay doesn't trigger lifecycle
    // events. While the overlay covers the SurfaceView, SurfaceFlinger may stop
    // consuming frames, stalling the codec's BufferQueue. Flush codec on overlay close.
    LaunchedEffect(showSettings) {
        val screenName = if (showSettings) "SettingsScreen (overlay)" else "MainScreen (Projection)"
        logInfo("[UI_NAV] Active screen: $screenName", tag = "UI")
        if (!showSettings) {
            carlinkManager.recoverVideoFromOverlay()
        }
    }

    // Handle back button to close settings overlay
    BackHandler(enabled = showSettings) {
        logInfo("[UI_NAV] Back pressed: Closing SettingsScreen overlay", tag = "UI")
        showSettings = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // MainScreen is ALWAYS in composition - VideoSurface never gets disposed
        // This keeps the Surface alive and video playing uninterrupted
        MainScreen(
            carlinkManager = carlinkManager,
            displayMode = displayMode,
            onNavigateToSettings = {
                logInfo("[UI_NAV] Opening SettingsScreen overlay (video continues)", tag = "UI")
                showSettings = true
            },
            onResetConnection = onResetConnection,
        )

        // SettingsScreen slides in ON TOP of MainScreen
        // MainScreen remains visible underneath (just covered)
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            SettingsScreen(
                carlinkManager = carlinkManager,
                fileLogManager = fileLogManager,
                onNavigateBack = {
                    logInfo("[UI_NAV] Closing SettingsScreen overlay", tag = "UI")
                    showSettings = false
                },
                onResetCluster = onResetCluster,
                onReinitForDisplayMode = onReinitForDisplayMode,
            )
        }
    }
}
