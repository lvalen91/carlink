package com.carlink.platform

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

/**
 * Clamps the video size requested from the adapter to one the local H.264 decoder will accept.
 *
 * The adapter encodes whatever frame size the OPEN message asks for, and that is normally the
 * head unit's usable display area. On some devices the decoder cannot configure that size even
 * though [android.media.MediaCodecInfo.VideoCapabilities] claims it can, and the result is a
 * permanently black screen: USB video keeps arriving, `MediaCodec.start()` throws, and the
 * renderer restarts forever.
 *
 * Observed on a Samsung Galaxy Tab A9+ (SM-X210, Snapdragon SM6375 "holi", Android 15): the
 * panel is 1920x1200 and `media_codecs.xml` advertises up to 4096x2176 for c2.qti.avc.decoder,
 * but the kernel refuses anything above 8160 macroblocks:
 *
 *     msm_vidc h264d: Unsupported mbpf 8165, max 8160
 *     QC2V4l2Decoder: start: Failed to set resolution (1840x1136) input port
 *
 * 8160 macroblocks is 1920x1088, so a native 1920x1200 stream (9000 MBs) never starts. Because
 * the advertised capabilities are wrong, the only reliable test is to configure a throwaway
 * decoder and see whether it starts. This runs once per size and costs a few milliseconds; a
 * device whose decoder handles the display size natively probes once and keeps its own size.
 */
object DecoderCompat {
    private const val TAG = "DecoderCompat"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /** Give up rather than shrink forever if a device rejects everything. */
    private const val MAX_STEPS = 24

    /** Never propose a stream smaller than this. */
    private const val MIN_DIMENSION = 320

    private val cache = HashMap<Long, Pair<Int, Int>>()

    /**
     * Returns [width] x [height] when the decoder can start at that size, otherwise the largest
     * 16-aligned size with the same aspect ratio that it will start. Results are memoized.
     */
    @Synchronized
    fun adjust(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val key = (width.toLong() shl 32) or height.toLong()
        cache[key]?.let { return it }

        var w = width
        var h = height
        var steps = 0
        while (steps < MAX_STEPS && w >= MIN_DIMENSION && h >= MIN_DIMENSION) {
            if (canStart(w, h)) {
                if (w != width || h != height) {
                    Log.w(TAG, "Decoder rejected ${width}x$height; using ${w}x$h instead")
                }
                val result = w to h
                cache[key] = result
                return result
            }
            // Shrink the longer side one macroblock and re-derive the other from the source
            // aspect ratio, keeping both 16-aligned so the decoder is not asked to pad.
            if (w >= h) {
                w -= 16
                h = ((w.toLong() * height / width).toInt() / 16) * 16
            } else {
                h -= 16
                w = ((h.toLong() * width / height).toInt() / 16) * 16
            }
            steps++
        }

        Log.w(TAG, "No decodable size found below ${width}x$height; leaving it unchanged")
        val result = width to height
        cache[key] = result
        return result
    }

    /**
     * True when a decoder configures *and* starts at this size. Some drivers accept the format
     * and only fail in `start()`, so both calls have to be exercised. No Surface is bound; the
     * frame-size check happens before any output buffers are needed.
     */
    private fun canStart(
        width: Int,
        height: Int,
    ): Boolean {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createDecoderByType(MIME)
            codec.configure(MediaFormat.createVideoFormat(MIME, width, height), null, null, 0)
            codec.start()
            true
        } catch (t: Throwable) {
            Log.d(TAG, "${width}x$height rejected: ${t.javaClass.simpleName}")
            false
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
                // Already in an error state; nothing to recover.
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
                // Best effort.
            }
        }
    }
}
