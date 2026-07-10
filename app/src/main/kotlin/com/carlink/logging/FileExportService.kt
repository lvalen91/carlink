package com.carlink.logging

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Stateless utility for file export operations using Android's Storage Access Framework (SAF).
 *
 * This object provides pure I/O functions for reading files and writing to URIs.
 * It is designed to work with Compose's `rememberLauncherForActivityResult` pattern
 * where the Composable handles lifecycle-aware launcher registration and this service
 * handles the actual I/O operations.
 *
 * Usage in Compose:
 * ```kotlin
 * val launcher = rememberLauncherForActivityResult(
 *     contract = ActivityResultContracts.CreateDocument("text/plain")
 * ) { uri ->
 *     uri?.let {
 *         scope.launch {
 *             val result = FileExportService.writeFileToUri(context, it, file)
 *             result.onSuccess { bytes -> showSuccess("Exported $bytes bytes") }
 *             result.onFailure { error -> showError(error.message) }
 *         }
 *     }
 * }
 * ```
 *
 * Design rationale:
 * - Stateless: No mutable state. (Two concurrent calls with the same Uri can still race
 *   at the SAF document-provider level — don't launch two exports of the same target.)
 * - Suspend functions: All I/O runs on Dispatchers.IO per Android best practices.
 * - Result type: Explicit error handling for the expected I/O failure modes
 *   (IOException, SecurityException). CancellationException propagates normally per
 *   Kotlin structured concurrency; OOM / other RuntimeExceptions are not caught.
 * - Separation of concerns: Compose handles lifecycle, this handles I/O.
 * - Streaming: Large files (>LARGE_FILE_THRESHOLD) are copied in 8 KiB chunks. Smaller
 *   files are read whole via File.readBytes() — a 1 MiB single allocation at the
 *   threshold boundary, which is acceptable for log-file export but not for arbitrary
 *   payloads.
 *
 * @see <a href="https://developer.android.com/training/data-storage/shared/documents-files">SAF Documentation</a>
 */
object FileExportService {
    private const val TAG = Logger.Tags.FILE_LOG

    // Chunk size for the streaming path. 8 KiB matches the default used by
    // Okio/BufferedInputStream — larger values yield diminishing returns for SAF writes.
    private const val STREAM_BUFFER_SIZE = 8192

    // Empirically chosen for the LogsTab export flow. Rotated log files are typically
    // under 1 MiB; exports above this size use streaming instead of a whole-file
    // ByteArray allocation. Adjust if this service is ever used for larger payloads.
    private const val LARGE_FILE_THRESHOLD = 1024 * 1024L

    /**
     * Writes file contents to the specified URI.
     *
     * For files larger than LARGE_FILE_THRESHOLD (1 MiB), uses streaming copy. For
     * smaller files, reads into memory via File.readBytes() and delegates to
     * writeBytesToUri. The small-file path incurs a nested withContext(Dispatchers.IO)
     * inside the outer one — harmless (same-dispatcher calls are collapsed) but kept
     * so writeBytesToUri remains a safe public entry point on its own.
     *
     * @param context Android context for ContentResolver access
     * @param uri Destination URI from SAF document picker
     * @param file Source file to export
     * @return Result containing bytes written on success, or exception on failure
     */
    suspend fun writeFileToUri(
        context: Context,
        uri: Uri,
        file: File,
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                // Check file exists before attempting export
                if (!file.exists()) {
                    val error = IOException("Source file does not exist: ${file.name}")
                    logError("[FILE_EXPORT] ${error.message}", tag = TAG)
                    return@withContext Result.failure(error)
                }

                val fileSize = file.length()
                logInfo("[FILE_EXPORT] Starting export of ${file.name} ($fileSize bytes)", tag = TAG)

                // Use streaming for large files to avoid memory pressure
                if (fileSize > LARGE_FILE_THRESHOLD) {
                    streamFileToUri(context, uri, file)
                } else {
                    // Small file - read into memory
                    val bytes = file.readBytes()
                    writeBytesToUri(context, uri, bytes, file.name).map { it.toLong() }
                }
            } catch (e: IOException) {
                logError("[FILE_EXPORT] Failed to read file ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            } catch (e: SecurityException) {
                logError("[FILE_EXPORT] Permission denied reading ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            }
        }

    /**
     * Streams file contents to the specified URI using buffered copy.
     * Avoids loading entire file into memory - suitable for large files.
     *
     * Known gap: outputStream is opened before the inputStream's use {} block. If
     * file.inputStream() throws (file deleted after the exists() check, permission
     * change, etc.) the outputStream is caught by the IOException handler but is not
     * closed — it leaks until its ContentResolver-side client is GC'd. Low-risk for
     * the current log-export flow but worth fixing by inverting the use {} nesting
     * (outputStream.use { file.inputStream().use { ... } }).
     *
     * @param context Android context for ContentResolver access
     * @param uri Destination URI from SAF document picker
     * @param file Source file to stream
     * @return Result containing bytes written on success, or exception on failure
     */
    private suspend fun streamFileToUri(
        context: Context,
        uri: Uri,
        file: File,
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    val error = IOException("Failed to open output stream for URI: $uri")
                    logError("[FILE_EXPORT] ${error.message}", tag = TAG)
                    return@withContext Result.failure(error)
                }

                var totalBytes = 0L
                file.inputStream().use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                        output.flush()
                    }
                }

                logInfo("[FILE_EXPORT] Successfully streamed $totalBytes bytes (${file.name})", tag = TAG)
                Result.success(totalBytes)
            } catch (e: IOException) {
                logError("[FILE_EXPORT] Stream copy failed for ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            } catch (e: SecurityException) {
                logError(
                    "[FILE_EXPORT] Permission denied streaming ${file.name}: ${e.message}",
                    tag = TAG,
                    throwable = e,
                )
                Result.failure(e)
            }
        }

    /**
     * Writes byte array to the specified URI.
     *
     * Uses ContentResolver.openOutputStream with proper resource management via Kotlin's use().
     * The output stream is flushed before closing to ensure all data is written.
     *
     * @param context Android context for ContentResolver access
     * @param uri Destination URI from SAF document picker
     * @param bytes Data to write
     * @param fileName Optional filename for logging (defaults to "data")
     * @return Result containing bytes written on success, or exception on failure
     */
    suspend fun writeBytesToUri(
        context: Context,
        uri: Uri,
        bytes: ByteArray,
        fileName: String = "data",
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    val error = IOException("Failed to open output stream for URI: $uri")
                    logError("[FILE_EXPORT] ${error.message}", tag = TAG)
                    return@withContext Result.failure(error)
                }

                outputStream.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }

                logInfo("[FILE_EXPORT] Successfully wrote ${bytes.size} bytes ($fileName)", tag = TAG)
                Result.success(bytes.size)
            } catch (e: IOException) {
                logError("[FILE_EXPORT] Write failed for $fileName: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            } catch (e: SecurityException) {
                logError("[FILE_EXPORT] Permission denied writing $fileName: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            }
        }
}
