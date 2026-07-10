package com.carlink.util

import android.content.Context
import com.carlink.logging.logWarn
import java.io.IOException

/**
 * Utility for loading icon assets for adapter initialization.
 *
 * Icons are uploaded to the adapter's own filesystem at /etc/icon_*.png during FULL
 * init via [FileAddress.ICON_120/180/256] (see MessageSerializer.addFullSettings).
 * The adapter firmware consumes them as OEM/brand-logo assets for its AirPlay
 * configuration (LogoType=1, CustomCarLogo=1 per config_key_analysis.md). They are
 * NOT used by iOS/Android device-picker UIs on the phone side — that is the
 * adapter's own identity it advertises.
 *
 * Three sizes match standard iOS app-icon raster scales: 120=60@2x, 180=60@3x,
 * 256 is the marketing master. The adapter firmware picks the size that matches
 * its display density; shipping all three covers the known head-unit variants.
 *
 * Missing-asset contract: any file that fails to load returns null; the
 * MessageSerializer serializes only the icons present (`?.let { serializeFile(...) }`),
 * so partial coverage silently uploads what we have. No user-visible error.
 *
 * Filename-case note: the APK asset names use uppercase ICON_NNNxNNN.png while
 * FileAddress.ICON_* paths use lowercase icon_NNNxNNN.png. The serializer writes
 * to the FileAddress path, so the on-adapter filename is always lowercase
 * regardless of the APK casing.
 */
object IconAssets {
    private const val ICON_120_ASSET = "ICON_120x120.png"
    private const val ICON_180_ASSET = "ICON_180x180.png"
    private const val ICON_256_ASSET = "ICON_256x256.png"

    /**
     * Load icon data from assets folder.
     *
     * Called from MainActivity.initializeCarlinkManager on the main thread via
     * window.decorView.post { }. Total read size ~20 KiB across three APK-bundled
     * PNGs; negligible blocking in practice, but technically main-thread I/O.
     * Fire-and-forget trade-off: keeps startup sequencing simple.
     *
     * Triple-return limitation: if a fourth size or the separate OEM_ICON is ever
     * wired through here, the return type has to change. Consider a data class
     * (e.g. `IconSet(icon120, icon180, icon256, oem=null)`) if the icon surface grows.
     *
     * @param context Android context for asset access
     * @return Triple of (icon120, icon180, icon256) byte arrays, or nulls if loading fails
     */
    fun loadIcons(context: Context): Triple<ByteArray?, ByteArray?, ByteArray?> {
        val icon120 = loadAsset(context, ICON_120_ASSET)
        val icon180 = loadAsset(context, ICON_180_ASSET)
        val icon256 = loadAsset(context, ICON_256_ASSET)
        return Triple(icon120, icon180, icon256)
    }

    // Emits logWarn WITHOUT a tag = argument — goes to the default/unfiltered pool
    // rather than a subsystem tag like Logger.Tags.CONFIG. Inconsistent with the
    // project convention of explicit tags; preserved here to avoid changing behavior
    // but worth aligning to `tag = Logger.Tags.CONFIG` next time this file is touched.
    private fun loadAsset(
        context: Context,
        fileName: String,
    ): ByteArray? =
        try {
            context.assets.open(fileName).use { it.readBytes() }
        } catch (e: IOException) {
            logWarn("[IconAssets] Failed to load asset '$fileName': ${e.message}")
            null
        }
}
