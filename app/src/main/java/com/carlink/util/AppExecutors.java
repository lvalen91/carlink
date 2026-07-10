package com.carlink.util;

import android.os.Process;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Centralized background executor for H.264 {@code MediaCodec} work during Android
 * Auto / CarPlay projection with the CPC200-CCPA adapter.
 *
 * Currently owns a single pool, {@link #mediaCodec1()}, used by {@code H264Renderer}
 * for off-main-thread codec resets and reactive keyframe-request callbacks.
 *
 * Pool sizing is parametric on {@code Runtime.getRuntime().availableProcessors()}:
 * core = {@code max(1, cores/2)}, max = {@code cores}. Threads run at
 * {@code THREAD_PRIORITY_DISPLAY} for low-latency video. Defaults are chosen with
 * the GM IOK target (Intel Atom x7 / Apollo Lake, quad-core, ~6GB RAM) in mind, but
 * nothing here probes or pins to that hardware.
 */
public class AppExecutors
{
    /**
     * {@link ThreadPoolExecutor} wrapper that applies an Android thread priority once
     * per worker thread. {@code Process.setThreadPriority} must run on the target
     * thread, so it's invoked from the task wrapper; a {@link ThreadLocal} flag avoids
     * repeating the syscall on every subsequent task that lands on the same worker.
     */
    private static class OptimizedMediaCodecExecutor implements Executor {
        private final ThreadPoolExecutor executor;
        private final int androidPriority;

        // Track which threads have had priority set (thread-local for efficiency)
        private final ThreadLocal<Boolean> prioritySet = ThreadLocal.withInitial(() -> false);

        private OptimizedMediaCodecExecutor(String executorName, int androidPriority) {
            int numberOfCores = Runtime.getRuntime().availableProcessors();

            this.executor = new ThreadPoolExecutor(
                    Math.max(1, numberOfCores / 2), // corePoolSize: half the cores (min 1)
                    numberOfCores,                  // maximumPoolSize: scale up to all cores under load
                    60L,                            // keepAliveTime
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(128),
                    r -> new Thread(r, executorName)
            );
            this.executor.allowCoreThreadTimeOut(true); // let idle core threads die back
            this.androidPriority = androidPriority;
        }

        @Override
        public void execute(@NonNull Runnable command) {
            executor.execute(() -> {
                if (!prioritySet.get()) {
                    Process.setThreadPriority(androidPriority);
                    prioritySet.set(true);
                }
                command.run();
            });
        }

        public void shutdown() {
            executor.shutdown();
        }
    }

    private final OptimizedMediaCodecExecutor mediaCodec1;

    public AppExecutors()
    {
        mediaCodec1 = new OptimizedMediaCodecExecutor("MediaCodec-Input", Process.THREAD_PRIORITY_DISPLAY);
    }

    public Executor mediaCodec1() {
        return mediaCodec1;
    }

    /**
     * Shut down the underlying pool. Intended to be called from a teardown path to
     * release worker threads; currently no caller invokes this, so the pool lives
     * for the process lifetime.
     */
    public void shutdown() {
        try {
            mediaCodec1.shutdown();
        } catch (Exception e) {
            android.util.Log.w("AppExecutors", "Error during shutdown: " + e.getMessage());
        }
    }
}
