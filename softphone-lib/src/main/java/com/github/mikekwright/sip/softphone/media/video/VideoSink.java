package com.github.mikekwright.sip.softphone.media.video;

/**
 * Consumer of received, reassembled video frames. Called on the network event loop: implementations must be
 * fast and hand frames to a decoder thread if decoding is expensive.
 */
@FunctionalInterface
public interface VideoSink {

    /** A complete frame was received. */
    void onFrame(EncodedVideoFrame frame);
}
