package com.carlink.logging

import android.util.Log
import com.carlink.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized logging facade for the CarPlay-only (cp-stripped) build.
 *
 * - Uniform call surface (v/d/i/w/e plus the top-level logDebug/logInfo/logWarn/logError).
 * - logcat policy: DEBUG builds emit everything; RELEASE builds emit WARN/ERROR only, plus
 *   [Tags.PROTO_UNKNOWN] which always passes (protocol anomalies must reach logcat for
 *   post-mortem forensics). The log-to-file feature was removed in this variant — there is
 *   no listener fan-out and no preset/level UI.
 * - Zero-cost [logDebugOnly] family: the message lambda runs only when debug logging is
 *   enabled AND the tag is enabled, so release builds compile verbose call sites to no-ops.
 *
 * `setDebugLoggingEnabled(false)` (set at startup in release builds) disables the verbose
 * video/audio pipeline tags so their [logDebugOnly] call sites become no-ops.
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

        /** Protocol unknowns — always emitted, even in release builds. */
        const val PROTO_UNKNOWN = "PROTO_UNKNOWN"

        // Video / audio pipeline debug tags (gated off in release via setDebugLoggingEnabled).
        const val VIDEO_USB = "VIDEO_USB"
        const val VIDEO_RING_BUFFER = "VIDEO_RING"
        const val VIDEO_CODEC = "VIDEO_CODEC"
        const val VIDEO_SURFACE = "VIDEO_SURFACE"
        const val VIDEO_PERF = "VIDEO_PERF"
        const val AUDIO_PERF = "AUDIO_PERF"
    }

    @Volatile
    private var debugLoggingEnabled = true

    private val enabledTags = ConcurrentHashMap<String, Boolean>()

    init {
        enableTag(Tags.USB)
        enableTag(Tags.VIDEO)
        enableTag(Tags.AUDIO)
        enableTag(Tags.ADAPTR)
        enableTag(Tags.PHONE)
        enableTag(Tags.MEDIA_SESSION)
        enableTag(Tags.ERROR_RECOVERY)
    }

    /** Enable/disable debug logging. When disabled (release builds), the verbose pipeline tags
     *  are turned off so their [logDebugOnly] call sites become no-ops. */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        debugLoggingEnabled = enabled
        if (!enabled) {
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

    fun enableTag(tag: String) {
        enabledTags[tag] = true
    }

    fun disableTag(tag: String) {
        enabledTags[tag] = false
    }

    // Default-ENABLED: unregistered tags pass. Verbose tags are disabled by
    // setDebugLoggingEnabled(false) in release builds.
    fun isTagEnabled(tag: String): Boolean = enabledTags[tag] ?: true

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
        // DEBUG builds: emit everything. RELEASE builds: WARN/ERROR only, plus PROTO_UNKNOWN.
        val emit =
            BuildConfig.DEBUG || level.priority >= Level.WARN.priority || tag == Tags.PROTO_UNKNOWN
        if (!emit) return

        val formattedMessage = if (tag != null) "[$tag] $message" else message
        when (level) {
            Level.VERBOSE -> Log.v(TAG, formattedMessage, throwable)
            Level.DEBUG -> Log.d(TAG, formattedMessage, throwable)
            Level.INFO -> Log.i(TAG, formattedMessage, throwable)
            Level.WARN -> Log.w(TAG, formattedMessage, throwable)
            Level.ERROR -> Log.e(TAG, formattedMessage, throwable)
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

// Zero-cost debug logging: the message lambda is only invoked if both debugLoggingEnabled
// AND the tag are enabled. In release builds debugLoggingEnabled is false (set by
// MainActivity.initializeLogging), so every call site compiles down to a no-op.
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
