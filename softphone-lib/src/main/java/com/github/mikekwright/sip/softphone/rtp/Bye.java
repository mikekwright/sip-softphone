package com.github.mikekwright.sip.softphone.rtp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * RTCP goodbye (BYE, packet type 203), RFC 3550 §6.6: indicates that one or more sources are no longer active.
 *
 * @param ssrcs  the sources leaving (1..31 entries)
 * @param reason optional reason for leaving (UTF-8, at most 255 octets) or {@code null}
 */
public record Bye(List<Long> ssrcs, String reason) implements RtcpPacket {

    public Bye {
        ssrcs = List.copyOf(Objects.requireNonNull(ssrcs, "ssrcs"));
        if (ssrcs.size() > 31) {
            throw new IllegalArgumentException("at most 31 SSRCs per BYE");
        }
        ssrcs.forEach(s -> Uint32.check(s, "ssrc"));
        if (reason != null && reason.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("BYE reason longer than 255 octets");
        }
    }

    /** A BYE for a single source without reason. */
    public static Bye of(long ssrc) {
        return new Bye(List.of(ssrc), null);
    }

    @Override
    public int packetType() {
        return PT_BYE;
    }
}
