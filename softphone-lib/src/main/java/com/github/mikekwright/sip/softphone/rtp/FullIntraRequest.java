package com.github.mikekwright.sip.softphone.rtp;

import java.util.List;
import java.util.Objects;

/**
 * Full Intra Request (FIR), RFC 5104 §4.3.1: a payload-specific feedback message (PT=206, FMT=4) asking one or
 * more media senders to send a decoder refresh point (key frame). Per RFC 5104 the "media source" SSRC of the
 * common feedback header is unused and set to 0; the targets are listed in the FCI entries.
 *
 * @param senderSsrc SSRC of the packet sender (uint32)
 * @param entries    FCI entries, one per media sender asked to refresh
 */
public record FullIntraRequest(long senderSsrc, List<Entry> entries) implements RtcpPacket {

    /** Feedback message type of FIR within PSFB. */
    public static final int FMT = 4;

    public FullIntraRequest {
        Uint32.check(senderSsrc, "senderSsrc");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** Whether this request targets the given media sender SSRC. */
    public boolean targets(long ssrc) {
        return entries.stream().anyMatch(e -> e.ssrc() == ssrc);
    }

    @Override
    public int packetType() {
        return PT_PSFB;
    }

    /**
     * A FIR FCI entry, RFC 5104 §4.3.1.1.
     *
     * @param ssrc           SSRC of the media sender that is requested to send a decoder refresh point
     * @param sequenceNumber command sequence number (0..255), incremented for each new request
     */
    public record Entry(long ssrc, int sequenceNumber) {
        public Entry {
            Uint32.check(ssrc, "ssrc");
            if (sequenceNumber < 0 || sequenceNumber > 255) {
                throw new IllegalArgumentException("FIR sequence number out of range: " + sequenceNumber);
            }
        }
    }
}
