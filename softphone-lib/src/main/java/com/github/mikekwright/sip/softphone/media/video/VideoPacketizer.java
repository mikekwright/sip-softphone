package com.github.mikekwright.sip.softphone.media.video;

import java.util.List;

/**
 * Splits encoded video frames into RTP payloads according to a codec's RTP payload format.
 *
 * <p>The caller builds the RTP packets: all payloads of a frame share one RTP timestamp, take consecutive
 * sequence numbers, and the last one carries the marker bit (RFC 3551 §5, and the respective payload format
 * specifications).</p>
 *
 * <p>Implementations should be stateless or document otherwise; the built-in ones are stateless and
 * thread-safe.</p>
 */
public interface VideoPacketizer {

    /** The RTP encoding name handled, e.g. {@code VP8}. */
    String encodingName();

    /**
     * Packetizes one frame.
     *
     * @param frame          the frame
     * @param maxPayloadSize maximum size of each RTP payload in bytes (excluding the RTP header)
     * @return the RTP payloads in sending order (at least one)
     */
    List<byte[]> packetize(EncodedVideoFrame frame, int maxPayloadSize);
}
