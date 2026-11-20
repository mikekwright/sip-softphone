package com.github.mikekwright.sip.softphone.sdp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An RTP payload format: the mapping of a payload type number to an encoding
 * ({@code a=rtpmap:<payload type> <encoding name>/<clock rate>[/<encoding parameters>]}, RFC 8866 §6.6) together
 * with its format parameters ({@code a=fmtp}, RFC 8866 §6.15) and RTCP feedback capabilities ({@code a=rtcp-fb},
 * RFC 4585 §4.2).
 *
 * <p>The same type is used to describe local capabilities ({@link SdpMediaConfig#formats()}) and formats found in
 * a parsed description ({@link MediaDescription#rtpMaps()}).</p>
 *
 * @param payloadType  RTP payload type number, 0-127
 * @param encodingName encoding name, e.g. {@code PCMU}, {@code opus}, {@code telephone-event}
 * @param clockRate    RTP clock rate in Hz
 * @param channels     number of audio channels (the rtpmap encoding parameter), 1 if absent
 * @param fmtp         format specific parameters (the {@code a=fmtp} value after the payload type) or {@code null}
 * @param rtcpFeedback RTCP feedback values (after the payload type), e.g. {@code nack}, {@code nack pli}
 */
public record RtpMap(int payloadType, String encodingName, int clockRate, int channels, String fmtp,
                     List<String> rtcpFeedback) {

    /** Encoding name of RFC 4733 DTMF events. */
    public static final String TELEPHONE_EVENT = "telephone-event";

    public RtpMap {
        Objects.requireNonNull(encodingName, "encodingName");
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payload type out of range: " + payloadType);
        }
        if (clockRate <= 0) {
            throw new IllegalArgumentException("clock rate must be positive: " + clockRate);
        }
        if (channels <= 0) {
            channels = 1;
        }
        rtcpFeedback = rtcpFeedback == null ? List.of() : List.copyOf(rtcpFeedback);
    }

    /** A mono format without parameters. */
    public RtpMap(int payloadType, String encodingName, int clockRate) {
        this(payloadType, encodingName, clockRate, 1, null, List.of());
    }

    /** A format with the given channel count and no parameters. */
    public RtpMap(int payloadType, String encodingName, int clockRate, int channels) {
        this(payloadType, encodingName, clockRate, channels, null, List.of());
    }

    /**
     * Parses an {@code a=rtpmap} value such as {@code 96 opus/48000/2}.
     *
     * @throws IllegalArgumentException if the value is malformed
     */
    public static RtpMap parse(String rtpmapValue) {
        String[] parts = rtpmapValue.trim().split("\\s+", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("malformed rtpmap: " + rtpmapValue);
        }
        String[] encoding = parts[1].trim().split("/");
        if (encoding.length < 2 || encoding.length > 3 || encoding[0].isEmpty()) {
            throw new IllegalArgumentException("malformed rtpmap encoding: " + rtpmapValue);
        }
        try {
            int pt = Integer.parseInt(parts[0]);
            int rate = Integer.parseInt(encoding[1]);
            int channels = encoding.length == 3 ? Integer.parseInt(encoding[2]) : 1;
            return new RtpMap(pt, encoding[0], rate, channels);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed rtpmap number: " + rtpmapValue, e);
        }
    }

    /** The {@code a=rtpmap} value, e.g. {@code 96 VP8/90000}; channels are only appended when greater than 1. */
    public String toRtpMapValue() {
        StringBuilder sb = new StringBuilder().append(payloadType).append(' ')
                .append(encodingName).append('/').append(clockRate);
        if (channels > 1) {
            sb.append('/').append(channels);
        }
        return sb.toString();
    }

    /** Case-insensitive comparison of the encoding name (encoding names are case-insensitive, RFC 8866 §6.6). */
    public boolean is(String name) {
        return encodingName.equalsIgnoreCase(name);
    }

    /** Whether this is the RFC 4733 {@code telephone-event} format. */
    public boolean isTelephoneEvent() {
        return is(TELEPHONE_EVENT);
    }

    /** Whether encoding name, clock rate and channel count are equal (payload type and parameters ignored). */
    public boolean sameEncoding(RtpMap other) {
        return is(other.encodingName) && clockRate == other.clockRate && channels == other.channels;
    }

    /**
     * The {@code fmtp} parameters as an ordered map, assuming the common {@code key=value;key=value} syntax.
     * Keys are lower-cased; parameters without {@code =} map to an empty string. Formats such as
     * {@code telephone-event} ({@code 0-16}) yield a single key with an empty value.
     */
    public Map<String, String> fmtpParameters() {
        Map<String, String> result = new LinkedHashMap<>();
        if (fmtp == null) {
            return result;
        }
        for (String parameter : fmtp.split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                result.put(trimmed.toLowerCase(Locale.ROOT), "");
            } else {
                result.put(trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                        trimmed.substring(eq + 1).trim());
            }
        }
        return result;
    }

    /** A single {@code fmtp} parameter by (case-insensitive) name, see {@link #fmtpParameters()}. */
    public Optional<String> fmtpParameter(String name) {
        return Optional.ofNullable(fmtpParameters().get(name.toLowerCase(Locale.ROOT)));
    }

    /** Returns a copy with another payload type number. */
    public RtpMap withPayloadType(int pt) {
        return new RtpMap(pt, encodingName, clockRate, channels, fmtp, rtcpFeedback);
    }

    /** Returns a copy with other format parameters ({@code null} for none). */
    public RtpMap withFmtp(String parameters) {
        return new RtpMap(payloadType, encodingName, clockRate, channels, parameters, rtcpFeedback);
    }

    /** Returns a copy with other RTCP feedback values. */
    public RtpMap withRtcpFeedback(List<String> feedback) {
        return new RtpMap(payloadType, encodingName, clockRate, channels, fmtp, feedback);
    }
}
