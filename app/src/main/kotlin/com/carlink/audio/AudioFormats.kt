package com.carlink.audio

import android.media.AudioFormat

/**
 * Audio format configuration matching CPC200-CCPA protocol decode types.
 *
 * Note: [bitDepth] is informational only. [encoding] is hardcoded to
 * [AudioFormat.ENCODING_PCM_16BIT] regardless of this value, and the field is
 * not read elsewhere.
 */
data class AudioFormatConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int = 16,
) {
    val channelConfig: Int
        get() =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

    val encoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT
}

/**
 * Predefined audio formats from CPC200-CCPA protocol.
 *
 * Maps the wire-level `decode_type` byte to a concrete PCM playback format.
 *
 * `decode_type` serves dual purposes:
 * 1. Audio format specification (sample rate, channels).
 * 2. Semantic context for the audio command.
 *
 * `decode_type` appears in the standard audio packet header — both for 13-byte
 * command packets and for larger PCM audio-data packets. Wire layout of the
 * 13-byte command form:
 *   [decode_type:4][volume:4][audio_type:4][command:1]
 *
 * Observed semantics (from CPC200 adapter capture analysis + firmware RE):
 * - decode_type=2: Stop/cleanup commands (MEDIA_STOP, PHONECALL_STOP).
 *   Firmware also defines a 44.1kHz stereo audio-data variant, but the app
 *   currently configures the adapter for 48kHz at init, so that variant is
 *   not exercised — would require an adapter-init code change to reach.
 * - decode_type=4: Default playback (CarPlay AND Android Auto, 48kHz)
 *   — MEDIA_START, NAVI_*, ALERT_*, OUTPUT_*.
 * - decode_type=5: Voice/mic — Siri, CarPlay phone calls, INPUT_*,
 *   INCOMING_CALL_INIT.
 *
 * Values 1, 6, 7 firmware-supported but STILL not observed as of 2026-04-20 across
 * 3 independent hosts: POTATO GM AAOS (~9h, 3 CarPlay sessions), macOS Carlink app
 * (24-min CarPlay session: 22,129 dt=4 + 732 dt=5 + 221 TX mic dt=5), and AAOS
 * emulator v120. Only decode_type 4 and 5 appear on wire. FORMAT_6 remains the
 * pre-allocated ALERT slot config in DualStreamAudioManager regardless.
 */
object AudioFormats {
    val FORMAT_1 = AudioFormatConfig(48000, 2) // Music stereo (firmware-supported, not observed)
    val FORMAT_2 = AudioFormatConfig(48000, 2) // Stop/cleanup commands. 44.1kHz audio-data variant unreachable (adapter hardcoded to 48kHz at init)
    val FORMAT_3 = AudioFormatConfig(8000, 1)  // Android Auto phone calls (HFP/SCO narrowband). CarPlay phone calls use FORMAT_5
    val FORMAT_4 = AudioFormatConfig(48000, 2) // Default playback — CarPlay and Android Auto
    val FORMAT_5 = AudioFormatConfig(16000, 1) // Siri + CarPlay phone calls (16kHz wideband)
    val FORMAT_6 = AudioFormatConfig(24000, 1) // Enhanced voice (firmware-supported, not observed; used as ALERT slot config)
    val FORMAT_7 = AudioFormatConfig(16000, 2) // Stereo voice (firmware-supported, not observed)

    fun fromDecodeType(decodeType: Int): AudioFormatConfig =
        when (decodeType) {
            1 -> FORMAT_1
            2 -> FORMAT_2
            3 -> FORMAT_3
            4 -> FORMAT_4
            5 -> FORMAT_5
            6 -> FORMAT_6
            7 -> FORMAT_7
            else -> FORMAT_4 // Default to high-quality
        }
}
