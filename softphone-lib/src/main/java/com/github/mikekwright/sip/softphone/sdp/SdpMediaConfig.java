package com.github.mikekwright.sip.softphone.sdp;

import java.util.List;
import java.util.Objects;

/**
 * Local configuration of one media stream, the input of {@link SdpNegotiator} for both offers and answers.
 *
 * <p>Formats are listed in preference order and carry the payload type numbers to use in an offer (dynamic
 * formats should use 96-127), along with their {@code fmtp} and {@code rtcp-fb} values. DTMF is configured by
 * including an RFC 4733 {@code telephone-event} format, e.g. {@code new RtpMap(101, "telephone-event", 8000)
 * .withFmtp("0-16")}.</p>
 *
 * @param mediaType media type of the {@code m=} line, e.g. {@code audio} or {@code video}
 * @param address   local RTP address for the {@code c=} line; {@code null} uses the session-level address
 * @param port      local RTP port; 0 disables the stream in an offer and rejects it in an answer
 * @param formats   supported formats in preference order; must not be empty
 * @param direction desired direction from the local point of view; {@code null} means {@code SENDRECV}
 * @param rtcpMux   whether to offer/accept RTP and RTCP multiplexing ({@code a=rtcp-mux}, RFC 5761)
 * @param protocol  transport protocol; {@code null} means {@code RTP/AVP}, use {@code RTP/AVPF} for feedback
 * @param ptime     packetization time in milliseconds for {@code a=ptime}, or {@code null} to omit it
 */
public record SdpMediaConfig(String mediaType, String address, int port, List<RtpMap> formats,
                             MediaDirection direction, boolean rtcpMux, String protocol, Integer ptime) {

    public SdpMediaConfig {
        Objects.requireNonNull(mediaType, "mediaType");
        formats = List.copyOf(Objects.requireNonNull(formats, "formats"));
        if (formats.isEmpty()) {
            throw new IllegalArgumentException("at least one format is required");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        direction = direction == null ? MediaDirection.SENDRECV : direction;
        protocol = protocol == null ? MediaDescription.RTP_AVP : protocol;
    }

    /** A {@code sendrecv}, {@code RTP/AVP} stream without {@code rtcp-mux} or {@code ptime}. */
    public static SdpMediaConfig of(String mediaType, String address, int port, List<RtpMap> formats) {
        return new SdpMediaConfig(mediaType, address, port, formats, MediaDirection.SENDRECV, false, null, null);
    }

    /** Shorthand for {@link #of} with media type {@code audio}. */
    public static SdpMediaConfig audio(String address, int port, List<RtpMap> formats) {
        return of(MediaDescription.AUDIO, address, port, formats);
    }

    /** Shorthand for {@link #of} with media type {@code video}. */
    public static SdpMediaConfig video(String address, int port, List<RtpMap> formats) {
        return of(MediaDescription.VIDEO, address, port, formats);
    }

    public SdpMediaConfig withAddress(String newAddress) {
        return new SdpMediaConfig(mediaType, newAddress, port, formats, direction, rtcpMux, protocol, ptime);
    }

    public SdpMediaConfig withPort(int newPort) {
        return new SdpMediaConfig(mediaType, address, newPort, formats, direction, rtcpMux, protocol, ptime);
    }

    public SdpMediaConfig withFormats(List<RtpMap> newFormats) {
        return new SdpMediaConfig(mediaType, address, port, newFormats, direction, rtcpMux, protocol, ptime);
    }

    public SdpMediaConfig withDirection(MediaDirection newDirection) {
        return new SdpMediaConfig(mediaType, address, port, formats, newDirection, rtcpMux, protocol, ptime);
    }

    public SdpMediaConfig withRtcpMux(boolean newRtcpMux) {
        return new SdpMediaConfig(mediaType, address, port, formats, direction, newRtcpMux, protocol, ptime);
    }

    public SdpMediaConfig withProtocol(String newProtocol) {
        return new SdpMediaConfig(mediaType, address, port, formats, direction, rtcpMux, newProtocol, ptime);
    }

    public SdpMediaConfig withPtime(Integer newPtime) {
        return new SdpMediaConfig(mediaType, address, port, formats, direction, rtcpMux, protocol, newPtime);
    }
}
