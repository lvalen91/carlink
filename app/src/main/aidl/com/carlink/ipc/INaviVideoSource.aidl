// SPDX-License-Identifier: Apache-2.0
package com.carlink.ipc;

import com.carlink.ipc.INaviVideoSink;

/**
 * Producer of the CPC200 AltVideo (USB MsgType 0x2C) navigation stream.
 *
 * Implemented by zeno.carlink (carlink_native) and exposed via a bound
 * Service. Consumers register sinks; the producer broadcasts demuxed frames
 * to every registered sink.
 *
 * Service ComponentName:
 *   zeno.carlink / zeno.carlink.ipc.NaviVideoSourceService
 *
 * Permission protecting the service:
 *   com.carlink.permission.NAVI_VIDEO_STREAM (signature|privileged)
 */
interface INaviVideoSource {
    /**
     * Add a sink to the broadcast list. The producer immediately calls
     * {@link INaviVideoSink#onStreamConfigured} on the new sink if a stream
     * is already active, so late-binding consumers don't miss the geometry.
     * Subsequent frames are forwarded as they arrive.
     */
    void registerSink(INaviVideoSink sink);

    /**
     * Remove a sink. Idempotent. The producer must also unregister sinks
     * whose hosting process dies (via linkToDeath).
     */
    void unregisterSink(INaviVideoSink sink);

    /**
     * True if the producer is currently receiving 0x2C frames from the
     * adapter. Used by consumers binding mid-session to decide whether to
     * immediately reveal their video overlay.
     */
    boolean isStreamActive();
}
