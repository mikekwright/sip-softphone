package com.github.mikekwright.sip.softphone.rtp;

import java.util.List;
import java.util.Objects;

/**
 * RTCP receiver report (RR, packet type 201), RFC 3550 §6.4.2. Sent by participants that did not send RTP
 * data since the previous report.
 *
 * @param senderSsrc   SSRC of the originator of this RR (uint32)
 * @param reportBlocks at most 31 reception report blocks (may be empty)
 */
public record ReceiverReport(long senderSsrc, List<ReportBlock> reportBlocks) implements RtcpPacket {

    public ReceiverReport {
        Uint32.check(senderSsrc, "senderSsrc");
        reportBlocks = List.copyOf(Objects.requireNonNull(reportBlocks, "reportBlocks"));
        if (reportBlocks.size() > 31) {
            throw new IllegalArgumentException("at most 31 report blocks per RR");
        }
    }

    @Override
    public int packetType() {
        return PT_RR;
    }
}
