package com.github.mikekwright.sip.softphone.rtp;

import java.util.List;

/**
 * Send-side counters of a local synchronization source, used to fill RTCP sender reports
 * (RFC 3550 §6.4.1): the packet count, the payload octet count, and the correspondence between wallclock and
 * RTP timestamp of the most recently sent packet, extrapolated to the report instant.
 *
 * <p>Thread-safety: all methods are {@code synchronized}.</p>
 */
public final class SenderStatistics {

    private long packets;
    private long octets;
    private long lastRtpTimestamp;
    private long lastSendNanos;
    private int lastClockRate;
    private boolean sentSinceLastReport;

    /**
     * Records a sent RTP packet.
     *
     * @param payloadOctets payload length (RFC 3550 counts payload octets only, not header or padding)
     * @param rtpTimestamp  the packet's RTP timestamp
     * @param sendNanos     the send time from {@link System#nanoTime()}
     * @param clockRate     the RTP clock rate of the packet's payload type (0 if unknown)
     */
    public synchronized void onPacketSent(int payloadOctets, long rtpTimestamp, long sendNanos, int clockRate) {
        packets++;
        octets += payloadOctets;
        lastRtpTimestamp = rtpTimestamp;
        lastSendNanos = sendNanos;
        lastClockRate = clockRate;
        sentSinceLastReport = true;
    }

    /** Total packets sent. */
    public synchronized long packetsSent() {
        return packets;
    }

    /** Total payload octets sent. */
    public synchronized long octetsSent() {
        return octets;
    }

    /** Whether any packet was sent since the last call of this method; resets the flag. */
    public synchronized boolean consumeSentSinceLastReport() {
        boolean sent = sentSinceLastReport;
        sentSinceLastReport = false;
        return sent;
    }

    /**
     * Builds a sender report for the current instant. The RTP timestamp is the last sent timestamp advanced by
     * the elapsed time at the last packet's clock rate, so that NTP and RTP timestamps refer to the same instant.
     */
    public synchronized SenderReport toSenderReport(long ssrc, long ntpNow, long nowNanos,
                                                    List<ReportBlock> blocks) {
        long rtpNow = lastRtpTimestamp;
        if (lastClockRate > 0 && packets > 0) {
            long elapsedMicros = Math.max(0, nowNanos - lastSendNanos) / 1_000L;
            rtpNow = (lastRtpTimestamp + elapsedMicros * lastClockRate / 1_000_000L) & Uint32.MASK;
        }
        return new SenderReport(ssrc, ntpNow, rtpNow, packets & Uint32.MASK, octets & Uint32.MASK, blocks);
    }
}
