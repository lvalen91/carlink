package com.carlink.navigation.compose

import android.content.Context
import android.graphics.Bitmap
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.navigation.Iap2ManeuverData
import java.io.File
import java.io.FileOutputStream

/**
 * Validation/debug pathway: writes each composed maneuver bitmap to a per-trip directory
 * as a PNG with a descriptive filename, so the icons can be reviewed offline (e.g. via
 * `adb pull` to a Mac and visual eyeball-check vs Apple Maps screenshots).
 *
 * Wires into [ComposedIconStore.debugSink]. Enable from [com.carlink.CarlinkManager.init]
 * by calling [enable] with the app's context (uses [Context.getExternalFilesDir] for the
 * output directory — externally readable, no special permissions required on Android 10+).
 *
 * Each populate cycle starts a fresh subdirectory named with the timestamp + first-maneuver
 * road name, so multiple test runs don't overwrite each other.
 *
 * Filename convention:
 *   <idx>_<cpType>_<road>_<exit>.png
 * Example:
 *   /sdcard/Android/data/zeno.carlink/files/composer_test/20260526_051600_StartOnArborDr/
 *     00_11_StartOnArborDr_.png
 *     10_29_EMainSt_exit2.png
 *
 * Cluster behavior is unaffected by whether the dumper is enabled — it's a pure side
 * effect on top of the compose pipeline.
 */
object ManeuverIconDebugDumper {
    /** Set by [enable]; if null, [onIconComposed] is a no-op. */
    @Volatile
    private var baseDir: File? = null

    /** Created lazily on first composed icon per population. Reset by [resetSession]. */
    @Volatile
    private var sessionDir: File? = null

    /**
     * Enable the dumper. Outputs go under `<externalFilesDir>/composer_test/`.
     * Connects to [ComposedIconStore.debugSink].
     */
    fun enable(context: Context) {
        // Use INTERNAL storage (getFilesDir) so adb run-as zeno.carlink can read the PNGs.
        // External storage (/storage/emulated/.../Android/data/) is locked down on Android 14+
        // even from `adb pull` due to scoped-storage enforcement. Internal files are
        // accessible to `adb shell run-as <pkg>` for the developer's own debuggable builds.
        val dir = File(context.filesDir, "composer_test")
        if (!dir.exists() && !dir.mkdirs()) {
            logWarn("[COMPOSER_DUMP] Failed to create $dir", tag = Logger.Tags.NAVI)
            return
        }
        baseDir = dir
        sessionDir = null
        ComposedIconStore.debugSink = ::onIconComposed
        logInfo("[COMPOSER_DUMP] enabled, output dir = ${dir.absolutePath}", tag = Logger.Tags.NAVI)
    }

    fun disable() {
        ComposedIconStore.debugSink = null
        baseDir = null
        sessionDir = null
    }

    /** Force-start a new session subdirectory on the next composed-icon call. */
    fun resetSession() {
        sessionDir = null
    }

    private fun onIconComposed(maneuver: Iap2ManeuverData, bmp: Bitmap) {
        val base = baseDir ?: return
        val dir = sessionDir ?: run {
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val firstRoad = maneuver.instructionText.take(32).replace(Regex("[^A-Za-z0-9]"), "")
            val d = File(base, "${ts}_$firstRoad")
            d.mkdirs()
            sessionDir = d
            logInfo("[COMPOSER_DUMP] New session dir: ${d.absolutePath}", tag = Logger.Tags.NAVI)
            d
        }
        val safeRoad = maneuver.postManeuverRoadName.take(24).replace(Regex("[^A-Za-z0-9]"), "")
        val exitTag = if (maneuver.cpManeuverType in 28..46) "_exit${maneuver.cpManeuverType - 27}" else ""
        val filename = "%02d_%02d_%s%s.png".format(
            maneuver.index,
            maneuver.cpManeuverType,
            safeRoad,
            exitTag,
        )
        val file = File(dir, filename)
        try {
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Throwable) {
            logWarn("[COMPOSER_DUMP] write failed for $file: ${e.message}", tag = Logger.Tags.NAVI)
        }
    }
}
