package com.carlink.logging

import android.util.Log
import com.carlink.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized logging facade for the app.
 *
 * Responsibilities:
 *  - Uniform call surface (v/d/i/w/e plus top-level logDebug/logInfo/logWarn/logError).
 *  - Tag filtering (27 tags in [Tags]) with a default-ENABLED policy for unregistered tags
 *    — callers must use presets to disable tags they don't want.
 *  - Level filtering via [LogLevel] presets, with a derived [minLevel] fast-path.
 *  - [LogListener] fan-out for persistence. Today [FileLogManager] is the sole listener.
 *  - Debug-build-only mirroring to android.util.Log; release builds route exclusively
 *    through listeners to avoid per-packet Log.d() overhead on the GM AAOS Intel Atom.
 *  - [Tags.PROTO_UNKNOWN] bypass so protocol anomalies always reach persistence.
 *  - Inline [logDebugOnly] family with lambda message builders for zero-cost call sites
 *    when a tag is disabled.
 *
 * Threading: [log] dispatches to listeners SYNCHRONOUSLY on the caller's thread. That
 * is the load-bearing invariant [FileLogManager.onLog] is engineered around.
 */
object Logger {
    private const val TAG = "CARLINK"

    enum class Level(
        val priority: Int,
    ) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
    }

    /** LogPreset-compatible log levels. Intentionally omits VERBOSE — presets cannot
     *  filter VERBOSE and no call site uses [Logger.v] today; it exists as a logcat-only
     *  escape hatch. */
    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    object Tags {
        const val USB = "USB"
        const val VIDEO = "VIDEO"
        const val AUDIO = "AUDIO"
        const val MIC = "MIC"
        const val H264_RENDERER = "H264_RENDERER"
        const val PLATFORM = "PLATFORM"
        const val SERIALIZE = "SERIALIZE"
        const val COMMAND = "COMMAND"
        const val TOUCH = "TOUCH"
        const val CONFIG = "CONFIG"
        const val ADAPTR = "ADAPTR"
        const val PHONE = "PHONE"
        const val MEDIA = "MEDIA"
        const val MEDIA_SESSION = "MEDIA_SESSION"
        const val FILE_LOG = "FILE_LOG"
        const val USB_RAW = "USB_RAW"
        const val AUDIO_DEBUG = "AUDIO_DEBUG"
        const val ERROR_RECOVERY = "ERROR_RECOVERY"
        const val NAVI = "NAVI"
        const val CLUSTER = "CLUSTER"
        const val ICON_SHIM = "ICON_SHIM"

        /** Protocol unknowns — bypasses ALL tag/level filtering. Always reaches file logs. */
        const val PROTO_UNKNOWN = "PROTO_UNKNOWN"

        // Video Pipeline Debug Tags
        const val VIDEO_USB = "VIDEO_USB"
        const val VIDEO_RING_BUFFER = "VIDEO_RING"
        const val VIDEO_CODEC = "VIDEO_CODEC"
        const val VIDEO_SURFACE = "VIDEO_SURFACE"
        const val VIDEO_PERF = "VIDEO_PERF"
        const val AUDIO_PERF = "AUDIO_PERF"
    }

    @Volatile
    private var debugLoggingEnabled = true

    /** Set debug logging state. In release mode, disables verbose pipeline tags. */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        debugLoggingEnabled = enabled
        if (!enabled) {
            // In release mode, disable verbose video pipeline tags
            disableTag(Tags.VIDEO_USB)
            disableTag(Tags.VIDEO_RING_BUFFER)
            disableTag(Tags.VIDEO_CODEC)
            disableTag(Tags.VIDEO_SURFACE)
            disableTag(Tags.USB_RAW)
            disableTag(Tags.AUDIO_DEBUG)
            disableTag(Tags.AUDIO_PERF)
        }
    }

    fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled

    interface LogListener {
        fun onLog(
            level: Level,
            tag: String?,
            message: String,
            timestamp: Long,
        )
    }

    private val enabledTags = ConcurrentHashMap<String, Boolean>()
    private val listeners = CopyOnWriteArrayList<LogListener>()

    @Volatile
    private var minLevel = Level.DEBUG

    init {
        enableTag(Tags.USB)
        enableTag(Tags.VIDEO)
        enableTag(Tags.AUDIO)
        enableTag(Tags.ADAPTR)
        enableTag(Tags.PHONE)
        enableTag(Tags.MEDIA_SESSION)
        enableTag(Tags.ERROR_RECOVERY)
    }

    fun enableTag(tag: String) {
        enabledTags[tag] = true
    }

    fun disableTag(tag: String) {
        enabledTags[tag] = false
    }

    // Default-ENABLED: unregistered tags pass the filter. LogPreset.apply() always
    // explicitly configures the full set (via enableAllTags/disableAllTags followed by
    // setTagsEnabled), so the default only governs transient states before a preset
    // runs and one-off tags added without a preset update.
    fun isTagEnabled(tag: String): Boolean = enabledTags[tag] ?: true

    fun setMinLevel(level: Level) {
        minLevel = level
    }

    private val logLevelEnabled =
        ConcurrentHashMap<LogLevel, Boolean>().apply {
            LogLevel.entries.forEach { put(it, true) }
        }

    fun setLogLevel(
        level: LogLevel,
        enabled: Boolean,
    ) {
        logLevelEnabled[level] = enabled
        // Recalculate to fix release builds staying at ERROR after preset change
        recalculateMinLevel()
    }

    // Picks the lowest enabled level as minLevel — i.e., the cutoff passed at
    // log() line ~262. This ASSUMES contiguous preset masks (e.g., INFO+WARN+ERROR).
    // A non-contiguous mask (e.g., INFO=on, WARN=off, ERROR=on) would compute
    // minLevel=INFO and silently let WARN through. All 11 LogPresets use contiguous
    // ranges today; if that ever changes, upgrade the fast-path to check the per-level
    // map instead of just minLevel.priority.
    private fun recalculateMinLevel() {
        minLevel =
            when {
                logLevelEnabled[LogLevel.DEBUG] == true -> Level.DEBUG
                logLevelEnabled[LogLevel.INFO] == true -> Level.INFO
                logLevelEnabled[LogLevel.WARN] == true -> Level.WARN
                logLevelEnabled[LogLevel.ERROR] == true -> Level.ERROR
                else -> Level.ERROR
            }
    }

    fun isLogLevelEnabled(level: LogLevel): Boolean = logLevelEnabled[level] ?: true

    fun setTagsEnabled(
        tags: List<String>,
        enabled: Boolean,
    ) {
        tags.forEach { tag ->
            enabledTags[tag] = enabled
        }
    }

    fun disableAllTags() {
        enabledTags.keys.forEach { tag ->
            enabledTags[tag] = false
        }
    }

    fun enableAllTags() {
        enabledTags.keys.forEach { tag ->
            enabledTags[tag] = true
        }
        // Also enable known tags
        listOf(
            Tags.USB,
            Tags.VIDEO,
            Tags.AUDIO,
            Tags.MIC,
            Tags.H264_RENDERER,
            Tags.PLATFORM,
            Tags.SERIALIZE,
            Tags.COMMAND,
            Tags.TOUCH,
            Tags.CONFIG,
            Tags.ADAPTR,
            Tags.PHONE,
            Tags.MEDIA,
            Tags.MEDIA_SESSION,
            Tags.FILE_LOG,
            Tags.USB_RAW,
            Tags.AUDIO_DEBUG,
            Tags.ERROR_RECOVERY,
            Tags.NAVI,
            Tags.CLUSTER,
            Tags.ICON_SHIM,
            Tags.VIDEO_USB,
            Tags.VIDEO_RING_BUFFER,
            Tags.VIDEO_CODEC,
            Tags.VIDEO_SURFACE,
            Tags.VIDEO_PERF,
            Tags.AUDIO_PERF,
        ).forEach { tag ->
            enabledTags[tag] = true
        }
    }

    // Listeners are dispatched SYNCHRONOUSLY on the caller's thread inside log().
    // Re-entrancy hazard: a listener that calls back into Logger.d/i/w/e on every
    // onLog() will recurse until StackOverflow. Either queue-then-flush (as
    // FileLogManager does) or explicitly gate self-logging behind a reentry flag.
    fun addListener(listener: LogListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }

    fun v(
        message: String,
        tag: String? = null,
    ) = log(Level.VERBOSE, tag, message)

    fun d(
        message: String,
        tag: String? = null,
    ) = log(Level.DEBUG, tag, message)

    fun i(
        message: String,
        tag: String? = null,
    ) = log(Level.INFO, tag, message)

    fun w(
        message: String,
        tag: String? = null,
    ) = log(Level.WARN, tag, message)

    fun e(
        message: String,
        tag: String? = null,
        throwable: Throwable? = null,
    ) = log(Level.ERROR, tag, message, throwable)

    fun log(
        level: Level,
        tag: String?,
        message: String,
        throwable: Throwable? = null,
    ) {
        // Emit to system logcat only in debug builds (adb logcat capture during development).
        // Release builds route exclusively through listeners (app file logging) to avoid
        // per-packet Log.d() overhead on constrained hardware (GM AAOS Intel Atom).
        if (BuildConfig.DEBUG) {
            val formattedMessage = if (tag != null) "[$tag] $message" else message
            when (level) {
                Level.VERBOSE -> Log.v(TAG, formattedMessage, throwable)
                Level.DEBUG -> Log.d(TAG, formattedMessage, throwable)
                Level.INFO -> Log.i(TAG, formattedMessage, throwable)
                Level.WARN -> Log.w(TAG, formattedMessage, throwable)
                Level.ERROR -> Log.e(TAG, formattedMessage, throwable)
            }
        }

        // Filter before any further work — skip timestamp + listener dispatch if filtered out.
        // NEVER filter protocol-unknown messages — they must always reach file logs.
        val bypassFilter = tag == Tags.PROTO_UNKNOWN
        if (!bypassFilter) {
            if (level.priority < minLevel.priority) return
            if (tag != null && !isTagEnabled(tag)) return
        }

        if (listeners.isEmpty()) return

        val timestamp = System.currentTimeMillis()
        for (listener in listeners) {
            try {
                listener.onLog(level, tag, message, timestamp)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Error in log listener: ${e.message}")
            }
        }
    }
}

// Extension functions
fun logDebug(
    message: String,
    tag: String? = null,
) = Logger.d(message, tag)

fun logInfo(
    message: String,
    tag: String? = null,
) = Logger.i(message, tag)

fun logWarn(
    message: String,
    tag: String? = null,
) = Logger.w(message, tag)

fun logError(
    message: String,
    tag: String? = null,
    throwable: Throwable? = null,
) = Logger.e(message, tag, throwable)

// Legacy short-name alias for Logger.d(). Shadows kotlin.math.log in files that
// import both — prefer logDebug() for new code.
fun log(
    message: String,
    tag: String? = null,
) = Logger.d(message, tag)

fun isTagEnabled(tag: String): Boolean = Logger.isTagEnabled(tag)

// Zero-cost debug logging: the message lambda is only invoked if both
// debugLoggingEnabled AND the tag are enabled. debugLoggingEnabled defaults to true
// and is wired to BuildConfig.DEBUG by MainActivity.initializeLogging() at startup —
// so in release builds this compiles down to a no-op at every call site. Any other
// entry point that forgets to call setDebugLoggingEnabled(false) will pay full cost.
inline fun logDebugOnly(
    tag: String,
    message: () -> String,
) {
    if (Logger.isDebugLoggingEnabled() && Logger.isTagEnabled(tag)) {
        Logger.d(message(), tag)
    }
}

inline fun logVideoUsb(message: () -> String) = logDebugOnly(Logger.Tags.VIDEO_USB, message)

inline fun logVideoCodec(message: () -> String) = logDebugOnly(Logger.Tags.VIDEO_CODEC, message)

inline fun logVideoSurface(message: () -> String) = logDebugOnly(Logger.Tags.VIDEO_SURFACE, message)

inline fun logVideoPerf(message: () -> String) = logDebugOnly(Logger.Tags.VIDEO_PERF, message)

inline fun logNavi(message: () -> String) = logDebugOnly(Logger.Tags.NAVI, message)

inline fun logAudioPerf(message: () -> String) = logDebugOnly(Logger.Tags.AUDIO_PERF, message)
