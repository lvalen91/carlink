package com.carlink.logging

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent log storage with size-based rotation and async writes.
 *
 * - Rotation: ~5 MiB soft cap. The size check is post-write, so a single flush batch
 *   can push the file over the threshold before rotation triggers — expect occasional
 *   files up to a few MiB above MAX_FILE_SIZE. Intentional: the alternative is
 *   pre-computing batch size or splitting writes, neither worth the complexity.
 * - Retention: 7 days, enforced only at enable() via cleanOldLogs(). In a long-running
 *   session (>24 h without a disable/enable cycle) retention is NOT re-applied — old
 *   files will accumulate until the next enable() call. See cleanOldLogs() for the
 *   scheduling caveat.
 * - Writes: 1 Hz async flush from a single-threaded ScheduledExecutorService;
 *   producers (any thread) enqueue via onLog() with O(1) backpressure.
 */
// Singleton stores appContext (applicationContext), not an Activity, so the
// static-field-leak rule's concern doesn't apply here.
@Suppress("StaticFieldLeak")
class FileLogManager internal constructor(
    context: Context,
    private val sessionPrefix: String = "carlink",
    private val appVersion: String = "1.0.0",
) : Logger.LogListener {
    private val appContext: Context = context.applicationContext

    companion object {
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5MB
        private const val RETENTION_DAYS = 7
        private const val LOGS_DIR = "logs"
        private const val FLUSH_INTERVAL_MS = 1000L
        private const val SHUTDOWN_TIMEOUT_MS = 2000L

        // Producer-side backpressure: drop messages when queue exceeds this size.
        // Prevents unbounded memory growth under DEBUG/VIDEO_PIPELINE presets.
        // Footprint estimate: typical line is 80-150 bytes, worst case ~350 bytes
        // (long tag + long message), so 5000 entries is ~400 KiB typical, ~1.7 MiB
        // absolute worst case. Uses O(1) AtomicInteger check instead of
        // ConcurrentLinkedQueue.size() which is O(n).
        private const val MAX_QUEUE_SIZE = 5000

        @Volatile
        private var instance: FileLogManager? = null

        fun getInstance(context: Context): FileLogManager =
            instance ?: synchronized(this) {
                instance ?: FileLogManager(context.applicationContext).also { instance = it }
            }
    }

    private val logsDir: File
    private var currentLogFile: File? = null
    private var currentWriter: OutputStreamWriter? = null
    private val sessionId: String

    // Lock for thread-safe writer access
    private val writerLock = ReentrantLock()

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val queueSize = AtomicInteger(0)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val isEnabled = AtomicBoolean(false)
    private val lastDropWarningMs = AtomicLong(0L)
    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
            .withZone(ZoneId.systemDefault())
    private val fileDateFormat: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .withZone(ZoneId.systemDefault())

    init {
        logsDir =
            File(appContext.filesDir, LOGS_DIR).apply {
                if (!exists()) mkdirs()
            }

        sessionId = "${sessionPrefix}_${fileDateFormat.format(Instant.now())}"

        executor.scheduleAtFixedRate(
            { flushQueueInternal() },
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun enable() {
        if (isEnabled.getAndSet(true)) {
            return
        }

        writerLock.withLock {
            createNewLogFileLocked()
            writeHeaderLocked()
        }
        Logger.addListener(this)
        cleanOldLogs()

        android.util.Log.i("CARLINK", "[FILE_LOG] Enabled — file: ${currentLogFile?.name}")
        logInfo("File logging enabled: ${currentLogFile?.name}", tag = Logger.Tags.FILE_LOG)
    }

    fun disable() {
        if (!isEnabled.getAndSet(false)) {
            return
        }

        Logger.removeListener(this)
        flushQueueInternal()
        writerLock.withLock {
            closeWriterLocked()
        }

        android.util.Log.i("CARLINK", "[FILE_LOG] Disabled — writer closed")
        logInfo("File logging disabled", tag = Logger.Tags.FILE_LOG)
    }

    fun release() {
        android.util.Log.i("CARLINK", "[FILE_LOG] Releasing — shutting down executor")
        // Window between disable() returning and executor.shutdown() running: the 1 Hz
        // scheduled task may still fire once. Safe — isEnabled is false (early return in
        // flushQueueInternal) and currentWriter is null (post-disable). No action needed.
        disable()
        executor.shutdown()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
                android.util.Log.w("CARLINK", "[FILE_LOG] Executor did not terminate gracefully")
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun getLogFiles(): List<File> =
        logsDir
            .listFiles { file ->
                file.isFile && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deleteLogFile(file: File): Boolean =
        writerLock.withLock {
            try {
                if (file.absolutePath == currentLogFile?.absolutePath) {
                    closeWriterLocked()
                    val deleted = file.delete()
                    createNewLogFileLocked()
                    writeHeaderLocked()
                    deleted
                } else {
                    file.delete()
                }
            } catch (e: IOException) {
                logError("Failed to delete log file: ${e.message}", tag = Logger.Tags.FILE_LOG)
                false
            }
        }

    fun getTotalLogSize(): Long = getLogFiles().sumOf { it.length() }

    fun getCurrentLogFileSize(): Long = writerLock.withLock { currentLogFile?.length() ?: 0L }

    /**
     * Flushes all pending log entries to disk.
     * Call this before exporting a log file to ensure all data is written.
     * This is a blocking call that waits for the flush to complete.
     */
    fun flush() {
        flushQueueInternal()
    }

    // Called synchronously by Logger.log() on the caller's thread — can be ANY thread
    // (main, video decoder, audio, USB I/O, etc.). The ConcurrentLinkedQueue +
    // AtomicInteger pair handles the multi-producer case; the single flush executor is
    // the sole consumer.
    override fun onLog(
        level: Logger.Level,
        tag: String?,
        message: String,
        timestamp: Long,
    ) {
        if (!isEnabled.get()) return
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            // Throttled warning: at most once per 5s
            val now = System.currentTimeMillis()
            val last = lastDropWarningMs.get()
            if (now - last >= 5000L && lastDropWarningMs.compareAndSet(last, now)) {
                android.util.Log.w("CARLINK", "[FILE_LOG] Queue full ($MAX_QUEUE_SIZE) — dropping log messages")
            }
            return
        }

        val formattedTime = dateFormat.format(Instant.ofEpochMilli(timestamp))
        val levelStr = level.name.first()
        val tagStr = tag?.let { "[$it] " } ?: ""
        val line = "$formattedTime $levelStr $tagStr$message\n"

        logQueue.offer(line)
        queueSize.incrementAndGet()
    }

    // Internal flush called by executor - uses lock for writer access.
    // Drains the entire queue each cycle (no cap). The previous 100-entry cap
    // caused unbounded queue growth under VIDEO_PIPELINE/DEBUG presets that
    // produce >100 msg/sec. Producer-side MAX_QUEUE_SIZE backpressure in onLog()
    // prevents runaway memory growth from logging bugs.
    private fun flushQueueInternal() {
        if (!isEnabled.get() && logQueue.isEmpty()) return

        writerLock.withLock {
            val writer = currentWriter ?: return

            try {
                val batch = StringBuilder()
                var count = 0

                while (true) {
                    val line = logQueue.poll() ?: break
                    batch.append(line)
                    count++
                }

                if (count > 0) {
                    queueSize.addAndGet(-count)
                    writer.write(batch.toString())
                    writer.flush()

                    currentLogFile?.let { file ->
                        // Post-write size check — the batch that triggers rotation is
                        // already on disk, so files can exceed MAX_FILE_SIZE by up to one
                        // batch (~400 KiB typical, up to ~1.7 MiB in a worst-case burst
                        // that drained a full queue). Accepted as the simpler design.
                        if (file.length() > MAX_FILE_SIZE) {
                            closeWriterLocked()
                            createNewLogFileLocked()
                            writeHeaderLocked()
                            android.util.Log.i("CARLINK", "[FILE_LOG] Rotated — new file: ${currentLogFile?.name}")
                        }
                    }
                }
            } catch (e: IOException) {
                android.util.Log.e("CARLINK", "[FILE_LOG] Failed to flush log queue: ${e.message}")
            }
        }
    }

    // Must be called with writerLock held
    private fun createNewLogFileLocked() {
        closeWriterLocked()

        val fileName = "${sessionId}_${System.currentTimeMillis()}.log"
        currentLogFile = File(logsDir, fileName)

        try {
            currentWriter =
                OutputStreamWriter(
                    FileOutputStream(currentLogFile, true),
                    Charsets.UTF_8,
                )
        } catch (e: IOException) {
            // On open failure the manager degrades to a silent drop-all state:
            // flushQueueInternal returns at the `currentWriter ?: return` guard and
            // queued lines are lost. Intentional — we never want logging failures to
            // break the app. Recovery requires a disable()/enable() cycle.
            logError("Failed to create log file: ${e.message}", tag = Logger.Tags.FILE_LOG)
            currentLogFile = null
        }
    }

    // Must be called with writerLock held
    private fun writeHeaderLocked() {
        val header =
            buildString {
                appendLine("=".repeat(60))
                appendLine("Carlink Native Log Session")
                appendLine("Session ID: $sessionId")
                appendLine("App Version: $appVersion")
                appendLine("Started: ${dateFormat.format(Instant.now())}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("=".repeat(60))
                appendLine()
            }

        try {
            currentWriter?.write(header)
            currentWriter?.flush()
        } catch (e: IOException) {
            logError("Failed to write log header: ${e.message}", tag = Logger.Tags.FILE_LOG)
        }
    }

    // Must be called with writerLock held
    private fun closeWriterLocked() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: IOException) {
            android.util.Log.w("CARLINK", "[FILE_LOG] Writer close error: ${e.message}")
        }
        currentWriter = null
    }

    // Invoked only from enable() — retention is NOT re-applied during a long-running
    // session. If the app stays enabled for >24 h without a disable/enable cycle,
    // files older than RETENTION_DAYS will persist until the next enable() call.
    // Fix if it matters: schedule this in the executor (e.g., every N flushes).
    private fun cleanOldLogs() {
        val cutoffTime = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)

        getLogFiles()
            .filter { it.lastModified() < cutoffTime }
            .forEach { file ->
                try {
                    // Don't delete current file even if old
                    if (file.absolutePath != currentLogFile?.absolutePath) {
                        file.delete()
                        logInfo("Deleted old log file: ${file.name}", tag = Logger.Tags.FILE_LOG)
                    }
                } catch (e: IOException) {
                    logError("Failed to delete old log: ${e.message}", tag = Logger.Tags.FILE_LOG)
                }
            }
    }
}
