package com.github.mikekwright.sip.softphone.rtp;

/**
 * One RTCP packet inside a compound RTCP packet (RFC 3550 §6). Every RTCP packet starts with the common header
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P| RC/FMT  |      PT       |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * which {@link RtcpCodec} reads and writes; implementations only model the body. The hierarchy is sealed so
 * that consumers can use exhaustive {@code switch} pattern matching; packet types this library does not model
 * are surfaced as {@link UnknownRtcpPacket}.
 *
 * <p>All implementations are immutable records and therefore thread-safe.</p>
 */
public sealed interface RtcpPacket
        permits SenderReport, ReceiverReport, SourceDescription, Bye, PictureLossIndication, FullIntraRequest,
        GenericNack, UnknownRtcpPacket {

    /** Sender report, RFC 3550 §6.4.1. */
    int PT_SR = 200;
    /** Receiver report, RFC 3550 §6.4.2. */
    int PT_RR = 201;
    /** Source description, RFC 3550 §6.5. */
    int PT_SDES = 202;
    /** Goodbye, RFC 3550 §6.6. */
    int PT_BYE = 203;
    /** Application defined, RFC 3550 §6.7. */
    int PT_APP = 204;
    /** Transport layer feedback, RFC 4585 §6.2. */
    int PT_RTPFB = 205;
    /** Payload specific feedback, RFC 4585 §6.3. */
    int PT_PSFB = 206;

    /** The RTCP packet type (second octet of the common header). */
    int packetType();
}
