package com.github.mikekwright.sip.softphone.media.video;

import com.github.mikekwright.sip.softphone.rtp.RtpPacket;

/**
 * Reassembles encoded video frames from RTP packets of one source.
 *
 * <p>Packets are expected in sequence order (put a jitter buffer in front to undo network reordering). When a
 * sequence gap or an incomplete frame is detected, the partial frame is discarded and the depacketizer waits for
 * the next key frame, dropping dependent frames meanwhile; {@link #consumeKeyFrameNeeded()} then reports that a
 * key frame should be requested from the sender (RTCP PLI, RFC 4585 §6.3.1, or FIR, RFC 5104 §4.3.1).</p>
 *
 * <p>Thread-safety: implementations are stateful and not thread-safe; confine an instance to one thread.</p>
 */
public interface VideoDepacketizer {

    /** The RTP encoding name handled, e.g. {@code VP8}. */
    String encodingName();

    /**
     * Feeds one RTP packet.
     *
     * @return the completed frame when this packet (carrying the marker bit) completes an intact frame that can
     *         be decoded, otherwise {@code null}
     */
    EncodedVideoFrame push(RtpPacket packet);

    /** Returns whether a key frame should be requested since the last call, and clears the flag. */
    boolean consumeKeyFrameNeeded();

    /** Discards all state, as if newly created. */
    void reset();
}
