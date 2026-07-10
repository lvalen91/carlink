package com.carlink.ipc

import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo

/**
 * Forwards CPC200-CCPA AltVideo (USB MsgType 0x2C) frames to bound consumer sinks.
 *
 * UsbDeviceWrapper hands us the raw 0x2C payload (the 20-byte video header
 * followed by H.264 Annex-B NAL units). We parse the header, strip it, and
 * broadcast the Annex-B bytes via {@link INaviVideoSink}.
 *
 * Single instance lives in {@link NaviVideoSingleton}. Decoding is delegated to
 * the consumer (ClusterHomeDisplay priv-app); we never touch MediaCodec.
 *
 * Thread-safety:
 *  - onUsbFrame runs on the USB read thread (priority URGENT_DISPLAY - 2).
 *  - register/unregister run on the binder thread pool.
 *  - State mutations are guarded by [lock]; RemoteCallbackList handles its own.
 */
class NaviVideoForwarder {
    private val sinks = RemoteCallbackList<INaviVideoSink>()
    private val lock = Any()

    private var streamActive = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastFps = DEFAULT_FPS
    private var configuredFps = DEFAULT_FPS

    // Debug-only per-frame counter + 1Hz throttle for the per-frame proof-of-life log.
    // Compiled away in release builds because the BuildConfig.DEBUG branch is constant-folded.
    private var frameCount = 0L
    private var lastFrameLogMs = 0L

    /** Set by CarlinkManager from the same value sent in naviScreenInfo BoxSettings. */
    fun setRequestedFps(fps: Int) {
        configuredFps = fps
    }

    /**
     * Called per 0x2C USB packet. [data] is the pre-allocated USB read buffer
     * starting at the 20-byte video header (i.e. the 16-byte USB header is
     * already stripped by UsbDeviceWrapper).
     *
     * The Annex-B payload is COPIED out before broadcast so the USB buffer can
     * be reused immediately for the next read — AIDL oneway dispatch is
     * asynchronous and the bytes must outlive this call.
     */
    fun onUsbFrame(data: ByteArray, length: Int) {
        if (length < VIDEO_HEADER_BYTES) return

        val width = readInt32LE(data, 0x00)
        val height = readInt32LE(data, 0x04)
        // EncoderState at +0x08 is always 1 for nav video; ignored.
        val ptsMs = readInt32LE(data, 0x0C).toLong() and 0xFFFFFFFFL
        val ptsUs = ptsMs * 1000L
        // flags at +0x10 is observationally always 0; ignored.

        val payloadOffset = VIDEO_HEADER_BYTES
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) return

        val payload = ByteArray(payloadLen)
        System.arraycopy(data, payloadOffset, payload, 0, payloadLen)

        val isKey = annexBContainsIdr(payload)

        var configChanged = false
        synchronized(lock) {
            if (!streamActive || width != lastWidth || height != lastHeight) {
                lastWidth = width
                lastHeight = height
                lastFps = configuredFps
                streamActive = true
                configChanged = true
                logInfo(
                    "[NAVI_FWD] Stream configured: ${width}x$height@$lastFps " +
                        "(payload=${payloadLen}B, key=$isKey)",
                    tag = Logger.Tags.VIDEO,
                )
            }
        }

        if (configChanged) {
            broadcast { it.onStreamConfigured(width, height, lastFps) }
        }
        broadcast { it.onFrame(payload, ptsUs, isKey) }

        if (BuildConfig.DEBUG) {
            frameCount++
            // Dump NAL types on every key frame so we can prove SPS (7) + PPS (8)
            // + IDR (5) are forwarded together as a single access unit. The consumer
            // MediaCodec needs all three in the same buffer to initialize.
            if (isKey) {
                val nalTypes = annexBScanNalTypes(payload)
                logInfo(
                    "[NAVI_FWD] keyframe frame=$frameCount payload=${payloadLen}B " +
                        "nalTypes=$nalTypes (7=SPS 8=PPS 5=IDR 6=SEI 1=non-IDR)",
                    tag = Logger.Tags.VIDEO,
                )
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastFrameLogMs >= FRAME_LOG_INTERVAL_MS) {
                lastFrameLogMs = nowMs
                logInfo(
                    "[NAVI_FWD] frame=$frameCount payload=${payloadLen}B key=$isKey ${width}x$height pts=${ptsUs}us",
                    tag = Logger.Tags.VIDEO,
                )
            }
        }
    }

    /** Called on session end (cmd 509 release, CarPlay disconnect, USB unplug). */
    fun onStreamStop() {
        synchronized(lock) {
            if (!streamActive) return
            streamActive = false
            logInfo("[NAVI_FWD] Stream ended", tag = Logger.Tags.VIDEO)
        }
        broadcast { it.onStreamEnded() }
    }

    fun isStreamActive(): Boolean = synchronized(lock) { streamActive }

    fun registerSink(sink: INaviVideoSink) {
        sinks.register(sink)
        logDebug("[NAVI_FWD] Sink registered", tag = Logger.Tags.VIDEO)
        // Replay current geometry to a late-binding consumer so it can show
        // its overlay immediately instead of waiting for a resolution change.
        synchronized(lock) {
            if (streamActive) {
                try {
                    sink.onStreamConfigured(lastWidth, lastHeight, lastFps)
                } catch (_: RemoteException) {
                    // Consumer process died between register() and replay; linkToDeath
                    // will clean it up. Nothing to do here.
                }
            }
        }
    }

    fun unregisterSink(sink: INaviVideoSink) {
        sinks.unregister(sink)
        logDebug("[NAVI_FWD] Sink unregistered", tag = Logger.Tags.VIDEO)
    }

    private inline fun broadcast(action: (INaviVideoSink) -> Unit) {
        val n = sinks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    action(sinks.getBroadcastItem(i))
                } catch (_: RemoteException) {
                    // Consumer died mid-broadcast; RemoteCallbackList drops it via linkToDeath.
                }
            }
        } finally {
            sinks.finishBroadcast()
        }
    }

    private fun readInt32LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * Scan Annex-B start codes for an IDR (NAL unit type 5).
     *
     * SPS (7) and PPS (8) typically precede an IDR but are not by themselves
     * a key frame. The consumer's MediaCodec drops everything until the first
     * key frame, so misclassifying P-frames as key would only cost early
     * decode attempts (harmless) — but misclassifying an IDR as P stalls the
     * decoder. We intentionally lean toward true detection: any 0x05 NAL is
     * a key.
     */
    private fun annexBContainsIdr(buf: ByteArray): Boolean {
        var i = 0
        val end = buf.size - 4
        while (i < end) {
            if (buf[i].toInt() == 0 &&
                buf[i + 1].toInt() == 0 &&
                buf[i + 2].toInt() == 0 &&
                buf[i + 3].toInt() == 1
            ) {
                val nalType = buf[i + 4].toInt() and 0x1F
                if (nalType == NAL_TYPE_IDR) return true
                i += 4
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Walk Annex-B start codes and return the sequence of NAL unit types found.
     * Used for debug-only logging on key frames to confirm SPS (7) + PPS (8) +
     * IDR (5) are all present in the same access unit so the consumer's
     * MediaCodec can initialize.
     */
    private fun annexBScanNalTypes(buf: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        val end = buf.size - 4
        while (i < end) {
            val is4ByteStart = buf[i].toInt() == 0 &&
                buf[i + 1].toInt() == 0 &&
                buf[i + 2].toInt() == 0 &&
                buf[i + 3].toInt() == 1
            val is3ByteStart = !is4ByteStart &&
                buf[i].toInt() == 0 &&
                buf[i + 1].toInt() == 0 &&
                buf[i + 2].toInt() == 1
            when {
                is4ByteStart -> {
                    types.add(buf[i + 4].toInt() and 0x1F)
                    i += 4
                }
                is3ByteStart -> {
                    types.add(buf[i + 3].toInt() and 0x1F)
                    i += 3
                }
                else -> i++
            }
        }
        return types
    }

    companion object {
        private const val VIDEO_HEADER_BYTES = 20
        private const val NAL_TYPE_IDR = 5
        private const val DEFAULT_FPS = 30
        private const val FRAME_LOG_INTERVAL_MS = 1000L
    }
}

/**
 * Holds the single process-wide [NaviVideoForwarder]. UsbDeviceWrapper writes
 * into it; NaviVideoSourceService reads from it. Stays alive for the life of
 * the process — there is no teardown path because the forwarder owns no
 * resources (RemoteCallbackList cleans itself via linkToDeath).
 *
 * [enabled] is the runtime gate that's set ONCE at CarlinkManager startup
 * based on `BuildConfig.DEBUG && PlatformDetector.isAaosEmulator()`. When
 * false, UsbDeviceWrapper drops 0x2C frames (defense in depth — the adapter
 * shouldn't be emitting them anyway, because MessageSerializer also gates the
 * `naviScreenInfo` BoxSettings JSON on the same condition) and MessageSerializer
 * skips the `naviScreenInfo` field. Production APKs and real-device debug APKs
 * see identical behavior to before this feature: zero new work, zero overhead.
 */
object NaviVideoSingleton {
    val forwarder: NaviVideoForwarder = NaviVideoForwarder()

    /** Single source of truth for the emulator+debug gate. Read by UsbDeviceWrapper + MessageSerializer. */
    @Volatile
    var enabled: Boolean = false
}
