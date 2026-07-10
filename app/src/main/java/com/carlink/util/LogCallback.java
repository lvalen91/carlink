package com.carlink.util;

/**
 * Logging seam between Java/Kotlin platform components and the app's centralized Logger.
 *
 * Consumers (e.g. {@code H264Renderer}, {@code DualStreamAudioManager},
 * {@code MicrophoneCaptureManager}, {@code MediaSessionManager}) take a {@code LogCallback}
 * via their constructor so they don't depend on the Kotlin {@code Logger} singleton directly.
 * The single producer is {@code CarlinkManager}, which supplies an implementation that routes
 * to {@code Logger} and applies tag/debug gating for {@link #logPerf}.
 *
 * Usage example:
 * <pre>
 * LogCallback callback = message -> Log.d(TAG, message);
 * H264Renderer renderer = new H264Renderer(width, height, surface, callback, executors, decoderName);
 * </pre>
 */
public interface LogCallback {

    void log(String message);

    /**
     * Log with an explicit tag. The default implementation prepends {@code "[tag] "} and
     * forwards to {@link #log(String)}; implementations may instead route the tag to a
     * structured logger.
     */
    default void log(String tag, String message) { log("[" + tag + "] " + message); }

    /**
     * Performance/diagnostic log. The default implementation forwards unconditionally to
     * {@link #log(String, String)}; implementations SHOULD gate emission on debug-logging
     * and per-tag enablement (as {@code CarlinkManager} does in release builds).
     */
    default void logPerf(String tag, String message) { log(tag, message); }
}
