package com.github.mikekwright.sip.softphone.rtp;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Fallback for RTCP packets this library does not model (e.g. APP, XR, or feedback messages with unknown FMT).
 * Re-encoding it reproduces the original packet (minus any padding).
 *
 * @param packetType the RTCP packet type (0..255)
 * @param count      the 5-bit count / FMT field of the common header (0..31)
 * @param body       the packet body following the 4-byte common header, without padding. Not copied. When its
 *                   length is not a multiple of four, {@link RtcpCodec} pads it and sets the P bit on encoding.
 */
public record UnknownRtcpPacket(int packetType, int count, byte[] body) implements RtcpPacket {

    public UnknownRtcpPacket {
        if (packetType < 0 || packetType > 255) {
            throw new IllegalArgumentException("packet type out of range: " + packetType);
        }
        if (count < 0 || count > 31) {
            throw new IllegalArgumentException("count out of range: " + count);
        }
        Objects.requireNonNull(body, "body");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UnknownRtcpPacket u && u.packetType == packetType && u.count == count
                && Arrays.equals(u.body, body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packetType, count) * 31 + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "UnknownRtcpPacket[pt=" + packetType + ", count=" + count + ", body=" + HexFormat.of().formatHex(body)
                + "]";
    }
}
