package com.carlink.platform

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.carlink.BuildConfig
import com.carlink.util.WindowMetricsCompat

/**
 * PlatformDetector - Detects hardware platform characteristics for configuration selection.
 *
 * PURPOSE:
 * Identifies Intel x86/x86_64 platforms and GM AAOS devices at runtime to enable
 * platform-specific optimizations. No root access required.
 *
 * DETECTION METHODS:
 * - Build.SUPPORTED_ABIS: Primary CPU architecture detection (x86, x86_64, arm64-v8a, etc.)
 * - Build.MANUFACTURER/PRODUCT/DEVICE: GM AAOS device identification
 * - MediaCodecList: Intel OMX codec detection
 * - AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE: Native audio sample rate
 *
 * USAGE:
 * Call detect(context) during video/audio subsystem initialization. PlatformInfo is
 * NOT cached at this layer — every call re-runs all detection steps. CarlinkManager
 * stores the returned PlatformInfo locally and forwards it to AudioConfig.forPlatform;
 * the cluster platform-switch TODO in CarlinkClusterService also plans to consume
 * PlatformInfo.isGmAaos from here. Re-detection on display-mode reinit is redundant
 * but cheap (Build properties + one MediaCodecList scan).
 *
 * Log output uses android.util.Log directly rather than the project Logger — this
 * class runs before the Logger is fully wired and any failure here should not
 * propagate into the file-log pipeline.
 *
 * Reference: https://developer.android.com/ndk/guides/abis
 */
object PlatformDetector {
    private const val TAG = "CARLINK_PLATFORM"

    /**
     * Platform information data class.
     *
     * @property isIntel True if CPU architecture is x86 or x86_64
     * @property isGmAaos True if device is GM AAOS. Matches on Harman_Samsung manufacturer
     *   OR "gminfo" in product OR device starts with "gminfo". 2024 Silverado gminfo37
     *   reports Build.MANUFACTURER="gm" (confirmed 6 detections across 3 POTATO sessions
     *   2026-04-20: product=full_gminfo37_gb, device=gminfo37). The Harman_Samsung branch
     *   is DEAD CODE on this hardware — kept defensively for hypothetical OEM variants.
     * @property cpuArch Primary CPU ABI (e.g., "arm64-v8a", "x86_64")
     * @property hasIntelCodec True if an Intel video codec is available. Naive
     *   substring match on ".contains("Intel")" in the decoder name — fragile if the
     *   vendor ever renames.
     * @property hardwareH264DecoderName The detected hardware H.264 decoder name (any vendor)
     * @property nativeSampleRate Device's native audio output sample rate in Hz
     * @property manufacturer Device manufacturer string
     * @property product Device product string
     * @property device Device name string
     * @property isBroxton True if Build.BOARD/HARDWARE/PRODUCT contains "broxton".
     *   EMPIRICALLY FALSE on gminfo37 (POTATO 2026-04-20 "Broxton platform: false" despite
     *   gminfo37 being Apollo Lake — GM does not publish the SoC codename at Build level).
     *   Effectively unreachable on all known devices; delete unless a Build property ever
     *   surfaces "broxton".
     * @property displayWidth Native display width in pixels (0 if unknown). CURRENTLY
     *   UNUSED beyond debug logging — MainActivity re-reads WindowMetrics independently
     *   to compute adapter viewArea/safeArea; this copy is redundant.
     * @property displayHeight Native display height in pixels (0 if unknown). See [displayWidth].
     */
    data class PlatformInfo(
        val isIntel: Boolean,
        val isGmAaos: Boolean,
        val cpuArch: String,
        val hasIntelCodec: Boolean,
        val hardwareH264DecoderName: String? = null,
        val nativeSampleRate: Int,
        val manufacturer: String,
        val product: String,
        val device: String,
        val isBroxton: Boolean = false,
        val displayWidth: Int = 0,
        val displayHeight: Int = 0,
    ) {
        /**
         * Returns true if device is the GM gminfo37 (2400x960 display).
         * CURRENTLY UNUSED — reserved for gminfo37-specific tuning that may or may not
         * materialize. Consider deleting if [isGmAaos] + [isBroxton] ever prove
         * sufficient for all cases in the audio/video config paths.
         */
        fun isGmInfo37(): Boolean = device.equals("gminfo37", ignoreCase = true)

        /**
         * Returns true if running on Google's AAOS reference emulator
         * (product=sdk_gcar_arm64 / sdk_gcar_x86 / sdk_gcar_*). Used as a stand-in for
         * gminfo37 during local development — same 3P-app constraints, and the only way
         * to validate gminfo37-specific defaults without the Silverado hardware.
         */
        fun isAaosEmulator(): Boolean = product.startsWith("sdk_gcar", ignoreCase = true)

        /**
         * Composite: device should receive "gminfo37-like" UI defaults
         * (fullscreen-immersive, OEM icon hidden, etc.). True on gminfo37 itself and
         * on the AAOS emulator (for development parity). False elsewhere — preserves
         * existing factory defaults for non-GM 3P targets.
         */
        fun requiresImmersiveDefaults(): Boolean = isGmInfo37() || isAaosEmulator()

        /**
         * Returns true if Intel-specific MediaCodec fixes should be applied.
         * Requires BOTH Intel architecture AND Intel codec presence.
         * ARM-based GM AAOS devices will return false.
         */
        fun requiresIntelMediaCodecFixes(): Boolean = isIntel && hasIntelCodec

        /**
         * Returns true if GM AAOS audio optimizations should be applied.
         * Requires BOTH Intel architecture AND GM AAOS device.
         * ARM-based GM AAOS devices will return false.
         */
        fun requiresGmAaosAudioFixes(): Boolean = isIntel && isGmAaos

        override fun toString(): String =
            "PlatformInfo(arch=$cpuArch, intel=$isIntel, gm=$isGmAaos, " +
                "hwDecoder=${hardwareH264DecoderName ?: "software"}, " +
                "nativeRate=${nativeSampleRate}Hz, mfr=$manufacturer, product=$product, device=$device)"
    }

    /**
     * Detect platform characteristics.
     *
     * @param context Android context for accessing system services
     * @return PlatformInfo with all detected characteristics
     */
    fun detect(context: Context): PlatformInfo {
        val cpuArch = detectCpuArchitecture()
        val isIntel = cpuArch == "x86_64" || cpuArch == "x86"

        val manufacturer = Build.MANUFACTURER ?: ""
        val product = Build.PRODUCT ?: ""
        val device = Build.DEVICE ?: ""
        val board = Build.BOARD ?: ""
        val hardware = Build.HARDWARE ?: ""

        val isGmAaos = detectGmAaos(manufacturer, product, device)
        val (_, hardwareH264DecoderName) = detectHardwareH264Decoder()
        val hasIntelCodec = hardwareH264DecoderName?.contains("Intel", ignoreCase = true) == true
        val nativeSampleRate = detectNativeSampleRate(context)

        // Detect Intel Broxton/Apollo Lake platform (used in gminfo37)
        // Broxton uses Intel Atom x7 (Apollo Lake) with HD Graphics 505
        val isBroxton =
            board.contains("broxton", ignoreCase = true) ||
                hardware.contains("broxton", ignoreCase = true) ||
                product.contains("broxton", ignoreCase = true)

        // Detect display resolution
        val (displayWidth, displayHeight) = detectDisplayResolution(context)

        val info =
            PlatformInfo(
                isIntel = isIntel,
                isGmAaos = isGmAaos,
                cpuArch = cpuArch,
                hasIntelCodec = hasIntelCodec,
                hardwareH264DecoderName = hardwareH264DecoderName,
                nativeSampleRate = nativeSampleRate,
                manufacturer = manufacturer,
                product = product,
                device = device,
                isBroxton = isBroxton,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "[PLATFORM] Detected: $info")
            Log.i(TAG, "[PLATFORM] Hardware H.264 decoder: ${hardwareH264DecoderName ?: "none (software fallback)"}")
            Log.i(TAG, "[PLATFORM] Intel-specific fixes: ${info.requiresIntelMediaCodecFixes()}")
            Log.i(TAG, "[PLATFORM] GM AAOS audio fixes: ${info.requiresGmAaosAudioFixes()}")
            Log.i(TAG, "[PLATFORM] Broxton platform: $isBroxton, Display: ${displayWidth}x$displayHeight")
        }

        return info
    }

    /**
     * Detect display resolution from WindowManager.
     *
     * @param context Android context
     * @return Pair of (width, height) in pixels
     */
    private fun detectDisplayResolution(context: Context): Pair<Int, Int> =
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                // WindowMetricsCompat: currentWindowMetrics on API 30+, getRealMetrics on API 29.
                val bounds = WindowMetricsCompat.displayBounds(windowManager)
                Pair(bounds.width(), bounds.height())
            } else {
                Pair(0, 0)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect display resolution: ${e.message}")
            Pair(0, 0)
        }

    /**
     * Detect primary CPU architecture from Build.SUPPORTED_ABIS.
     *
     * Reference: https://developer.android.com/ndk/guides/abis
     */
    private fun detectCpuArchitecture(): String =
        try {
            Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect CPU architecture: ${e.message}")
            "unknown"
        }

    /**
     * Detect if device is GM AAOS based on manufacturer, product, and device strings.
     *
     * Observed on 2024 Silverado gminfo37 (root README.md):
     * - Manufacturer: "gm"                    (Harman_Samsung branch does NOT fire)
     * - Product: "full_gminfo37_gb"           (matches via contains("gminfo"))
     * - Device: "gminfo37"                    (matches via startsWith("gminfo"))
     *
     * The Harman_Samsung branch is kept defensively for hypothetical OEM variants that
     * expose the underlying Harman/Samsung manufacturer at the Build level.
     */
    private fun detectGmAaos(
        manufacturer: String,
        product: String,
        device: String,
    ): Boolean =
        manufacturer.equals("Harman_Samsung", ignoreCase = true) ||
            product.contains("gminfo", ignoreCase = true) ||
            device.startsWith("gminfo", ignoreCase = true)

    /**
     * Detect the best available hardware H.264 decoder.
     *
     * Uses MediaCodecList.REGULAR_CODECS — deliberately excludes ALL_CODECS, since
     * vendor-specific non-regular decoders are rarely suitable for real-time video
     * playback without additional tuning. If no hardware decoder is surfaced as
     * REGULAR the method returns (false, null) and the app falls back to software.
     *
     * @return Pair of (isHardwareDecoder, codecName)
     */
    private fun detectHardwareH264Decoder(): Pair<Boolean, String?> =
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val hwDecoder =
                codecList.codecInfos.firstOrNull { info ->
                    !info.isEncoder &&
                        info.isHardwareAccelerated &&
                        info.supportedTypes.any { type ->
                            type.equals("video/avc", ignoreCase = true)
                        }
                }
            if (hwDecoder != null) {
                if (BuildConfig.DEBUG) Log.i(TAG, "[PLATFORM] Found hardware H.264 decoder: ${hwDecoder.name}")
                Pair(true, hwDecoder.name)
            } else {
                if (BuildConfig.DEBUG) Log.i(TAG, "[PLATFORM] No hardware H.264 decoder found")
                Pair(false, null)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect hardware codec: ${e.message}")
            Pair(false, null)
        }

    /**
     * Detect native audio output sample rate.
     *
     * Uses AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE to get the optimal output rate.
     * Falls back to 48000 Hz (common automotive rate) if detection fails.
     *
     * Reference: https://developer.android.com/reference/android/media/AudioManager#PROPERTY_OUTPUT_SAMPLE_RATE
     */
    private fun detectNativeSampleRate(context: Context): Int =
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?: DEFAULT_SAMPLE_RATE
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect native sample rate: ${e.message}")
            DEFAULT_SAMPLE_RATE
        }

    private const val DEFAULT_SAMPLE_RATE = 48000
}
