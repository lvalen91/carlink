// SPDX-License-Identifier: Apache-2.0
package com.carlink.ipc;

/**
 * Consumer of the CPC200 AltVideo (USB MsgType 0x2C) navigation stream.
 *
 * Implemented by the cluster-home app and registered with the producer
 * (zeno.carlink). The producer strips the 16-byte USB header and the 20-byte
 * video header, then pushes raw Annex-B H.264 NAL units via {@link #onFrame}.
 *
 * All methods are oneway: the producer must not block on the consumer.
 */
oneway interface INaviVideoSink {
    /**
     * Pushed once at the start of a 0x2C session (immediately on the first
     * 0x2C frame the producer sees). Triggers the consumer to expose its
     * Surface and prepare a decoder.
     *
     * width, height: from the 20-byte video header (offsets 0x10, 0x14)
     * fps: nominal frame rate the host requested via naviScreenInfo
     */
    void onStreamConfigured(int width, int height, int fps);

    /**
     * One H.264 access unit in Annex-B form (NAL units prefixed with the
     * 00 00 00 01 start code). PTS is the 1 kHz adapter clock from video
     * header offset 0x1C, converted to microseconds.
     *
     * isKeyFrame: true when the access unit contains an IDR (NAL type 5),
     * optionally preceded by SPS (7) / PPS (8). The decoder may drop frames
     * until the first isKeyFrame=true arrives.
     */
    void onFrame(in byte[] h264AnnexB, long ptsUs, boolean isKeyFrame);

    /**
     * Pushed when the 0x2C stream stops: CarPlay disconnect, an explicit
     * RELEASE_NAVI_SCREEN_FOCUS (cmd 509), or a producer-side idle watchdog.
     * The consumer should hide its video overlay and release decoder state.
     */
    void onStreamEnded();
}
