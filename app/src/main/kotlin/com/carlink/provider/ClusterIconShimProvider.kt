package com.carlink.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Shim ContentProvider that claims the orphaned authority
 * `cc.funkemunky.carlink.ClusterIconContentProvider`.
 *
 * GM's Templates Host has this provider class but never registers it in its manifest.
 * When Templates Host converts CarIcon maneuver icons into navstate2 protobuf, it calls
 * contentResolver.insert() against this authority. The first failure sets `skipIcons = true`
 * permanently, disabling all icon delivery for the session.
 *
 * This shim implements the 3-method contract Templates Host expects:
 * - insert(): cache PNG bytes keyed by iconId
 * - query(): return contentUri + aspectRatio metadata
 * - openFile(): serve cached PNG via pipe (with optional scaling)
 *
 * Security posture: [AndroidManifest.xml] declares this provider exported=true with
 * grantUriPermissions=true because Templates Host runs in a different UID. The
 * authority string is not secret, but cached content is limited to maneuver-icon
 * PNGs (adapter-forwarded) and external callers can only read what Templates Host
 * has already inserted. delete/update are no-ops.
 *
 * Process scope: ContentProvider is a per-process singleton, so [iconCache] is
 * effectively process-global. Cache resets on app process death; Templates Host
 * then re-inserts on next icon use.
 */
class ClusterIconShimProvider : ContentProvider() {
    companion object {
        private const val TAG = Logger.Tags.ICON_SHIM
        // Per-flavor (BuildConfig.CLUSTER_ICON_AUTHORITY, see app/build.gradle.kts).
        // Must mirror the manifest's android:authorities so URI building stays
        // consistent with the registered provider authority for this variant.
        private val AUTHORITY: String = BuildConfig.CLUSTER_ICON_AUTHORITY
        // Bounded to cap memory: maneuver-icon diversity per trip is small, so 20
        // entries is enough to cover a full drive without re-encoding repeats.
        // At ~10-40 KiB per PNG this caps peak footprint at ~200-800 KiB.
        private const val MAX_CACHE_SIZE = 20
    }

    // access-order LinkedHashMap (third arg=true) wrapped in synchronizedMap: reads AND
    // writes acquire the intrinsic lock, and the eviction callback runs UNDER that lock.
    // Don't iterate iconCache directly elsewhere without wrapping in synchronized(iconCache);
    // and keep the Logger.d in removeEldestEntry cheap — it runs while the lock is held.
    private val iconCache: MutableMap<String, ByteArray> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                    val shouldRemove = size > MAX_CACHE_SIZE
                    if (shouldRemove && eldest != null) {
                        Logger.d("Evicted ${eldest.key} from icon cache (LRU)", tag = TAG)
                    }
                    return shouldRemove
                }
            },
        )

    override fun onCreate(): Boolean {
        Logger.i("ClusterIconShimProvider registered (authority=$AUTHORITY)", tag = TAG)
        return true
    }

    // CONTRACT: insert() must NEVER return null. A null return triggers Templates Host's
    // internal `skipIcons = true` latch for the session, disabling all cluster icons
    // until the process restarts. Even error paths return a non-null sentinel URI.
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        if (values == null) {
            Logger.w("insert() called with null ContentValues", tag = TAG)
            return "content://$AUTHORITY/img/empty".toUri()
        }

        val iconId = values.getAsString("iconId")
        val data = values.getAsByteArray("data")

        if (iconId == null || data == null) {
            Logger.w("insert() missing iconId or data (iconId=$iconId, dataSize=${data?.size})", tag = TAG)
            return "content://$AUTHORITY/img/unknown".toUri()
        }

        val cacheKey = "cluster_icon_$iconId"
        iconCache[cacheKey] = data
        val resultUri = "content://$AUTHORITY/img/$cacheKey".toUri()
        Logger.d("insert() cached $cacheKey (${data.size} bytes) → $resultUri", tag = TAG)
        return resultUri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = arrayOf("contentUri", "aspectRatio")
        val cursor = MatrixCursor(columns)

        if (selection == null) {
            Logger.d("query() with null selection — returning empty cursor", tag = TAG)
            return cursor
        }

        val cacheKey = "cluster_icon_$selection"
        val data = iconCache[cacheKey]
        if (data == null) {
            Logger.d("query() cache miss for $cacheKey", tag = TAG)
            return cursor
        }

        // Decode dimensions without allocating the full bitmap
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val aspectRatio =
            if (opts.outHeight > 0) {
                opts.outWidth.toDouble() / opts.outHeight.toDouble()
            } else {
                1.0
            }

        val contentUri = "content://$AUTHORITY/img/$cacheKey"
        cursor.addRow(arrayOf<Any>(contentUri, aspectRatio))
        Logger.d(
            "query() hit for $cacheKey (${opts.outWidth}x${opts.outHeight}, ar=${"%.2f".format(aspectRatio)})",
            tag = TAG,
        )
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val cacheKey = uri.lastPathSegment
        if (cacheKey == null) {
            Logger.w("openFile() with null lastPathSegment", tag = TAG)
            return null
        }

        val data = iconCache[cacheKey]
        if (data == null) {
            Logger.w("openFile() cache miss for $cacheKey", tag = TAG)
            return null
        }

        // Check for scaling parameters
        val targetW = uri.getQueryParameter("w")?.toIntOrNull()
        val targetH = uri.getQueryParameter("h")?.toIntOrNull()
        val outputData =
            if (targetW != null && targetH != null && targetW > 0 && targetH > 0) {
                scaleIcon(data, targetW, targetH)
            } else {
                data
            }

        Logger.d("openFile() serving $cacheKey (${outputData.size} bytes, scale=${targetW}x$targetH)", tag = TAG)

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        // One thread per openFile call (not pooled). Simpler than ThreadPoolExecutor
        // lifecycle for a provider whose caller cadence is a handful per minute during
        // nav. Swap for a pooled executor if this becomes a hot path.
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { os ->
                    os.write(outputData)
                }
            } catch (e: Exception) {
                Logger.e("openFile() pipe write error for $cacheKey: ${e.message}", tag = TAG)
            }
        }, "IconShim-Pipe").start()

        return readEnd
    }

    private fun scaleIcon(
        pngData: ByteArray,
        w: Int,
        h: Int,
    ): ByteArray {
        return try {
            val original = BitmapFactory.decodeByteArray(pngData, 0, pngData.size) ?: return pngData
            val scaled = original.scale(w, h, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            // Silent fallback to unscaled original: decode failure or scale OOM on
            // anomalous PNG bytes. Logged under ICON_SHIM rather than PROTO_UNKNOWN;
            // upgrade the tag if malformed adapter PNGs become a forensics target.
            Logger.w("scaleIcon() failed (${w}x$h): ${e.message} — using original", tag = TAG)
            pngData
        }
    }

    // No-ops: Templates Host never calls delete/update on this authority. Returning 0
    // is the ContentProvider convention for "unsupported / no rows affected".
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    // Cached content is always PNG: adapter NAVI_IMAGE (subtype 201) ships PNG bytes
    // and scaleIcon re-encodes as PNG. Change this if a future code path ever caches
    // a different image format under the same authority.
    override fun getType(uri: Uri): String = "image/png"
}
