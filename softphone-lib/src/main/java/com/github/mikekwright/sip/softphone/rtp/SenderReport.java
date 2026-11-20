package com.github.mikekwright.sip.softphone.rtp;

import java.util.List;
import java.util.Objects;

/**
 * RTCP sender report (SR, packet type 200), RFC 3550 §6.4.1. Sent by participants that recently sent RTP data;
 * carries the sender's wallclock/RTP timestamp correspondence and send counters, followed by zero or more
 * reception report blocks.
 *
 * @param senderSsrc   SSRC of the originator of this SR (uint32)
 * @param ntpTimestamp 64-bit NTP timestamp (seconds since 1900 in the upper 32 bits, fraction in the lower);
 *                     see {@link NtpTime}
 * @param rtpTimestamp RTP timestamp corresponding to the same instant as {@code ntpTimestamp} (uint32)
 * @param packetCount  total RTP data packets sent by this source (uint32, wraps)
 * @param octetCount   total RTP payload octets sent (uint32, wraps; excludes headers and padding)
 * @param reportBlocks at most 31 reception report blocks
 */
public record SenderReport(long senderSsrc, long ntpTimestamp, long rtpTimestamp, long packetCount,
                           long octetCount, List<ReportBlock> reportBlocks) implements RtcpPacket {

    public SenderReport {
        Uint32.check(senderSsrc, "senderSsrc");
        Uint32.check(rtpTimestamp, "rtpTimestamp");
        Uint32.check(packetCount, "packetCount");
        Uint32.check(octetCount, "octetCount");
        reportBlocks = List.copyOf(Objects.requireNonNull(reportBlocks, "reportBlocks"));
        if (reportBlocks.size() > 31) {
            throw new IllegalArgumentException("at most 31 report blocks per SR");
        }
    }

    /** The middle 32 bits of the NTP timestamp, as echoed in the LSR field of reception reports. */
    public long compactNtpTimestamp() {
        return NtpTime.middle32(ntpTimestamp);
    }

    @Override
    public int packetType() {
        return PT_SR;
    }
}
