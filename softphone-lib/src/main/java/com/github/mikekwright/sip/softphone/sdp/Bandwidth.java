package com.github.mikekwright.sip.softphone.sdp;

import java.util.Objects;

/**
 * Bandwidth information ({@code b=<bwtype>:<bandwidth>}), RFC 8866 §5.8.
 *
 * <p>Common types are {@code CT} (conference total) and {@code AS} (application specific), both in kilobits per
 * second, and {@code TIAS} (RFC 3890) in bits per second. Unknown types are kept as-is.</p>
 *
 * @param type  bandwidth type, e.g. {@code AS}
 * @param value bandwidth value in the unit defined by the type
 */
public record Bandwidth(String type, long value) {

    /** Conference total, kbit/s (RFC 8866 §5.8). */
    public static final String CT = "CT";
    /** Application specific maximum, kbit/s (RFC 8866 §5.8). */
    public static final String AS = "AS";
    /** Transport independent application specific maximum, bit/s (RFC 3890). */
    public static final String TIAS = "TIAS";

    public Bandwidth {
        Objects.requireNonNull(type, "type");
        if (value < 0) {
            throw new IllegalArgumentException("bandwidth must not be negative: " + value);
        }
    }

    /** The text after {@code b=}, e.g. {@code AS:64}. */
    public String toSdpValue() {
        return type + ':' + value;
    }

    @Override
    public String toString() {
        return "b=" + toSdpValue();
    }
}
