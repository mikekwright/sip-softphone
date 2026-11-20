package com.github.mikekwright.sip.softphone.sdp;

import java.util.List;
import java.util.Objects;

/**
 * A payload format both parties agreed on.
 *
 * <p>Payload type numbers are directional (RFC 3264 §5.1, §6.1): an RTP receiver interprets payload types
 * according to the SDP <em>it</em> sent. Hence {@link #localPayloadType()} is the number from our SDP (what we
 * receive on) and {@link #remotePayloadType()} the number from the peer's SDP (what we must send with). With an
 * answerer that reuses the offerer's numbers, both are usually the same.</p>
 *
 * @param encodingName      encoding name, e.g. {@code PCMU}
 * @param clockRate         RTP clock rate in Hz
 * @param channels          number of channels
 * @param localPayloadType  payload type number in our SDP (incoming RTP)
 * @param remotePayloadType payload type number in the peer's SDP (outgoing RTP)
 * @param localFmtp         our {@code fmtp} parameters or {@code null}
 * @param remoteFmtp        the peer's {@code fmtp} parameters or {@code null}
 * @param rtcpFeedback      RTCP feedback values supported by both sides (RFC 4585)
 */
public record NegotiatedFormat(String encodingName, int clockRate, int channels, int localPayloadType,
                               int remotePayloadType, String localFmtp, String remoteFmtp,
                               List<String> rtcpFeedback) {

    public NegotiatedFormat {
        Objects.requireNonNull(encodingName, "encodingName");
        rtcpFeedback = rtcpFeedback == null ? List.of() : List.copyOf(rtcpFeedback);
    }

    /** Case-insensitive comparison of the encoding name. */
    public boolean is(String name) {
        return encodingName.equalsIgnoreCase(name);
    }

    /** The format as the peer declared it (payload type and fmtp to use when sending). */
    public RtpMap remoteRtpMap() {
        return new RtpMap(remotePayloadType, encodingName, clockRate, channels, remoteFmtp, rtcpFeedback);
    }

    /** The format as we declared it (payload type and fmtp of incoming RTP). */
    public RtpMap localRtpMap() {
        return new RtpMap(localPayloadType, encodingName, clockRate, channels, localFmtp, rtcpFeedback);
    }
}
