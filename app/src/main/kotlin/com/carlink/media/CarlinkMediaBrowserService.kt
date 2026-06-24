package com.carlink.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.carlink.BuildConfig
import com.carlink.MainActivity
import com.carlink.R
import com.carlink.logging.logInfo
import com.carlink.util.LogCallback

private const val TAG = "CARLINK_BROWSER"

/**
 * Carlink media service bridging the app to AAOS Media Center / steering-wheel controls.
 *
 * PURPOSE
 * - Registers the Carlink app as a selectable media source on Android Automotive OS
 *   (AAOS Media Center discovers us via the `androidx.media3.session.MediaLibraryService`
 *   + legacy `android.media.browse.MediaBrowserService` intent filters).
 * - Maintains a foreground service with an ongoing notification so the system does not
 *   reclaim the process while the USB adapter is connected (preserves USB priority,
 *   heartbeat timers, and streaming).
 *
 * MEDIA3 MIGRATION NOTES (from androidx.media:media:1.7.1 → androidx.media3:media3-session:1.10.0)
 * - Base class: [MediaLibraryService] (replaces legacy MediaBrowserServiceCompat).
 * - Browse-tree callbacks (onGetRoot / onLoadChildren) have moved to
 *   [MediaLibrarySession.Callback] inside [MediaSessionManager]. This service only
 *   supplies the live session via [onGetSession].
 * - `updateSessionToken` is gone — Media3's [onGetSession] returns the session directly.
 * - Legacy `android.media.session.MediaSession` observers (e.g. GM's GMCarMediaService
 *   on 2024 Silverado ICE firmware, verified via the mempalace gminfo37/architecture
 *   drawer on GMCarMediaService) continue to work: Media3 auto-registers a platform
 *   session under the hood for backwards compatibility.
 *
 * FOREGROUND SERVICE — HYBRID WITH MEDIA3 AUTO-FGS
 * Media3 [MediaLibraryService] automatically enters foreground while the Player is in
 * STATE_READY with `playWhenReady=true` (active playback). BUT carlink_native's
 * adapter lifecycle has an important phase that Media3 does NOT cover:
 *   CONNECTING — USB adapter handshake in progress, Player is STATE_BUFFERING,
 *                `playWhenReady=false`. Media3 would NOT foreground during this phase,
 *                but the USB subsystem needs elevated priority so the adapter doesn't
 *                get killed mid-handshake.
 * So we KEEP the manual foreground machinery (from the pre-migration design) for the
 * CONNECTING window. When Media3 additionally enters FGS during real playback both
 * mechanisms call `startForeground` with compatible type masks; the second call is a
 * no-op, and either one exiting calls `stopForeground` which is also idempotent.
 *
 * The single-notification invariant (one foreground notification, not two) depends on
 * both paths using the same notification id. Confirmed: Media3's
 * DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID = 1001 (public constant,
 * media3-session 1.10.0). Our NOTIFICATION_ID=1001 reconciles both paths. Verified
 * AAOS emulator v120 2026-04-20: exactly one notification tuple
 * `10|zeno.carlink|1001|null|1010252` across a 3-min STREAMING session — Media3 auto-FGS
 * did not post a second notification. If Media3 ever changes its default, realign.
 *
 * THREAD SAFETY — foregroundLock
 * [startForegroundMode] runs on the main thread via [onStartCommand]; [stopForegroundMode]
 * can be called from the USB read thread via [stopConnectionForeground] → instance. The
 * two methods are mutually-exclusive via [foregroundLock]; without the lock, a stop
 * landing between `ServiceCompat.startForeground` and `isForeground = true` would silently
 * skip, leaving the service foreground-stuck with the flag stale.
 *
 * MediaStyle in the notification
 * The manual notification is deliberately plain (no [androidx.media.app.NotificationCompat.MediaStyle])
 * — Media3's auto-notification provider delivers the MediaStyle-rich notification when
 * real playback is active. During CONNECTING our plain notification conveys "adapter
 * connected, waiting for stream" without the confusion of a MediaStyle card that has no
 * track loaded yet.
 */
class CarlinkMediaBrowserService : MediaLibraryService() {

    /**
     * Foreground-mode guard. See class-level KDoc for the race it prevents.
     */
    private var isForeground = false
    private val foregroundLock = Any()

    /**
     * Initialize the [MediaSessionManager] singleton synchronously here so AAOS
     * `onGetSession` probes (which arrive within ~50 ms of `onCreate` at boot) find a
     * live session token. Owning the lifecycle in the MBS — rather than in MainActivity
     * — closes the boot-race window where AAOS auto-launches the MBS without ever
     * launching MainActivity, so the older Activity-scoped initialize() never ran and
     * the latch-based wait timed out returning `null` (a permanent controller rejection
     * per Media3 docs).
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onCreate")
        MediaSessionManager.getOrCreate(applicationContext, serviceLogCallback).initialize()
    }

    /**
     * Return the live [MediaLibrarySession] for the requesting controller. Guaranteed
     * non-null after [onCreate] returns (the OS only dispatches binder calls — including
     * `onGetSession` — after `Service.onCreate` completes).
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[BROWSER_SERVICE] onGetSession from ${controllerInfo.packageName} uid=${controllerInfo.uid}")
        }
        return MediaSessionManager.getMediaLibrarySession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_FOREGROUND) startForegroundMode()
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Override of [MediaLibraryService.onTaskRemoved] (inherited from [MediaSessionService]).
     *
     * Rationale based on observations of media3-session 1.10.0 source on 2026-05-03 — these
     * are point-in-time observations, not contract guarantees. Re-verify against the current
     * Media3 release before relying on this for refactoring decisions.
     *
     * Observed default implementation (around MediaSessionService.java:737-743 as of the
     * 2026-05-03 read) calls `pauseAllPlayersAndStopSelf()` whenever
     * `!isAnySessionPlaying()`. The observed `isAnySessionPlaying()` (around line 912-920)
     * iterates sessions and checks `Player.isPlaying()`. Per Player javadoc as of 1.10.0,
     * `isPlaying()` requires `STATE_READY + playWhenReady` simultaneously — which appears
     * not to be reachable for [UsbAdapterPlayer] during the CONNECTING phase (Player is
     * `STATE_BUFFERING + playWhenReady=true`, so isPlaying() returned false in observed
     * runs). If a future Media3 release changes the predicate (e.g., admits
     * STATE_BUFFERING), this override's premise weakens and should be reconsidered.
     *
     * Observed `pauseAllPlayersAndStopSelf()` then calls `setPlayWhenReady(false)` on every
     * session player before `stopSelf()`. For [UsbAdapterPlayer] that routes through
     * [UsbAdapterPlayer.handleSetPlayWhenReady] → [UsbAdapterPlayer.Callback.onPause] →
     * [MediaSessionManager.MediaControlCallback.onPause] →
     * `CarlinkManager.sendKey(CommandMapping.PAUSE)` — which would emit a SPURIOUS USB
     * PAUSE command to the connected phone even though the user only swiped the launcher.
     * The override below avoids this cascade.
     *
     * Override: when our manual FGS is held (CONNECTING/STREAMING), call `stopSelf()`
     * directly — observed doc warning around MediaSessionService.java:723-725 (2026-05-03)
     * stating that when playback is not ongoing, the service must be terminated or the
     * system will crash and restart it. `stopSelf()` satisfies that without invoking the
     * player-side pause cascade. When the manual FGS is not held, defer to the default.
     *
     * Line numbers above are point-in-time references; expect drift across Media3 releases
     * and grep the symbol names rather than the line numbers when re-verifying.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isForeground) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "[BROWSER_SERVICE] onTaskRemoved during FGS — stopSelf without pause cascade")
            }
            stopSelf()
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onDestroy")
        // Release the MediaSession singleton here — this is the one and only
        // shutdown path for the session now that MBS owns its lifecycle. MainActivity
        // is no longer responsible.
        MediaSessionManager.releaseInstance()
        instance = null
        stopForegroundMode()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Notification channel + build
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Connection Status",
            NotificationManager.IMPORTANCE_LOW, // silent; adapter-connected indicator
        ).apply {
            description = "Shows when Carlink adapter is connected"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Notification channel created")
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(currentTitle ?: "Carlink")
            .setContentText(currentArtist ?: "Adapter connected")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Use Media3's auto-FGS notification group key so that, during any brief overlap
            // between our manual FGS notification (CONNECTING) and Media3's
            // DefaultMediaNotificationProvider notification (active playback), both notifications
            // — which already share NOTIFICATION_ID per `notify()` replace-by-id semantics — also
            // share a group, eliminating cross-path UI churn. Verified group key value at
            // DefaultMediaNotificationProvider.java:250 in 1.10.0.
            .setGroup(DefaultMediaNotificationProvider.GROUP_KEY)
            .setContentIntent(contentIntent)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Foreground mode (manual — covers CONNECTING, pre-playback)
    // ──────────────────────────────────────────────────────────────────────

    private fun startForegroundMode() {
        synchronized(foregroundLock) {
            if (isForeground) return
            try {
                val notification = buildNotification()
                // Type mask is a CONTRACT with AndroidManifest.xml's foregroundServiceType —
                // narrowing the manifest to just one type (e.g., dropping connectedDevice)
                // while leaving this OR intact throws SecurityException at runtime.
                //   MEDIA_PLAYBACK     — requires FOREGROUND_SERVICE_MEDIA_PLAYBACK perm
                //   CONNECTED_DEVICE   — requires FOREGROUND_SERVICE_CONNECTED_DEVICE perm
                //                        plus one of BLUETOOTH/NFC/USB/NETWORK_STATE/...
                //                        (CHANGE_NETWORK_STATE + usb.host feature satisfy)
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                isForeground = true
                if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Entered foreground mode")
            } catch (e: Exception) {
                Log.e(TAG, "[BROWSER_SERVICE] Failed to start foreground: ${e.message}")
            }
        }
    }

    private fun stopForegroundMode() {
        synchronized(foregroundLock) {
            if (!isForeground) return
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
                if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Exited foreground mode")
            } catch (e: Exception) {
                Log.e(TAG, "[BROWSER_SERVICE] Failed to stop foreground: ${e.message}")
            }
        }
    }

    private fun refreshNotification() {
        if (!isForeground) return
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "[BROWSER_SERVICE] Failed to refresh notification: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Companion — static API for CarlinkManager (unchanged from legacy path)
    // ──────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * LogCallback used by the [MediaSessionManager] singleton when it's bootstrapped
         * from the MBS at boot — i.e. before MainActivity has had a chance to install its
         * own callback. Routes through the same top-level [logInfo] used elsewhere in the
         * app so MediaSession events appear in both logcat and FileLogManager exports.
         */
        private val serviceLogCallback = object : LogCallback {
            override fun log(message: String) = logInfo(message, tag = "MEDIA_SESSION")
            override fun log(tag: String, message: String) = logInfo(message, tag = tag)
        }

        private const val NOTIFICATION_CHANNEL_ID = "carlink_connection"

        /**
         * Foreground-service notification id. MUST equal Media3's
         * `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID` so the manual FGS
         * notification (CONNECTING phase) and Media3's auto-FGS notification (active
         * playback) collapse into a single notification slot. See class-level KDoc
         * for full context. Verified by `MediaNotificationIdTest`.
         */
        @VisibleForTesting
        internal const val NOTIFICATION_ID = 1001
        private const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"

        @Volatile private var instance: CarlinkMediaBrowserService? = null
        @Volatile private var currentTitle: String? = null
        @Volatile private var currentArtist: String? = null

        /**
         * Update notification content with current now-playing metadata. Called from
         * CarlinkManager when MediaSessionManager publishes new metadata.
         *
         * Invariant: callers must serialize `updateNowPlaying` calls. The two field
         * writes are individually @Volatile but not atomic as a pair — simultaneous
         * calls from two threads could interleave title/artist from different tracks.
         * CarlinkManager currently drives this from a single producer (USB read thread
         * via processMediaMetadata), so the invariant holds.
         */
        fun updateNowPlaying(title: String?, artist: String?) {
            if (title == currentTitle && artist == currentArtist) return
            currentTitle = title
            currentArtist = artist
            instance?.refreshNotification()
        }

        /**
         * Clear now-playing metadata (e.g., on adapter disconnect).
         */
        fun clearNowPlaying() {
            currentTitle = null
            currentArtist = null
            instance?.refreshNotification()
        }

        /**
         * Start foreground mode for the CONNECTING / STREAMING phases. Idempotent —
         * see class-level KDoc for interaction with Media3's auto-FGS.
         */
        fun startConnectionForeground(context: Context) {
            val intent = Intent(context, CarlinkMediaBrowserService::class.java).apply {
                action = ACTION_START_FOREGROUND
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop foreground mode. Called from CarlinkManager on DISCONNECTED state. The
         * [context] parameter is unused (the running instance is tracked via the
         * [instance] companion field) but retained so call sites don't need to change.
         */
        @Suppress("UNUSED_PARAMETER")
        fun stopConnectionForeground(context: Context) {
            instance?.stopForegroundMode()
        }
    }
}
