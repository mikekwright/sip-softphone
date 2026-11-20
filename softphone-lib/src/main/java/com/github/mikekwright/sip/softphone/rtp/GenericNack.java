package com.github.mikekwright.sip.softphone.rtp;

import java.util.List;
import java.util.Objects;

/**
 * Generic NACK, RFC 4585 §6.2.1: a transport layer feedback message (PT=205, FMT=1) reporting lost RTP packets.
 *
 * <p>On the wire the lost sequence numbers are grouped into FCI entries of a packet ID (PID) and a 16-bit bitmask
 * of following lost packets (BLP). This record exposes the expanded list of lost sequence numbers;
 * {@link RtcpCodec} performs the grouping when encoding and the expansion when decoding. The list order is
 * preserved for grouping (consecutive numbers within 16 of the entry's PID share an entry), so callers should
 * pass them in ascending (wrap-aware) order for the most compact encoding.</p>
 *
 * @param senderSsrc            SSRC of the packet sender (uint32)
 * @param mediaSsrc             SSRC of the media source whose packets were lost (uint32)
 * @param lostSequenceNumbers   lost RTP sequence numbers (0..65535)
 */
public record GenericNack(long senderSsrc, long mediaSsrc, List<Integer> lostSequenceNumbers) implements RtcpPacket {

    /** Feedback message type of Generic NACK within RTPFB. */
    public static final int FMT = 1;

    public GenericNack {
        Uint32.check(senderSsrc, "senderSsrc");
        Uint32.check(mediaSsrc, "mediaSsrc");
        lostSequenceNumbers = List.copyOf(Objects.requireNonNull(lostSequenceNumbers, "lostSequenceNumbers"));
        for (int seq : lostSequenceNumbers) {
            if (seq < 0 || seq > 0xFFFF) {
                throw new IllegalArgumentException("sequence number out of range: " + seq);
            }
        }
    }

    @Override
    public int packetType() {
        return PT_RTPFB;
    }
}
