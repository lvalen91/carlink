package com.carlink.platform

import android.media.AudioTrack

/**
 * AudioConfig - Configuration for audio playback behavior.
 *
 * PURPOSE:
 * Provides platform-specific AudioTrack settings to optimize playback on GM AAOS.
 * GM AAOS denies AUDIO_OUTPUT_FLAG_FAST for third-party apps, requiring compensating
 * adjustments to buffer sizes and performance modes.
 *
 * GM AAOS ISSUES (confirmed on gminfo37 Y181.3.2; every POTATO session 2026-04-20 logs
 * `AudioTrack createTrack_l(8): AUDIO_OUTPUT_FLAG_FAST denied by server` while system
 * apps (createTrack_l(0)) pass; not AOSP-documented):
 * - FAST track denial: AudioFlinger denies the low-latency path for non-system apps,
 *   so PERFORMANCE_MODE_LOW_LATENCY has no upside and can increase jitter.
 * - Native sample rate: 48 kHz. Any other rate triggers standard AudioFlinger
 *   resampling (Android-general behavior) which — on this device — further disqualifies
 *   the stream from FAST eligibility and adds latency.
 *
 * CONFIGURATION SELECTION:
 * - DEFAULT: ARM platforms where FAST is available (LOW_LATENCY + moderate buffers).
 * - GM_AAOS: Intel GM AAOS (typically 48 kHz, larger buffers, NONE performance mode).
 *   sampleRate may still be overridden via userSampleRate for field testing — callers
 *   should omit the override in production to preserve the 48 kHz invariant.
 *
 * Canonical profiles (DEFAULT, GM_AAOS) are immutable vals; forPlatform always
 * returns a fresh `copy()` rather than mutating the source.
 *
 * No tests. Add coverage if the branch policy ever gets more complex.
 *
 * Reference:
 * - https://source.android.com/docs/core/audio/latency/design
 * - https://developer.android.com/reference/android/media/AudioTrack
 */
data class AudioConfig(
    /** Target sample rate (48000Hz avoids resampling on GM AAOS). */
    val sampleRate: Int,
    /** Buffer multiplier on minBufferSize (4x typical, higher = more jitter tolerance). */
    val bufferMultiplier: Int,
    /** AudioTrack performance mode (LOW_LATENCY or NONE for GM AAOS). */
    val performanceMode: Int,
    /** Min buffer level before playback starts (prevents initial underruns). */
    val prefillThresholdMs: Int,
    /** Media ring buffer capacity (larger on GM AAOS for stall absorption). */
    val mediaBufferCapacityMs: Int,
    /** Nav ring buffer capacity (lower latency requirements than media). */
    val navBufferCapacityMs: Int,
) {
    companion object {
        /** ARM platforms. FAST track available, 4x buffer, 80ms prefill.
         *  P99 jitter ~7ms (USB-capture analysis, AudioRingBuffer.kt:8, max 30ms). 4x
         *  multiplier empirically adequate: POTATO 2026-04-20 AUDIO_PERF 30s windows
         *  show Ovf:0 consistently, Urun typically 0-4 (outlier 12 once during heavy
         *  focus transition). Retune if platform jitter changes materially. */
        val DEFAULT =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 4,
                performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                prefillThresholdMs = 80,
                mediaBufferCapacityMs = 500,
                navBufferCapacityMs = 200,
            )

        /** Intel GM AAOS. 48kHz native, FAST denied, larger buffers for stall absorption. */
        val GM_AAOS =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 4,
                performanceMode = AudioTrack.PERFORMANCE_MODE_NONE,
                prefillThresholdMs = 80,
                mediaBufferCapacityMs = 750,
                navBufferCapacityMs = 300,
            )

        /**
         * Select config based on platform. GM AAOS audio fixes require BOTH:
         * (1) Intel x86/x86_64 architecture, (2) GM AAOS device.
         */
        fun forPlatform(
            platformInfo: PlatformDetector.PlatformInfo,
            userSampleRate: Int? = null,
        ): AudioConfig {
            val effectiveSampleRate = userSampleRate ?: platformInfo.nativeSampleRate

            return when {
                // Intel + GM AAOS: the profiled target (gminfo37). sampleRate is
                // overridable via userSampleRate for field testing — intentional,
                // even though non-48kHz defeats the "avoid resampling" rationale.
                platformInfo.requiresGmAaosAudioFixes() -> {
                    GM_AAOS.copy(sampleRate = effectiveSampleRate)
                }

                // Intel, non-GM: emulator path. GM audio fixes don't apply (no FAST
                // denial), but Intel codec fixes do. bufferMultiplier=4 is redundant
                // with DEFAULT — kept explicit for parallelism with the GM branch.
                platformInfo.requiresIntelMediaCodecFixes() -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                    )
                }

                // ARM fallback: largest ring buffers (1000/400ms) because this branch
                // covers unknown/untested hardware. Counter-intuitive vs GM_AAOS (750/300)
                // — GM is the profiled device with known jitter (POTATO 2026-04-20 Ovf:0);
                // ARM has to absorb the worst case across any vendor HAL. STILL UNVERIFIED:
                // no 2026-04-20 captures on ARM hardware. Retune if a specific ARM target
                // gets profiled.
                else -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                        prefillThresholdMs = 80,
                        mediaBufferCapacityMs = 1000,
                        navBufferCapacityMs = 400,
                    )
                }
            }
        }
    }
}
