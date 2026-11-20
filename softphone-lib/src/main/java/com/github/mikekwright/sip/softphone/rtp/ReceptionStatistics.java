package com.github.mikekwright.sip.softphone.rtp;

/**
 * Reception statistics for one remote synchronization source, implementing the algorithms of RFC 3550
 * Appendix A:
 * <ul>
 *   <li>A.1 — sequence number maintenance: extended highest sequence number with wrap-around (cycles), detection
 *       of large jumps ({@value #MAX_DROPOUT} packets) and re-synchronisation after two sequential packets;</li>
 *   <li>A.3 — expected/lost packet counts and the fraction lost per reporting interval;</li>
 *   <li>A.8 — interarrival jitter estimate {@code J += (|D| - J) / 16} computed from arrival times and RTP
 *       timestamps converted with the payload's clock rate.</li>
 * </ul>
 * It also remembers the last sender report received from the source to fill the LSR/DLSR fields of
 * {@link ReportBlock}s (RFC 3550 §6.4.1).
 *
 * <p>Deviation from A.1: the source is accepted on its first packet (no probation period of
 * {@code MIN_SEQUENTIAL} packets), which suits point-to-point calls where the remote is known from signalling.</p>
 *
 * <p>Thread-safety: all methods are {@code synchronized}; updates normally come from a single network thread
 * while getters may be called from anywhere.</p>
 */
public final class ReceptionStatistics {

    /** Largest forward jump (in packets) still treated as normal sequence progress. */
    public static final int MAX_DROPOUT = 3000;
    /** Largest backward jump (in packets) treated as reordering rather than a sequence restart. */
    public static final int MAX_MISORDER = 100;
    private static final int RTP_SEQ_MOD = 1 << 16;

    private final long ssrc;

    private boolean initialized;
    private int maxSeq;
    private long cycles;
    private long baseSeq;
    private int badSeq = RTP_SEQ_MOD + 1;
    private long received;
    private long expectedPrior;
    private long receivedPrior;
    private long octetsReceived;

    private boolean jitterInitialized;
    private long firstArrivalNanos;
    private long lastTransit;
    private double jitter;

    private long lastSrCompactNtp;
    private long lastSrArrivalNanos;
    private boolean srReceived;

    /** Creates statistics for the given remote SSRC. */
    public ReceptionStatistics(long ssrc) {
        this.ssrc = ssrc;
    }

    /** The remote SSRC these statistics belong to. */
    public long ssrc() {
        return ssrc;
    }

    /**
     * Records the arrival of an RTP packet.
     *
     * @param sequenceNumber the packet's 16-bit sequence number
     * @param rtpTimestamp   the packet's RTP timestamp (uint32)
     * @param payloadOctets  payload size, for octet counting
     * @param arrivalNanos   arrival time from {@link System#nanoTime()} (or any monotonic nanosecond clock)
     * @param clockRate      RTP clock rate of the packet's payload type in Hz; jitter is not updated when &lt;= 0
     * @return {@code false} if the packet was considered invalid by the A.1 algorithm (a large jump not yet
     *         confirmed by a following sequential packet)
     */
    public synchronized boolean onPacket(int sequenceNumber, long rtpTimestamp, int payloadOctets,
                                         long arrivalNanos, int clockRate) {
        if (!initialized) {
            initSequence(sequenceNumber);
            initialized = true;
        } else if (!updateSequence(sequenceNumber)) {
            return false;
        }
        received++;
        octetsReceived += payloadOctets;
        if (clockRate > 0) {
            updateJitter(rtpTimestamp, arrivalNanos, clockRate);
        }
        return true;
    }

    private void initSequence(int seq) {
        baseSeq = seq;
        maxSeq = seq;
        badSeq = RTP_SEQ_MOD + 1;
        cycles = 0;
        received = 0;
        receivedPrior = 0;
        expectedPrior = 0;
    }

    private boolean updateSequence(int seq) {
        int udelta = (seq - maxSeq) & 0xFFFF;
        if (udelta < MAX_DROPOUT) {
            // In order, with permissible gap.
            if (seq < maxSeq) {
                cycles += RTP_SEQ_MOD;
            }
            maxSeq = seq;
        } else if (udelta <= RTP_SEQ_MOD - MAX_MISORDER) {
            // The sequence number made a very large jump.
            if (seq == badSeq) {
                // Two sequential packets: assume the other side restarted without telling us.
                initSequence(seq);
            } else {
                badSeq = (seq + 1) & (RTP_SEQ_MOD - 1);
                return false;
            }
        }
        // else: duplicate or reordered packet; counted as received (RFC 3550 A.3 notes lost may go negative).
        return true;
    }

    private void updateJitter(long rtpTimestamp, long arrivalNanos, int clockRate) {
        if (!jitterInitialized) {
            firstArrivalNanos = arrivalNanos;
        }
        long elapsedMicros = (arrivalNanos - firstArrivalNanos) / 1_000L;
        long arrival = elapsedMicros * clockRate / 1_000_000L;
        long transit = arrival - rtpTimestamp;
        if (jitterInitialized) {
            // 32-bit signed difference copes with RTP timestamp wrap-around.
            int d = (int) (transit - lastTransit);
            jitter += (Math.abs((double) d) - jitter) / 16.0;
        }
        lastTransit = transit;
        jitterInitialized = true;
    }

    /**
     * Records a sender report received from this source, for the LSR/DLSR fields of subsequent report blocks.
     *
     * @param ntpTimestamp the SR's 64-bit NTP timestamp
     * @param arrivalNanos arrival time of the SR on the same clock as used for {@link #onPacket}
     */
    public synchronized void onSenderReport(long ntpTimestamp, long arrivalNanos) {
        lastSrCompactNtp = NtpTime.middle32(ntpTimestamp);
        lastSrArrivalNanos = arrivalNanos;
        srReceived = true;
    }

    /** Whether at least one RTP packet was recorded. */
    public synchronized boolean hasReceived() {
        return initialized;
    }

    /** Packets received, including duplicates (RFC 3550 A.3). */
    public synchronized long packetsReceived() {
        return received;
    }

    /** Payload octets received. */
    public synchronized long octetsReceived() {
        return octetsReceived;
    }

    /** Extended highest sequence number received: cycles in the upper 16 bits, sequence number in the lower. */
    public synchronized long extendedHighestSequence() {
        return cycles + maxSeq;
    }

    /** Number of packets expected: {@code extended max - base + 1}. */
    public synchronized long packetsExpected() {
        return initialized ? cycles + maxSeq - baseSeq + 1 : 0;
    }

    /** Cumulative number of packets lost (may be negative when duplicates arrived). */
    public synchronized long packetsLost() {
        return packetsExpected() - received;
    }

    /** Current interarrival jitter estimate in RTP timestamp units. */
    public synchronized double jitter() {
        return jitter;
    }

    /**
     * Builds a reception report block for this source and starts a new reporting interval (the fraction lost
     * is computed relative to the previous call, RFC 3550 A.3).
     *
     * @param nowNanos the current time on the clock used for {@link #onSenderReport}, for the DLSR field
     */
    public synchronized ReportBlock toReportBlock(long nowNanos) {
        long expected = packetsExpected();
        long lost = expected - received;
        int cumulativeLost = (int) Math.max(ReportBlock.MIN_CUMULATIVE_LOST,
                Math.min(ReportBlock.MAX_CUMULATIVE_LOST, lost));

        long expectedInterval = expected - expectedPrior;
        expectedPrior = expected;
        long receivedInterval = received - receivedPrior;
        receivedPrior = received;
        long lostInterval = expectedInterval - receivedInterval;
        int fraction = (expectedInterval == 0 || lostInterval <= 0)
                ? 0 : (int) Math.min(255, (lostInterval << 8) / expectedInterval);

        long lsr = 0;
        long dlsr = 0;
        if (srReceived) {
            lsr = lastSrCompactNtp;
            long delayNanos = Math.max(0, nowNanos - lastSrArrivalNanos);
            dlsr = Math.min(Uint32.MASK, delayNanos * 65_536L / 1_000_000_000L);
        }
        return new ReportBlock(ssrc, fraction, cumulativeLost, extendedHighestSequence() & Uint32.MASK,
                Math.min(Uint32.MASK, (long) jitter), lsr, dlsr);
    }

    @Override
    public synchronized String toString() {
        return "ReceptionStatistics[ssrc=" + Long.toHexString(ssrc) + ", received=" + received
                + ", expected=" + packetsExpected() + ", jitter=" + String.format("%.1f", jitter) + "]";
    }
}
