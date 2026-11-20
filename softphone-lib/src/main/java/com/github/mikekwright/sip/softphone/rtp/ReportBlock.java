package com.github.mikekwright.sip.softphone.rtp;

/**
 * A reception report block carried in sender and receiver reports (RFC 3550 §6.4.1).
 *
 * <pre>
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |                 SSRC_1 (SSRC of first source)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | fraction lost |       cumulative number of packets lost       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           extended highest sequence number received           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      interarrival jitter                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         last SR (LSR)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   delay since last SR (DLSR)                  |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * </pre>
 *
 * @param ssrc                       the source this block reports on (uint32)
 * @param fractionLost               fraction of packets lost since the previous report, as a fixed point number
 *                                   with the binary point at the left edge (0..255)
 * @param cumulativeLost             total packets lost, a <em>signed</em> 24-bit value (-8388608..8388607);
 *                                   negative when duplicates were received
 * @param extendedHighestSequence    cycles count (upper 16 bits) and highest sequence number received (uint32)
 * @param jitter                     interarrival jitter in timestamp units (uint32), RFC 3550 §6.4.1 / A.8
 * @param lastSenderReport           middle 32 bits of the NTP timestamp of the last SR received (uint32)
 * @param delaySinceLastSenderReport delay since that SR was received, in units of 1/65536 seconds (uint32)
 */
public record ReportBlock(long ssrc, int fractionLost, int cumulativeLost, long extendedHighestSequence,
                          long jitter, long lastSenderReport, long delaySinceLastSenderReport) {

    /** Minimum value of the signed 24-bit cumulative lost field. */
    public static final int MIN_CUMULATIVE_LOST = -0x800000;
    /** Maximum value of the signed 24-bit cumulative lost field. */
    public static final int MAX_CUMULATIVE_LOST = 0x7FFFFF;
    /** Encoded size of a report block in bytes. */
    public static final int SIZE = 24;

    public ReportBlock {
        if (fractionLost < 0 || fractionLost > 255) {
            throw new IllegalArgumentException("fraction lost out of range: " + fractionLost);
        }
        if (cumulativeLost < MIN_CUMULATIVE_LOST || cumulativeLost > MAX_CUMULATIVE_LOST) {
            throw new IllegalArgumentException("cumulative lost out of 24-bit range: " + cumulativeLost);
        }
        Uint32.check(ssrc, "ssrc");
        Uint32.check(extendedHighestSequence, "extendedHighestSequence");
        Uint32.check(jitter, "jitter");
        Uint32.check(lastSenderReport, "lastSenderReport");
        Uint32.check(delaySinceLastSenderReport, "delaySinceLastSenderReport");
    }

    /** The fraction lost as a value between 0.0 and 1.0. */
    public double fractionLostRatio() {
        return fractionLost / 256.0;
    }
}
