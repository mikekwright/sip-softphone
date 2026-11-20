package com.github.mikekwright.sip.softphone.media;

import java.util.List;
import java.util.Objects;

/**
 * An RTP payload format bound to a payload type number, as negotiated through SDP
 * ({@code a=rtpmap}, {@code a=fmtp} and {@code a=rtcp-fb}).
 *
 * @param payloadType  RTP payload type number (0-127)
 * @param encodingName encoding name, e.g. {@code PCMU}, {@code VP8}, {@code telephone-event}
 * @param clockRate    RTP clock rate in Hz
 * @param channels     number of audio channels (1 for video)
 * @param fmtp         format specific parameters ({@code a=fmtp} value) or {@code null}
 * @param rtcpFeedback RTCP feedback capabilities ({@code a=rtcp-fb} values, e.g. {@code nack pli})
 */
public record MediaFormat(int payloadType, String encodingName, int clockRate, int channels, String fmtp,
                          List<String> rtcpFeedback) {

    public MediaFormat {
        Objects.requireNonNull(encodingName, "encodingName");
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payload type out of range: " + payloadType);
        }
        rtcpFeedback = rtcpFeedback == null ? List.of() : List.copyOf(rtcpFeedback);
    }

    public MediaFormat(int payloadType, String encodingName, int clockRate) {
        this(payloadType, encodingName, clockRate, 1, null, List.of());
    }

    /** Case-insensitive comparison of the encoding name. */
    public boolean is(String name) {
        return encodingName.equalsIgnoreCase(name);
    }
}
