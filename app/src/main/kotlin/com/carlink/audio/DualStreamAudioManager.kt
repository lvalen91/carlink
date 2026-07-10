package com.carlink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.carlink.platform.AudioConfig
import com.carlink.protocol.AudioRoutingState
import com.carlink.protocol.StreamPurpose
import com.carlink.util.LogCallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio stream type identifiers from CPC200-CCPA protocol.
 */
object AudioStreamType {
    const val MEDIA = 1 // Music, podcasts, Siri, phone, alerts (all non-nav)
    const val NAVIGATION = 2 // Turn-by-turn directions
    // Note: Protocol defines PHONE_CALL=3 and SIRI=4, but adapter always sends type=1.
    // Routing is handled via AudioRoutingState flags + format matching instead.
}

/**
 * DualStreamAudioManager - Pre-allocated per-purpose audio streams.
 *
 * PURPOSE:
 * Provides stable, uninterrupted audio playback for CarPlay/Android Auto projection
 * using dedicated pre-allocated AudioTracks per stream purpose (Media, Siri, PhoneCall,
 * Alert, Navigation), with ring buffers to absorb USB packet jitter.
 *
 * ARCHITECTURE:
 * Ring-buffer capacities are platform-dependent ([AudioConfig.mediaBufferCapacityMs] /
 * [AudioConfig.navBufferCapacityMs]); the values shown are typical defaults.
 * ```
 * USB Thread (non-blocking)
 *     │
 *     ├──► Media Ring Buffer (~500ms) ──► Media AudioTrack (USAGE_MEDIA)
 *     ├──► Siri Ring Buffer  (~500ms) ──► Siri AudioTrack (USAGE_ASSISTANT)
 *     ├──► Call Ring Buffer  (~500ms) ──► Call AudioTrack (USAGE_VOICE_COMMUNICATION)
 *     ├──► Alert Ring Buffer (~500ms) ──► Alert AudioTrack (USAGE_VOICE_COMMUNICATION_SIGNALLING)
 *     └──► Nav Ring Buffer   (~200ms) ──► Nav AudioTrack (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
 *     │
 *     └──► Playback Thread (THREAD_PRIORITY_URGENT_AUDIO)
 *             reads from all buffers, writes to AudioTracks
 * ```
 * ALERT uses USAGE_VOICE_COMMUNICATION_SIGNALLING (not USAGE_NOTIFICATION_RINGTONE) as
 * a deliberate workaround for GM AAOS bus routing — see [purposeToAttributes].
 *
 * ROUTING:
 * PCM packets are routed by two-factor matching: audio command state flags + format match.
 * This prevents transition artifacts where media PCM briefly routes to the wrong AudioTrack
 * when purpose changes (e.g., SIRI_START arrives but adapter still sends media PCM).
 * - audioType=2 → Navigation
 * - audioType=1 + isPhoneCallActive + mono @ 8kHz (AA HFP/SCO) or 16kHz (CarPlay iAP2) → PhoneCall track
 * - audioType=1 + isSiriActive + 16kHz mono → Siri track
 * - audioType=1 + isAlertActive → Alert track (format-agnostic; ensureSlotFormat handles
 *     48kHz/2ch CarPlay or 24kHz/1ch AA)
 * - audioType=1 + anything else → Media track (default, catches transition-period packets)
 *
 * THREAD SAFETY:
 * - writeAudio() called from USB thread (non-blocking)
 * - Playback thread handles AudioTrack writes
 * - Volume/ducking can be called from any thread
 */
class DualStreamAudioManager(
    private val context: Context,
    private val logCallback: LogCallback,
    private val audioConfig: AudioConfig = AudioConfig.DEFAULT,
) {
    private val systemAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Pre-allocated per-purpose audio slots (created at init, one per stream purpose)
    private var mediaSlot: PurposeSlot? = null
    private var siriSlot: PurposeSlot? = null
    private var phoneCallSlot: PurposeSlot? = null
    private var alertSlot: PurposeSlot? = null

    // Navigation: single track (format doesn't change mid-session)
    @Volatile private var navTrack: AudioTrack? = null

    @Volatile private var navBuffer: AudioRingBuffer? = null

    @Volatile private var navFormat: AudioFormatConfig? = null

    private var mediaVolume: Float = 1.0f
    private var navVolume: Float = 1.0f
    private var isDucked: Boolean = false
    private var duckLevel: Float = 1.0f // 1.0 = no duck; adapter sets lower via volumeDuration packets

    // AudioFocus state machine
    private val activeFocusRequests = mutableMapOf<StreamPurpose, AudioFocusRequest>()
    private var focusDuckLevel: Float = 1.0f // system-driven duck (1.0 = none)

    /** Per-purpose focus listeners — AAOS CarAudioFocus uses the listener as identity key.
     *  A single listener cannot hold focus for two different usages simultaneously. */
    private val focusListeners = mutableMapOf<StreamPurpose, AudioManager.OnAudioFocusChangeListener>()
    private val focusHandler = Handler(Looper.getMainLooper())

    private fun getOrCreateFocusListener(purpose: StreamPurpose): AudioManager.OnAudioFocusChangeListener =
        focusListeners.getOrPut(purpose) {
            AudioManager.OnAudioFocusChangeListener { focusChange ->
                val changeStr =
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
                        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
                        else -> "UNKNOWN($focusChange)"
                    }
                logDebug("[AUDIO_FOCUS] FocusChange($purpose): $changeStr")
                // Only MEDIA adjusts volume on duck — other purposes are short-lived
                // foreground streams that play at full volume.
                if (purpose == StreamPurpose.MEDIA) {
                    focusDuckLevel =
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_GAIN -> 1.0f
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 0.0f
                            AudioManager.AUDIOFOCUS_LOSS -> 0.0f
                            else -> focusDuckLevel
                        }
                    applyEffectiveVolume()
                }
            }
        }

    private var playbackThread: AudioPlaybackThread? = null
    private val isRunning = AtomicBoolean(false)

    private var navUnderruns: Int = 0

    // 30s aggregate stats counters (per-window deltas, reset each interval)
    private val statsInterval = 30_000L
    private var lastStatsTime = 0L
    private val windowMediaRx = AtomicLong(0)
    private val windowNavRx = AtomicLong(0)
    private val windowMediaPlayed = AtomicLong(0)
    private val windowNavPlayed = AtomicLong(0)
    private val windowMediaResiduals = AtomicLong(0)
    private val windowNavResiduals = AtomicLong(0)
    private val windowZeroFiltered = AtomicLong(0)
    private var prevNavOverflow = 0
    private var prevNavUnderruns = 0

    private val bufferMultiplier = audioConfig.bufferMultiplier
    private val prefillThresholdMs = audioConfig.prefillThresholdMs
    private val underrunRecoveryThreshold = 10
    private val minBufferLevelMs = 0 // AudioTrack internal buffer (4x min) provides jitter protection

    // AUDIO_ROUTE dedup: only log when route changes, plus periodic summary
    private var lastRoutePurpose: StreamPurpose? = null
    private var lastRouteSampleRate: Int = 0
    private var lastRouteChannels: Int = 0
    private var routeRepeatCount: Long = 0

    @Volatile private var navStarted = false

    @Volatile private var navStartTime: Long = 0

    @Volatile private var navPendingPlay = false

    /**
     * Per-purpose audio slot: owns AudioTrack + ring buffer + playback state.
     * Pre-allocated at init, one per stream purpose. Format may change if adapter
     * sends unexpected decode types (rare — triggers track recreation).
     */
    private class PurposeSlot(
        var format: AudioFormatConfig,
        val purpose: StreamPurpose,
        var track: AudioTrack?,
        var buffer: AudioRingBuffer,
        var started: Boolean = false,
        var pendingPlay: Boolean = false,
        var residualOffset: Int = 0,
        var residualCount: Int = 0,
        var underruns: Int = 0,
        var tempBuffer: ByteArray,
        // Stats delta tracking
        var prevOverflow: Int = 0,
        var prevUnderruns: Int = 0,
        // Idle-pause: timestamp when buffer first went empty while playing.
        // Track paused after idlePauseMs to release AAOS volume context.
        var idleSince: Long = 0,
    )

    // After a slot's ring buffer has been empty for this long while playing, take
    // an idle action. Prevents AAOS volume keys from being hijacked by stale PLAYING
    // tracks. Action depends on purpose (see playback-thread idle handling at line ~1170):
    //   - MEDIA, PHONE_CALL, SIRI: write a silence frame to keep the track PLAYING
    //     (avoids volume-context flap during natural inter-packet gaps).
    //   - ALERT: actually pause (short bursts with explicit start/end signals).
    private val idlePauseMs = 200L

    // Reject NAVI_COMPLETE if the prompt has been audible for less than this many ms
    // and the buffer still holds >50ms of data — guards against the adapter sending
    // STOP before the prompt has had time to play.
    private val minNavPlayDurationMs = 300

    // Skip warmup noise: mixed 0xFFFF/0x0000/0xFEFF patterns for ~200-400ms after NavStart
    private val navWarmupSkipMs = 250

    private var navEndMarkersDetected: Long = 0
    private var navWarmupFramesSkipped: Long = 0

    // Flush after consecutive zero packets to prevent resampling noise on GM AAOS
    private var consecutiveNavZeroPackets: Int = 0
    private val navZeroFlushThreshold = 3

    // Drop nav packets after NAVI_STOP (~2s silence before NAVI_COMPLETE per USB captures)
    @Volatile private var navStopped = false

    private val lock = Any()

    /**
     * Initialize the audio manager and start playback thread.
     */
    fun initialize(): Boolean {
        synchronized(lock) {
            if (isRunning.get()) {
                log("[AUDIO] Already initialized")
                return true
            }

            try {
                isRunning.set(true)

                // Pre-allocate per-purpose audio slots
                mediaSlot = createPurposeSlot(StreamPurpose.MEDIA, AudioFormats.FORMAT_4)
                siriSlot = createPurposeSlot(StreamPurpose.SIRI, AudioFormats.FORMAT_5)
                phoneCallSlot = createPurposeSlot(StreamPurpose.PHONE_CALL, AudioFormats.FORMAT_3)
                alertSlot = createPurposeSlot(StreamPurpose.ALERT, AudioFormats.FORMAT_6)

                // Start playback thread
                playbackThread = AudioPlaybackThread().also { it.start() }

                val perfModeStr =
                    if (audioConfig.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
                        "LOW_LATENCY"
                    } else {
                        "NONE"
                    }
                log(
                    "[AUDIO] DualStreamAudioManager initialized with config: " +
                        "sampleRate=${audioConfig.sampleRate}Hz, " +
                        "bufferMult=${audioConfig.bufferMultiplier}x, " +
                        "perfMode=$perfModeStr, " +
                        "prefill=${audioConfig.prefillThresholdMs}ms, " +
                        "slots=MEDIA/SIRI/PHONE_CALL/ALERT pre-allocated",
                )
                return true
            } catch (e: Exception) {
                log("[AUDIO] ERROR: Failed to initialize: ${e.message}")
                isRunning.set(false)
                return false
            }
        }
    }

    /**
     * Check if audio data is zero-filled (adapter issue).
     * Real audio has dithering noise even during silence.
     */
    private fun isZeroFilledAudio(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < 16) return false
        val end = offset + length

        val positions =
            intArrayOf(
                offset,
                offset + ((length * 0.25).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.5).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.75).toInt() and 0x7FFFFFFE),
                offset + ((length - 8).coerceAtLeast(0) and 0x7FFFFFFE),
            )

        for (pos in positions) {
            if (pos + 4 > end) continue
            if (data[pos] != 0.toByte() || data[pos + 1] != 0.toByte() ||
                data[pos + 2] != 0.toByte() || data[pos + 3] != 0.toByte()
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Detect nav end marker (solid 0xFFFF). Adapter sends before NaviStop.
     * Distinct from warmup noise (mixed 0xFFFF/0x0000/0xFEFF patterns).
     * When detected: flush buffers for clean next NaviStart.
     */
    private fun isNavEndMarker(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < 32) return false
        val end = offset + length

        // Sample 4 positions - all must be 0xFFFF for end marker
        val positions =
            intArrayOf(
                offset,
                offset + ((length * 0.25).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.5).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.75).toInt() and 0x7FFFFFFE),
            )

        for (pos in positions) {
            if (pos + 3 >= end) continue
            if (data[pos] != 0xFF.toByte() || data[pos + 1] != 0xFF.toByte() ||
                data[pos + 2] != 0xFF.toByte() || data[pos + 3] != 0xFF.toByte()
            ) {
                return false
            }
        }

        return true
    }

    /**
     * Detect warmup noise (near-silence mix of 0xFFFF/0x0000/0xFEFF).
     * Appears ~200-400ms after NavStart. Causes distortion if played.
     */
    private fun isWarmupNoise(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < 32) return false
        val end = offset + length

        val sampleCount = 8
        var nearSilenceCount = 0

        for (i in 0 until sampleCount) {
            val pos = offset + (((length * i) / sampleCount) and 0x7FFFFFFE)
            if (pos + 1 >= end) continue

            val sample = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample >= 32768) sample - 65536 else sample

            if (signedSample in -258..2) nearSilenceCount++
        }

        return nearSilenceCount >= 6
    }

    /** Flush nav buffers when end marker (0xFFFF) detected. Ensures clean next prompt. */
    private fun flushNavBuffers() {
        synchronized(lock) {
            val discardedMs = navBuffer?.fillLevelMs() ?: 0
            navBuffer?.clear()

            // flush() requires paused state; track resumes via ensureNavTrack on next audio
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            }

            navEndMarkersDetected++
            log("[AUDIO] Nav end marker detected, buffers flushed — discarded=${discardedMs}ms (total: $navEndMarkersDetected)")
        }
    }

    /** Write audio to stream buffer (non-blocking, USB thread). Returns bytes written. */
    fun writeAudio(
        data: ByteArray,
        dataOffset: Int,
        dataLength: Int,
        audioType: Int,
        decodeType: Int,
        routingState: AudioRoutingState = AudioRoutingState(),
    ): Int {
        if (!isRunning.get()) return -1

        // Navigation handles zeros separately for consecutive tracking and buffer flush
        val isZeroFilled = isZeroFilledAudio(data, dataOffset, dataLength)
        if (isZeroFilled && audioType != AudioStreamType.NAVIGATION) {
            windowZeroFiltered.incrementAndGet()
            return 0
        }

        // Route: Navigation → nav track, everything else → per-purpose pre-allocated track
        return when (audioType) {
            AudioStreamType.NAVIGATION -> {
                // Drop packets after NAVI_STOP (~2s silence before NAVI_COMPLETE per USB captures)
                if (navStopped) return 0

                // Check end marker before track creation (navStartTime may be 0 — this is
                // the cold-start path; nav track never allocated in 2026-04-20 POTATO
                // sessions, Nav[Rx:0] in every AUDIO_PERF log).
                if (isNavEndMarker(data, dataOffset, dataLength)) {
                    flushNavBuffers()
                    consecutiveNavZeroPackets = 0
                    return 0
                }

                // Consecutive zeros cause resampling noise on GM AAOS (44.1kHz→48kHz)
                if (isZeroFilled) {
                    consecutiveNavZeroPackets++
                    windowZeroFiltered.incrementAndGet()
                    if (consecutiveNavZeroPackets >= navZeroFlushThreshold) {
                        flushNavBuffers()
                        log(
                            "[AUDIO_FILTER] Nav buffer flushed after " +
                                "$consecutiveNavZeroPackets consecutive zero packets",
                        )
                        consecutiveNavZeroPackets = 0
                    }
                    return 0
                }

                consecutiveNavZeroPackets = 0
                ensureNavTrack(decodeType)
                val timeSinceStart = System.currentTimeMillis() - navStartTime

                // Skip warmup noise in first ~250ms
                if (timeSinceStart < navWarmupSkipMs && isWarmupNoise(data, dataOffset, dataLength)) {
                    navWarmupFramesSkipped++
                    if (navWarmupFramesSkipped == 1L || navWarmupFramesSkipped % 10 == 0L) {
                        log(
                            "[AUDIO] Skipped nav warmup frame " +
                                "(${timeSinceStart}ms since start, total: $navWarmupFramesSkipped)",
                        )
                    }
                    return 0
                }

                windowNavRx.incrementAndGet()
                val bytesWritten = navBuffer?.write(data, dataOffset, dataLength) ?: -1
                if (bytesWritten > 0) {
                    navPackets++
                }
                bytesWritten
            }

            else -> {
                // Two-factor routing: state flags + format match → pre-allocated slot
                windowMediaRx.incrementAndGet()
                val format = AudioFormats.fromDecodeType(decodeType)
                val targetSlot = resolveTargetSlot(format, routingState) ?: return 0
                ensureSlotFormat(targetSlot, format)
                // Activate slot if track is not yet playing (pre-allocated slots start in STOPPED)
                if (!targetSlot.pendingPlay && targetSlot.track?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    targetSlot.pendingPlay = true
                    targetSlot.started = false
                }
                targetSlot.buffer.write(data, dataOffset, dataLength)
            }
        }
    }

    /** Set media ducking (Len=16 volume packets from adapter). */
    fun setDucking(targetVolume: Float) {
        synchronized(lock) {
            isDucked = targetVolume < 1.0f
            duckLevel = targetVolume.coerceIn(0.0f, 1.0f)

            applyEffectiveVolume()

            if (isDucked) {
                log("[AUDIO] Media ducked to ${(duckLevel * 100).toInt()}%")
            } else {
                log("[AUDIO] Media volume restored to ${(mediaVolume * 100).toInt()}%")
            }
        }
    }

    /** Combine adapter ducking and system focus ducking, apply to media slot only.
     *  Adapter duck (duckLevel) only applies when explicitly set via volumeDuration packet (isDucked=true).
     *  System focus duck (focusDuckLevel) always applies — it tracks Android AudioFocus state. */
    private fun applyEffectiveVolume() {
        val adapterFactor = if (isDucked) duckLevel else 1.0f
        val effectiveVolume = mediaVolume * minOf(adapterFactor, focusDuckLevel)
        mediaSlot?.track?.setVolume(effectiveVolume)
        // Non-media purpose slots (PHONE_CALL, SIRI, ALERT) stay at full volume
        logDebug(
            "[AUDIO_FOCUS] Volume: effective=${(effectiveVolume * 100).toInt()}% " +
                "(media=${(mediaVolume * 100).toInt()}% adapterDuck=${if (isDucked) "${(duckLevel * 100).toInt()}%" else "off"} " +
                "focusDuck=${(focusDuckLevel * 100).toInt()}%)",
        )
    }

    /** Request AudioFocus for a stream purpose. */
    fun onPurposeChanged(purpose: StreamPurpose) {
        synchronized(lock) {
            val gainType =
                when (purpose) {
                    StreamPurpose.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
                    StreamPurpose.PHONE_CALL -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    StreamPurpose.SIRI -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    StreamPurpose.ALERT, StreamPurpose.RINGTONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    StreamPurpose.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                }

            val (usage, contentType) = purposeToAttributes(purpose)

            val focusRequest =
                AudioFocusRequest
                    .Builder(gainType)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build(),
                    ).setOnAudioFocusChangeListener(getOrCreateFocusListener(purpose), focusHandler)
                    .build()

            val result = systemAudioManager.requestAudioFocus(focusRequest)
            activeFocusRequests[purpose] = focusRequest

            // Reset focusDuckLevel when MEDIA focus is granted. With per-purpose listeners,
            // MEDIA's listener gets proper GAIN callbacks when unblocked, but the sync reset
            // handles the case where MEDIA focus was re-requested after abandonment.
            if (purpose == StreamPurpose.MEDIA && result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && focusDuckLevel != 1.0f) {
                logDebug("[AUDIO_FOCUS] Reset focusDuckLevel=100% (MEDIA focus granted)")
                focusDuckLevel = 1.0f
                applyEffectiveVolume()
            }

            val resultStr =
                when (result) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
                    AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
                    else -> "UNKNOWN($result)"
                }
            val gainStr =
                when (gainType) {
                    AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
                    else -> "UNKNOWN($gainType)"
                }
            logDebug("[AUDIO_FOCUS] Request $purpose: gain=$gainStr → $resultStr")
        }
    }

    /** Abandon AudioFocus for a stream purpose. */
    fun onPurposeEnded(purpose: StreamPurpose) {
        synchronized(lock) {
            activeFocusRequests.remove(purpose)?.let { request ->
                systemAudioManager.abandonAudioFocusRequest(request)
                logDebug("[AUDIO_FOCUS] Abandon $purpose")
            }
            focusListeners.remove(purpose)
        }
    }

    private fun purposeToAttributes(purpose: StreamPurpose): Pair<Int, Int> =
        when (purpose) {
            StreamPurpose.MEDIA -> Pair(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)
            StreamPurpose.PHONE_CALL -> Pair(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH)
            StreamPurpose.SIRI -> Pair(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH)
            // ALERT (incoming-call ringtone): try USAGE_VOICE_COMMUNICATION_SIGNALLING
            // to attempt routing onto BUS_CALL_RING → vgroup 2 (RING) on GM AAOS.
            // Standard AOSP maps USAGE_NOTIFICATION_RINGTONE to CALL_RING context, but
            // GM's carAudioContexts.xml on this head unit remaps it to BUS_NOTIFICATION
            // (vgroup 6 SYSTEM) — not adjustable, not on the right physical bus.
            // SIGNALLING is the closest usage that semantically belongs to the telephony
            // group and may map to BUS_CALL_RING in GM's config without requiring a
            // Telecom ConnectionService. Fallback: USAGE_NOTIFICATION_RINGTONE.
            StreamPurpose.ALERT -> Pair(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, AudioAttributes.CONTENT_TYPE_SONIFICATION)
            StreamPurpose.RINGTONE -> Pair(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, AudioAttributes.CONTENT_TYPE_SONIFICATION)
            StreamPurpose.NAVIGATION -> Pair(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH)
        }

    // ========== Stream Stop Methods ==========
    //
    // These methods pause individual AudioTracks when their corresponding stream ends
    // (e.g., AudioNaviStop command received). This is critical for AAOS volume control:
    //
    // AAOS CarAudioService determines which volume group to adjust based on "active players"
    // (AudioTracks in PLAYSTATE_PLAYING). If a track remains in PLAYING state after its
    // audio stream ends, AAOS continues to prioritize that context for volume control.
    //
    // Example: Nav track left in PLAYING state after nav prompt ends causes volume keys
    // to control NAVIGATION volume instead of MEDIA volume, appearing "stuck".
    //
    // Using pause() instead of stop() preserves the buffer and allows quick resume
    // when the stream restarts, avoiding audio glitches.

    /** Reset nav stopped state (AUDIO_NAVI_START). Allows packets after a prior NAVI_STOP. */
    fun onNavStarted() {
        navStopped = false
        logDebug("[AUDIO_TRACK] onNavStarted() - accepting nav packets")
    }

    /** Stop accepting nav packets (AUDIO_NAVI_STOP). Track cleanup on NAVI_COMPLETE. */
    fun onNavStopped() {
        log("[NAV_STOP] onNavStopped() called - will reject incoming nav packets")
        navStopped = true
    }

    /** Pause nav track (AUDIO_NAVI_COMPLETE). Enforces min duration (Sessions 1-2 fix). */
    fun stopNavTrack() {
        log("[NAV_STOP] stopNavTrack() called")

        synchronized(lock) {
            val trackStateStr =
                when (val trackState = navTrack?.playState) {
                    AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
                    AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
                    AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
                    else -> "null/unknown($trackState)"
                }
            log("[NAV_STOP] Track state: $trackStateStr, navStarted=$navStarted, navStartTime=$navStartTime")

            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val playDuration = System.currentTimeMillis() - navStartTime
                    val bufferLevel = navBuffer?.fillLevelMs() ?: 0
                    val bytesRead = navBuffer?.totalBytesRead ?: 0

                    log(
                        "[NAV_STOP] playDuration=${playDuration}ms, bufferLevel=${bufferLevel}ms, " +
                            "bytesRead=$bytesRead, packets=$navPackets, underruns=$navUnderruns",
                    )

                    if (playDuration < minNavPlayDurationMs && bufferLevel > 50) {
                        log(
                            "[NAV_STOP] Ignoring premature stop after ${playDuration}ms " +
                                "(min=${minNavPlayDurationMs}ms), buffer has ${bufferLevel}ms data",
                        )
                        return
                    }

                    // Secondary flush (primary is on 0xFFFF end marker)
                    track.pause()
                    track.flush()
                    val discardedMs = navBuffer?.fillLevelMs() ?: 0
                    navBuffer?.clear()
                    log(
                        "[NAV_STOP] Nav track paused+flushed after ${playDuration}ms - " +
                            "discarded=${discardedMs}ms, packets=$navPackets, underruns=$navUnderruns",
                    )
                } else {
                    log("[NAV_STOP] Track not playing, skipping pause (state=$trackStateStr)")
                }
            } ?: run {
                log("[NAV_STOP] navTrack is null, nothing to stop")
            }

            navStarted = false
            navPendingPlay = false
            navStopped = false // Reset stopped state for next nav session
            navPackets = 0 // Reset packet counter for next nav prompt
            navUnderruns = 0 // Reset underrun counter for next nav prompt
            navWarmupFramesSkipped = 0 // Reset per-prompt warmup skip counter
            // Abandon nav focus
            onPurposeEnded(StreamPurpose.NAVIGATION)
            logDebug("[AUDIO_FOCUS] Nav focus abandoned (stopNavTrack)")
        }
    }

    private var navPackets: Long = 0

    /**
     * Pause the SIRI track on AUDIO_SIRI_STOP. Same rationale as stopPhoneCallTrack:
     * SIRI now writes silence on idle gaps (TTS inter-utterance pauses) to prevent
     * mid-session volume-context flap, so it needs an explicit pause at end-of-session
     * to release the USAGE_ASSISTANT active-player signal back to AAOS.
     */
    fun stopSiriTrack() {
        log("[SIRI_STOP] stopSiriTrack() called")
        synchronized(lock) {
            val slot = siriSlot ?: run {
                log("[SIRI_STOP] siriSlot is null, nothing to stop")
                return
            }
            slot.track?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.pause()
                        track.flush()
                    } catch (e: Exception) {
                        log("[SIRI_STOP] pause/flush failed: ${e.message}")
                    }
                    log("[SIRI_STOP] SIRI track paused+flushed")
                } else {
                    log("[SIRI_STOP] Track not playing (state=${track.playState}), skipping pause")
                }
            }
            slot.started = false
            slot.idleSince = 0
            slot.residualOffset = 0
            slot.residualCount = 0
            slot.buffer.clear()
        }
    }

    /**
     * Pause the phone-call track on AUDIO_PHONECALL_STOP.
     *
     * Required because the idle-pause path no longer pauses PHONE_CALL — it writes
     * silence to keep the track PLAYING across natural inter-packet gaps (VAD/silence
     * frames). Without an explicit pause at end-of-call, the AudioTrack stays in
     * PLAYSTATE_PLAYING with USAGE_VOICE_COMMUNICATION and AAOS keeps routing volume
     * keys to the CALL group while ducking MEDIA/SIRI to inaudibility — symptom is
     * "post-call silence: media plays internally but nothing is heard, volume stuck
     * on call group." NAV breaks through via its own focus request, but on NAV end
     * the system falls back to the still-playing PHONE_CALL track.
     *
     * Mirrors stopNavTrack() — pause + flush + reset slot state.
     */
    fun stopPhoneCallTrack() {
        log("[PHONECALL_STOP] stopPhoneCallTrack() called")
        synchronized(lock) {
            val slot = phoneCallSlot ?: run {
                log("[PHONECALL_STOP] phoneCallSlot is null, nothing to stop")
                return
            }
            slot.track?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.pause()
                        track.flush()
                    } catch (e: Exception) {
                        log("[PHONECALL_STOP] pause/flush failed: ${e.message}")
                    }
                    log("[PHONECALL_STOP] Phone-call track paused+flushed")
                } else {
                    log("[PHONECALL_STOP] Track not playing (state=${track.playState}), skipping pause")
                }
            }
            slot.started = false
            slot.idleSince = 0
            slot.residualOffset = 0
            slot.residualCount = 0
            slot.buffer.clear()
        }
    }

    /** Stop playback and release all resources. */
    fun release() {
        synchronized(lock) {
            log("[AUDIO] Releasing DualStreamAudioManager")

            isRunning.set(false)

            playbackThread?.interrupt()
            try {
                playbackThread?.join(1000)
            } catch (_: InterruptedException) {
            }
            playbackThread = null

            releaseSlot(mediaSlot)
            releaseSlot(siriSlot)
            releaseSlot(phoneCallSlot)
            releaseSlot(alertSlot)
            mediaSlot = null
            siriSlot = null
            phoneCallSlot = null
            alertSlot = null

            releaseNavTrack()

            // Abandon all AudioFocus requests
            for ((purpose, request) in activeFocusRequests) {
                systemAudioManager.abandonAudioFocusRequest(request)
                logDebug("[AUDIO_FOCUS] Abandon $purpose (release)")
            }
            activeFocusRequests.clear()
            focusListeners.clear()

            navBuffer?.clear()
            navBuffer = null

            log("[AUDIO] DualStreamAudioManager released")
        }
    }

    // ========== Private Methods ==========

    /** Create a pre-allocated purpose slot with AudioTrack + ring buffer. */
    private fun createPurposeSlot(
        purpose: StreamPurpose,
        format: AudioFormatConfig,
    ): PurposeSlot {
        val buffer =
            AudioRingBuffer(
                capacityMs = audioConfig.mediaBufferCapacityMs,
                sampleRate = format.sampleRate,
                channels = format.channelCount,
            )
        val chunkSize = format.sampleRate * format.channelCount * 2 * 5 / 1000
        val track = createAudioTrack(format, purpose)
        log(
            "[AUDIO_TRACK] Pre-allocated $purpose track: ${format.sampleRate}Hz " +
                "${format.channelCount}ch id=${track?.audioSessionId}",
        )
        return PurposeSlot(
            format = format,
            purpose = purpose,
            track = track,
            buffer = buffer,
            tempBuffer = ByteArray(chunkSize * 20),
        )
    }

    /**
     * Two-factor routing: state flags + format match → pre-allocated slot.
     *
     * During transitions (e.g., SIRI_START sent but adapter still sending media PCM),
     * the format won't match the target purpose, so media PCM stays on the MEDIA track.
     */
    private fun resolveTargetSlot(
        format: AudioFormatConfig,
        state: AudioRoutingState,
    ): PurposeSlot? {
        // 8kHz = AA phone calls (HFP/SCO narrowband), 16kHz = CarPlay phone calls (iAP2 wideband)
        val slot =
            if (state.isPhoneCallActive && (format.sampleRate == 8000 || format.sampleRate == 16000) && format.channelCount == 1) {
                phoneCallSlot
            } else if (state.isSiriActive && format.sampleRate == 16000 && format.channelCount == 1) {
                siriSlot
            } else if (state.isAlertActive) {
                alertSlot // Format-agnostic: ensureSlotFormat() handles 48kHz/2ch (CarPlay) or 24kHz/1ch (AA)
            } else {
                mediaSlot // Default: all other audio (including transition-period media)
            }
        val purpose = slot?.purpose
        if (purpose != lastRoutePurpose || format.sampleRate != lastRouteSampleRate || format.channelCount != lastRouteChannels) {
            if (routeRepeatCount > 0) {
                logDebug("[AUDIO_ROUTE] (suppressed $routeRepeatCount identical)")
            }
            logDebug(
                "[AUDIO_ROUTE] ${format.sampleRate}Hz/${format.channelCount}ch " +
                    "state=(call=${state.isPhoneCallActive} siri=${state.isSiriActive} alert=${state.isAlertActive}) " +
                    "→ ${purpose ?: "null"}",
            )
            lastRoutePurpose = purpose
            lastRouteSampleRate = format.sampleRate
            lastRouteChannels = format.channelCount
            routeRepeatCount = 0
        } else {
            routeRepeatCount++
        }
        return slot
    }

    /** Ensure slot's AudioTrack matches incoming format; recreate if different (rare). */
    private fun ensureSlotFormat(
        slot: PurposeSlot,
        incomingFormat: AudioFormatConfig,
    ) {
        if (slot.format == incomingFormat && slot.track != null) return

        synchronized(lock) {
            // Double-check under lock
            if (slot.format == incomingFormat && slot.track != null) return

            if (slot.format != incomingFormat) {
                // Format change: recreate track + buffer
                try {
                    slot.track?.let { track ->
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                        track.release()
                    }
                } catch (_: Exception) {
                }

                slot.format = incomingFormat
                slot.buffer =
                    AudioRingBuffer(
                        capacityMs = audioConfig.mediaBufferCapacityMs,
                        sampleRate = incomingFormat.sampleRate,
                        channels = incomingFormat.channelCount,
                    )
                val chunkSize = incomingFormat.sampleRate * incomingFormat.channelCount * 2 * 5 / 1000
                slot.tempBuffer = ByteArray(chunkSize * 20)
                slot.track = createAudioTrack(incomingFormat, slot.purpose)
                slot.started = false
                slot.pendingPlay = true
                slot.residualCount = 0
                log(
                    "[AUDIO] Recreated ${slot.purpose} track for format change: " +
                        "${incomingFormat.sampleRate}Hz ${incomingFormat.channelCount}ch",
                )
            } else {
                // Same format but dead track (ERROR_DEAD_OBJECT recovery)
                slot.track = createAudioTrack(incomingFormat, slot.purpose)
                slot.started = false
                slot.pendingPlay = true
                log("[AUDIO] Recreated dead ${slot.purpose} track: ${incomingFormat.sampleRate}Hz")
            }
        }
    }

    private fun ensureNavTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            navStopped = false

            // Resume paused track with flush (secondary to end marker flush)
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && navFormat == format && !navPendingPlay) {
                    val discardedMs = navBuffer?.fillLevelMs() ?: 0
                    track.flush()
                    navBuffer?.clear()
                    navPendingPlay = true
                    navStarted = false
                    navStartTime = System.currentTimeMillis()
                    // Request nav focus on resume
                    onPurposeChanged(StreamPurpose.NAVIGATION)
                    log("[AUDIO] Resumed paused nav track with flush — discarded=${discardedMs}ms (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            if (navFormat != format) {
                log("[AUDIO] Nav format change: ${navFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseNavTrack()
                navFormat = format

                navBuffer =
                    AudioRingBuffer(
                        capacityMs = audioConfig.navBufferCapacityMs,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                navTrack = createAudioTrack(format, StreamPurpose.NAVIGATION)
                navPendingPlay = true
                navStartTime = System.currentTimeMillis() // Track start time for min duration
                // Request nav focus on new track creation
                onPurposeChanged(StreamPurpose.NAVIGATION)
            }
        }
    }

    /**
     * Create an AudioTrack with per-purpose AudioAttributes for AAOS CarAudioContext mapping.
     *
     * AAOS CarAudioContext Mapping (this file's actual emissions):
     * - MEDIA        → USAGE_MEDIA                          → MUSIC context
     * - PHONE_CALL   → USAGE_VOICE_COMMUNICATION            → CALL context
     * - SIRI         → USAGE_ASSISTANT                      → VOICE_COMMAND context
     * - ALERT        → USAGE_VOICE_COMMUNICATION_SIGNALLING → CALL_RING (intended; GM-specific)
     * - NAVIGATION   → USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → NAVIGATION context
     *
     * The ALERT mapping is a deliberate GM-AAOS workaround (not the AOSP default of
     * USAGE_NOTIFICATION_RINGTONE → CALL_RING). See [purposeToAttributes] for the
     * rationale and `documents/reference/gminfo/audio/audio_subsystem.md` for the
     * authoritative GM volume-group / bus-routing tables.
     */
    private fun createAudioTrack(
        format: AudioFormatConfig,
        purpose: StreamPurpose,
    ): AudioTrack? {
        try {
            val minBufferSize =
                AudioTrack.getMinBufferSize(
                    format.sampleRate,
                    format.channelConfig,
                    format.encoding,
                )

            if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                log("[AUDIO] ERROR: Invalid buffer size for ${format.sampleRate}Hz")
                return null
            }

            val bufferSize = minBufferSize * bufferMultiplier

            val (usage, contentType) = purposeToAttributes(purpose)

            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()

            val audioFormat =
                AudioFormat
                    .Builder()
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(format.channelConfig)
                    .setEncoding(format.encoding)
                    .build()

            // Performance mode: LOW_LATENCY (default) or NONE (GM AAOS)
            val track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(audioConfig.performanceMode)
                    .build()

            val volume =
                when (purpose) {
                    StreamPurpose.NAVIGATION -> navVolume
                    StreamPurpose.MEDIA -> mediaVolume * minOf(if (isDucked) duckLevel else 1.0f, focusDuckLevel)
                    else -> 1.0f // Non-media purpose slots stay at full volume
                }
            track.setVolume(volume)

            log(
                "[AUDIO] Created $purpose AudioTrack: ${format.sampleRate}Hz " +
                    "${format.channelCount}ch buffer=${bufferSize}B usage=$usage id=${track.audioSessionId}",
            )

            return track
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to create AudioTrack: ${e.message}")
            return null
        }
    }

    private fun releaseSlot(slot: PurposeSlot?) {
        slot ?: return
        try {
            slot.track?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                } catch (e: Exception) {
                    log("[AUDIO] WARN: Failed to stop ${slot.purpose} track: ${e.message}")
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release ${slot.purpose} track: ${e.message}")
        }
        slot.buffer.clear()
        slot.track = null
    }

    private fun releaseNavTrack() {
        try {
            navTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                } catch (e: Exception) {
                    log("[AUDIO] WARN: Failed to stop nav track: ${e.message}")
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release nav track: ${e.message}")
        }
        navTrack = null
        navFormat = null
        navStarted = false
        navPendingPlay = false
    }

    private fun log(message: String) {
        logCallback.log("AUDIO", message)
    }

    private fun logPerf(message: String) {
        logCallback.logPerf("AUDIO_PERF", message)
    }

    private fun logDebug(message: String) {
        logCallback.log("AUDIO_DEBUG", message)
    }

    /** Playback thread (URGENT_AUDIO priority). Separate buffers per stream for safety. */
    private inner class AudioPlaybackThread : Thread("AudioPlayback") {
        // Nav temp buffer (sized for max nav format: 48kHz stereo, 100ms bulk)
        private val navTempBuffer = ByteArray(audioConfig.sampleRate * 2 * 2 / 10)

        // Pre-allocated silence for MEDIA idle-fill (10ms at 48kHz/stereo/16bit = 1920 bytes).
        // Keeps AudioTrack PLAYING to avoid AudioFlinger state-transition overhead.
        private val silenceBuffer = ByteArray(1920)

        // Residual tracking for nav partial WRITE_NON_BLOCKING returns
        private var navResidualOffset = 0
        private var navResidualCount = 0

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            log("[AUDIO] Playback thread started with URGENT_AUDIO priority")

            while (isRunning.get() && !isInterrupted) {
                try {
                    var didWork = false

                    // === Process all pre-allocated purpose slots ===
                    val slotsSnapshot = listOfNotNull(mediaSlot, siriSlot, phoneCallSlot, alertSlot)

                    for (slot in slotsSnapshot) {
                        val track = slot.track ?: continue
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING && !slot.pendingPlay) {
                            continue
                        }

                        val buffer = slot.buffer
                        val currentFillMs = buffer.fillLevelMs()

                        // Pre-fill before first playback
                        if (!slot.started) {
                            if (currentFillMs < prefillThresholdMs) continue
                            slot.started = true
                            if (slot.pendingPlay) {
                                while (buffer.fillLevelMs() > minBufferLevelMs) {
                                    val avail = buffer.availableForRead()
                                    if (avail <= 0) break
                                    val bytesRead =
                                        buffer.read(
                                            slot.tempBuffer,
                                            0,
                                            minOf(avail, slot.tempBuffer.size),
                                        )
                                    if (bytesRead <= 0) break
                                    val written =
                                        track.write(
                                            slot.tempBuffer,
                                            0,
                                            bytesRead,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    if (written <= 0) break
                                    windowMediaPlayed.addAndGet(written.toLong())
                                    if (written < bytesRead) {
                                        slot.residualOffset = written
                                        slot.residualCount = bytesRead - written
                                        break
                                    }
                                }
                                track.play()
                                slot.pendingPlay = false
                                slot.underruns = track.underrunCount
                            }
                            log(
                                "[AUDIO] ${slot.purpose} pre-fill complete " +
                                    "(${slot.format.sampleRate}Hz/${slot.format.channelCount}ch): " +
                                    "${currentFillMs}ms buffered, starting playback",
                            )
                        }

                        // Retry residual from prior partial WRITE_NON_BLOCKING
                        if (slot.residualCount > 0) {
                            val written =
                                track.write(
                                    slot.tempBuffer,
                                    slot.residualOffset,
                                    slot.residualCount,
                                    AudioTrack.WRITE_NON_BLOCKING,
                                )
                            if (written < 0) {
                                slot.residualCount = 0
                                handleTrackError(slot.purpose.name, written, slot)
                                continue
                            }
                            if (written > 0) {
                                windowMediaPlayed.addAndGet(written.toLong())
                                slot.residualOffset += written
                                slot.residualCount -= written
                                didWork = true
                            }
                        }

                        // Skip new reads while residual pending or buffer empty
                        if (slot.residualCount > 0 || currentFillMs <= minBufferLevelMs) {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                                currentFillMs <= minBufferLevelMs && slot.residualCount == 0
                            ) {
                                val now = System.currentTimeMillis()
                                if (slot.idleSince == 0L) {
                                    slot.idleSince = now
                                } else if (now - slot.idleSince >= idlePauseMs) {
                                    if (slot.purpose == StreamPurpose.MEDIA ||
                                        slot.purpose == StreamPurpose.PHONE_CALL ||
                                        slot.purpose == StreamPurpose.SIRI
                                    ) {
                                        // MEDIA, PHONE_CALL, SIRI: write silence to keep AudioTrack
                                        // PLAYING. These streams have natural inter-packet gaps
                                        // (VAD/silence frames in calls; inter-utterance pauses in
                                        // Siri/Assistant TTS) that would otherwise trigger
                                        // pause/resume churn — releasing the volume context to
                                        // AAOS and causing volume keys to flap to MEDIA mid-session.
                                        // Their lifecycle is gated by explicit STOP commands
                                        // (AUDIO_PHONECALL_STOP / AUDIO_SIRI_STOP) which call
                                        // stopPhoneCallTrack() / stopSiriTrack() to release the
                                        // volume context — not by buffer drain.
                                        track.write(
                                            silenceBuffer,
                                            0,
                                            silenceBuffer.size,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    } else {
                                        // ALERT: pause to release AAOS volume context.
                                        // Short bursts with defined start/end signals.
                                        track.pause()
                                        slot.started = false
                                        slot.idleSince = 0
                                        logDebug(
                                            "[AUDIO] ${slot.purpose} idle-paused " +
                                                "(no data for ${idlePauseMs}ms)",
                                        )
                                    }
                                }
                            } else {
                                slot.idleSince = 0
                            }
                            continue
                        }

                        val available = buffer.availableForRead()
                        if (available > 0) {
                            val bytesPerMs =
                                slot.format.sampleRate * slot.format.channelCount * 2 / 1000
                            val maxReadableMs = currentFillMs - minBufferLevelMs
                            val maxReadableBytes = maxReadableMs * bytesPerMs
                            val toRead =
                                minOf(
                                    available,
                                    slot.tempBuffer.size,
                                    maxReadableBytes.coerceAtLeast(0),
                                )

                            if (toRead > 0) {
                                val bytesRead = buffer.read(slot.tempBuffer, 0, toRead)
                                if (bytesRead > 0) {
                                    val written =
                                        track.write(
                                            slot.tempBuffer,
                                            0,
                                            bytesRead,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    if (written < 0) {
                                        handleTrackError(slot.purpose.name, written, slot)
                                        continue
                                    }
                                    if (written > 0) {
                                        windowMediaPlayed.addAndGet(written.toLong())
                                    }
                                    if (written < bytesRead) {
                                        slot.residualOffset = written
                                        slot.residualCount = bytesRead - written
                                        windowMediaResiduals.incrementAndGet()
                                    }
                                    if (written > 0) didWork = true
                                }
                            }
                        }

                        // Underrun detection
                        val underruns = track.underrunCount
                        if (underruns > slot.underruns) {
                            val newUnderruns = underruns - slot.underruns
                            slot.underruns = underruns
                            log(
                                "[AUDIO_UNDERRUN] ${slot.purpose} underrun " +
                                    "(${slot.format.sampleRate}Hz): +$newUnderruns (total: $underruns)",
                            )
                            if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 50) {
                                slot.started = false
                                log(
                                    "[AUDIO_RECOVERY] Resetting ${slot.purpose} pre-fill " +
                                        "(${slot.format.sampleRate}Hz): $newUnderruns underruns, " +
                                        "buffer=${buffer.fillLevelMs()}ms",
                                )
                            }
                        }
                    }

                    // === Navigation: single track ===
                    navBuffer?.let { buffer ->
                        navTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING || navPendingPlay) {
                                val currentNavFillMs = buffer.fillLevelMs()

                                // Shorter pre-fill for nav (lower latency)
                                if (!navStarted) {
                                    if (currentNavFillMs < prefillThresholdMs / 2) return@let
                                    navStarted = true
                                    if (navPendingPlay) {
                                        // Pre-load AudioTrack buffer while STOPPED/PAUSED
                                        // before play() so AudioFlinger finds data on first pull
                                        val navMinBuf = minBufferLevelMs / 2
                                        var preloaded = 0
                                        while (buffer.fillLevelMs() > navMinBuf) {
                                            val avail = buffer.availableForRead()
                                            if (avail <= 0) break
                                            val bytesRead =
                                                buffer.read(
                                                    navTempBuffer,
                                                    0,
                                                    minOf(avail, navTempBuffer.size),
                                                )
                                            if (bytesRead <= 0) break
                                            val written =
                                                track.write(
                                                    navTempBuffer,
                                                    0,
                                                    bytesRead,
                                                    AudioTrack.WRITE_NON_BLOCKING,
                                                )
                                            if (written <= 0) break
                                            windowNavPlayed.addAndGet(written.toLong())
                                            preloaded += written
                                            if (written < bytesRead) {
                                                navResidualOffset = written
                                                navResidualCount = bytesRead - written
                                                break
                                            }
                                        }
                                        track.play()
                                        navPendingPlay = false
                                        navUnderruns = track.underrunCount
                                    }
                                    log(
                                        "[AUDIO] Nav pre-fill complete: " +
                                            "${currentNavFillMs}ms buffered, starting playback",
                                    )
                                }

                                // Retry residual from prior partial WRITE_NON_BLOCKING
                                if (navResidualCount > 0) {
                                    val written =
                                        track.write(
                                            navTempBuffer,
                                            navResidualOffset,
                                            navResidualCount,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    if (written < 0) {
                                        navResidualCount = 0
                                        handleTrackError("NAV", written)
                                        return@let
                                    }
                                    if (written > 0) {
                                        navResidualOffset += written
                                        navResidualCount -= written
                                        windowNavPlayed.addAndGet(written.toLong())
                                        didWork = true
                                    }
                                }

                                val navMinBufferMs = minBufferLevelMs / 2
                                // Also skip new reads while residual is pending (AudioTrack full)
                                if (navResidualCount > 0 || currentNavFillMs <= navMinBufferMs) return@let

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val bytesPerMs =
                                        (navFormat?.sampleRate ?: audioConfig.sampleRate) *
                                            (navFormat?.channelCount ?: 2) * 2 / 1000
                                    val maxReadableMs = currentNavFillMs - navMinBufferMs
                                    val maxReadableBytes = maxReadableMs * bytesPerMs
                                    val toRead = minOf(available, navTempBuffer.size, maxReadableBytes.coerceAtLeast(0))

                                    if (toRead > 0) {
                                        val bytesRead = buffer.read(navTempBuffer, 0, toRead)
                                        if (bytesRead > 0) {
                                            val written =
                                                track.write(
                                                    navTempBuffer,
                                                    0,
                                                    bytesRead,
                                                    AudioTrack.WRITE_NON_BLOCKING,
                                                )
                                            if (written < 0) {
                                                handleTrackError("NAV", written)
                                                return@let
                                            }
                                            if (written > 0) {
                                                windowNavPlayed.addAndGet(written.toLong())
                                            }
                                            if (written < bytesRead) {
                                                navResidualOffset = written
                                                navResidualCount = bytesRead - written
                                                windowNavResiduals.incrementAndGet()
                                            }
                                            if (written > 0) didWork = true
                                        }
                                    }
                                }

                                val underruns = track.underrunCount
                                if (underruns > navUnderruns) {
                                    val newUnderruns = underruns - navUnderruns
                                    navUnderruns = underruns
                                    log(
                                        "[AUDIO_UNDERRUN] Nav underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )

                                    // Recovery: If many underruns and buffer critically low, reset pre-fill
                                    if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 30) {
                                        navStarted = false
                                        log(
                                            "[AUDIO_RECOVERY] Resetting nav pre-fill due to " +
                                                "$newUnderruns underruns, buffer=${buffer.fillLevelMs()}ms",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pace every pass, not just idle ones. Each pass can already stage up
                    // to ~100ms/slot (tempBuffer) into a 750ms ring + the AudioTrack buffer,
                    // so a fixed 5ms cadence cannot underfeed at 1x realtime — it removes the
                    // active-playback busy-spin (previously this only slept when fully idle).
                    // NOTE: `didWork` is now vestigial (assigned but unread) — harmless.
                    sleep(5)

                    // 30s aggregate stats (gated by AUDIO_PERF tag)
                    val now = System.currentTimeMillis()
                    if (now - lastStatsTime >= statsInterval) {
                        val mRx = windowMediaRx.getAndSet(0)
                        val nRx = windowNavRx.getAndSet(0)
                        val mPlay = windowMediaPlayed.getAndSet(0)
                        val nPlay = windowNavPlayed.getAndSet(0)
                        val mRes = windowMediaResiduals.getAndSet(0)
                        val nRes = windowNavResiduals.getAndSet(0)
                        val zf = windowZeroFiltered.getAndSet(0)

                        // Per-purpose slot stats
                        var mOvf = 0
                        var mUrun = 0
                        val sb = StringBuilder()
                        sb
                            .append("Slots[Rx:")
                            .append(mRx)
                            .append(" Play:")
                            .append(mPlay / 1024)
                            .append("KB")

                        for (slot in slotsSnapshot) {
                            val fill = slot.buffer.fillLevelMs()
                            val state =
                                when (slot.track?.playState) {
                                    AudioTrack.PLAYSTATE_PLAYING -> "PLAY"
                                    AudioTrack.PLAYSTATE_PAUSED -> "PAUSE"
                                    else -> "STOP"
                                }
                            sb
                                .append(" ")
                                .append(slot.purpose.name.take(4))
                                .append(":")
                                .append(fill)
                                .append("ms(")
                                .append(state)
                                .append(")")

                            val ovfDelta = slot.buffer.overflowCount - slot.prevOverflow
                            slot.prevOverflow = slot.buffer.overflowCount
                            mOvf += ovfDelta

                            val urunDelta = slot.underruns - slot.prevUnderruns
                            slot.prevUnderruns = slot.underruns
                            mUrun += urunDelta
                        }

                        sb
                            .append(" Ovf:")
                            .append(mOvf)
                            .append(" Urun:")
                            .append(mUrun)
                        if (mRes > 0) sb.append(" Res:").append(mRes)

                        // Nav stats
                        val nFill = navBuffer?.fillLevelMs() ?: 0
                        val nOvf = (navBuffer?.overflowCount ?: 0) - prevNavOverflow
                        val nUrun = navUnderruns - prevNavUnderruns
                        prevNavOverflow = navBuffer?.overflowCount ?: 0
                        prevNavUnderruns = navUnderruns

                        sb
                            .append("] Nav[Rx:")
                            .append(nRx)
                            .append(" Play:")
                            .append(nPlay / 1024)
                            .append("KB")
                            .append(" Buf:")
                            .append(nFill)
                            .append("ms")
                            .append(" Ovf:")
                            .append(nOvf)
                            .append(" Urun:")
                            .append(nUrun)
                        if (nRes > 0) sb.append(" Res:").append(nRes)
                        sb.append("]")
                        if (zf > 0) sb.append(" Zero:").append(zf)
                        val suppressed = routeRepeatCount
                        if (suppressed > 0) sb.append(" Route:").append(lastRoutePurpose?.name ?: "?").append("x").append(suppressed)
                        routeRepeatCount = 0
                        sb.append(" Duck:").append(if (isDucked) "Y" else "N")
                        sb.append(" FDuck:").append((focusDuckLevel * 100).toInt()).append("%")
                        val focusPurposes = activeFocusRequests.keys.joinToString(",")
                        if (focusPurposes.isNotEmpty()) sb.append(" Focus:[").append(focusPurposes).append("]")

                        logPerf(sb.toString())
                        lastStatsTime = now
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log("[AUDIO] Playback thread error: ${e.message}")
                }
            }

            log("[AUDIO] Playback thread stopped")
        }

        private fun handleTrackError(
            streamType: String,
            errorCode: Int,
            slot: PurposeSlot? = null,
        ) {
            when (errorCode) {
                AudioTrack.ERROR_DEAD_OBJECT -> {
                    log("[AUDIO] $streamType AudioTrack dead, releasing for recreation")
                    synchronized(lock) {
                        if (slot != null) {
                            // Purpose slot: null track, recreated on next ensureSlotFormat()
                            try {
                                slot.track?.release()
                            } catch (_: Exception) {
                            }
                            slot.track = null
                            slot.started = false
                            slot.pendingPlay = false
                            slot.residualCount = 0
                        } else {
                            // NAV path
                            when (streamType) {
                                "NAV" -> {
                                    try {
                                        navTrack?.release()
                                    } catch (_: Exception) {
                                    }
                                    navTrack = null
                                    navFormat = null
                                    navStarted = false
                                    navPendingPlay = false
                                }
                            }
                        }
                    }
                }

                AudioTrack.ERROR_INVALID_OPERATION -> {
                    log("[AUDIO] $streamType AudioTrack invalid operation")
                }

                else -> {
                    log("[AUDIO] $streamType AudioTrack write error: $errorCode")
                }
            }
        }
    }
}
