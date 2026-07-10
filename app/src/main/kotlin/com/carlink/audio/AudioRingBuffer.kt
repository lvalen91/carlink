package com.carlink.audio

/**
 * Lock-free ring buffer for audio jitter compensation.
 *
 * PURPOSE:
 * Decouples producer from consumer to absorb irregular packet arrival.
 * USB capture analysis shows P99 jitter of ~7ms with max 30ms, so buffer
 * provides significant safety margin for consistent playback.
 *
 * THREAD SAFETY:
 * Designed as single-producer / single-consumer (SPSC). Used in two role
 * configurations:
 * - Playback (DualStreamAudioManager): producer = USB ingest, consumer = AudioTrack writer.
 * - Microphone (MicrophoneCaptureManager): producer = AudioRecord reader, consumer = USB streamer.
 * Synchronization is via volatile cursors only. See [write] for a documented
 * SPSC violation in the overflow path.
 *
 * SAMPLE ALIGNMENT:
 * When discarding oldest data during overflow, bytes are aligned to PCM frame
 * boundaries (channels * 2 bytes for 16-bit audio) to prevent audio corruption.
 * See: https://developer.android.com/reference/android/media/AudioFormat
 *
 * BUFFER SIZING:
 * - Media stream: 200-300ms recommended (absorbs adapter jitter)
 * - Navigation stream: 100-150ms recommended (lower latency for prompts)
 *
 * @param capacityMs Buffer capacity in milliseconds
 * @param sampleRate Audio sample rate (e.g., 48000). [bytesPerMs] is computed via
 *   integer division — exact for 48000/16000/8000 Hz, but loses sub-byte precision
 *   for 44100 Hz (~0.2% under-allocation). The app currently pins the adapter to
 *   48kHz at init, so this is not exercised today.
 * @param channels Number of audio channels (1=mono, 2=stereo)
 */
class AudioRingBuffer(
    private val capacityMs: Int,
    private val sampleRate: Int,
    private val channels: Int,
) {
    // PCM frame size: 2 bytes per sample (16-bit) * number of channels
    // Stereo 16-bit = 4 bytes per frame, Mono 16-bit = 2 bytes per frame
    private val bytesPerFrame = channels * 2
    private val bytesPerMs = (sampleRate * channels * 2) / 1000
    private val capacity = capacityMs * bytesPerMs
    private val buffer = ByteArray(capacity)

    @Volatile private var writePos = 0 // Only modified by writer thread

    @Volatile private var readPos = 0 // Modified by reader thread; also by writer during overflow (see write())

    @Volatile var totalBytesWritten: Long = 0
        private set

    @Volatile var totalBytesRead: Long = 0
        private set

    @Volatile var overflowCount: Int = 0
        private set

    @Volatile var underflowCount: Int = 0
        private set

    @Volatile var discardedBytes: Long = 0
        private set

    /**
     * Write data to ring buffer (non-blocking, overwrite-oldest when full).
     *
     * @return Bytes written. Equals [length] in the normal case (oldest data is
     *   discarded as needed to make room). If [length] exceeds [capacity] - 1,
     *   the buffer cannot hold the whole chunk even after a full discard, and the
     *   return value is capped at [capacity] - 1 (tail of the chunk is dropped).
     *   Realistic chunks (~30ms) are well under buffer capacity (≥100ms) so this
     *   edge does not occur in practice.
     */
    fun write(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Int {
        var available = availableForWrite()

        // Overwrite-oldest: discard oldest data when full (prevents buffer-full stalls)
        // Align discard to PCM frame boundary to prevent audio corruption
        // (channel phase shift, clicking/popping artifacts)
        // NOTE: Advancing readPos from the writer violates strict SPSC — if the reader
        // captured a stale localReadPos, its next update may undo this advance, causing
        // brief replay of discarded data (~1 packet). Acceptable for projection audio
        // in an already-degraded overflow state. Alternatives (lock, CAS, drop-newest)
        // add complexity or are wrong for live audio.
        if (available < length) {
            val minDiscard = length - available
            // Round up to nearest frame boundary for proper sample alignment
            val toDiscard = ((minDiscard + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame
            readPos = (readPos + toDiscard) % capacity
            discardedBytes += toDiscard
            overflowCount++
            available = availableForWrite()
        }

        val toWrite = minOf(length, available)
        val localWritePos = writePos

        val firstChunk = minOf(toWrite, capacity - localWritePos)
        System.arraycopy(data, offset, buffer, localWritePos, firstChunk)

        if (toWrite > firstChunk) {
            val secondChunk = toWrite - firstChunk
            System.arraycopy(data, offset + firstChunk, buffer, 0, secondChunk)
        }

        writePos = (localWritePos + toWrite) % capacity
        totalBytesWritten += toWrite

        return toWrite
    }

    /**
     * Read data from ring buffer (non-blocking).
     * @return Bytes read (may be less than length if buffer empty)
     */
    fun read(
        out: ByteArray,
        offset: Int = 0,
        length: Int = out.size - offset,
    ): Int {
        val available = availableForRead()
        if (available == 0) {
            underflowCount++
            return 0
        }

        val toRead = minOf(length, available)
        val localReadPos = readPos

        val firstChunk = minOf(toRead, capacity - localReadPos)
        System.arraycopy(buffer, localReadPos, out, offset, firstChunk)

        if (toRead > firstChunk) {
            val secondChunk = toRead - firstChunk
            System.arraycopy(buffer, 0, out, offset + firstChunk, secondChunk)
        }

        readPos = (localReadPos + toRead) % capacity
        totalBytesRead += toRead
        return toRead
    }

    fun availableForRead(): Int {
        val w = writePos
        val r = readPos
        return if (w >= r) w - r else capacity - r + w
    }

    fun availableForWrite(): Int = capacity - availableForRead() - 1 // -1 distinguishes full from empty

    fun fillLevel(): Float = availableForRead().toFloat() / capacity

    // bytesPerMs > 0 guard is defensive — unreachable for any realistic audio
    // config (would require sampleRate * channels * 2 < 1000).
    fun fillLevelMs(): Int = if (bytesPerMs > 0) availableForRead() / bytesPerMs else 0

    /**
     * Reset both cursors to 0.
     *
     * Best-effort: writes to [writePos] and [readPos] are not atomic relative to
     * a concurrent [write] or [read], so a concurrent caller may briefly observe
     * a stale [availableForRead] before stabilizing. Callers in
     * DualStreamAudioManager invoke this from active paths (slot teardown,
     * navigation re-plan) accepting that brief inconsistency rather than fully
     * stopping the consumer thread.
     */
    fun clear() {
        writePos = 0
        readPos = 0
    }

    fun getStats(): Map<String, Any> =
        mapOf(
            "capacityMs" to capacityMs,
            "capacityBytes" to capacity,
            "fillLevelMs" to fillLevelMs(),
            "fillPercent" to (fillLevel() * 100).toInt(),
            "totalBytesWritten" to totalBytesWritten,
            "totalBytesRead" to totalBytesRead,
            "overflowCount" to overflowCount,
            "underflowCount" to underflowCount,
            "discardedBytes" to discardedBytes,
            "sampleRate" to sampleRate,
            "channels" to channels,
        )
}
