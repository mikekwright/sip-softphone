package com.github.mikekwright.sip.softphone.sdp;

import java.util.Objects;

/**
 * An SDP attribute line ({@code a=}), RFC 8866 §5.13.
 *
 * <p>Attributes take two forms: a <em>property attribute</em> such as {@code a=sendrecv} or {@code a=rtcp-mux},
 * represented with a {@code null} {@link #value()}, and a <em>value attribute</em> such as
 * {@code a=rtpmap:96 VP8/90000}, whose value is everything after the first colon.</p>
 *
 * @param name  attribute name, e.g. {@code rtpmap}; never {@code null}
 * @param value attribute value, or {@code null} for a property attribute
 */
public record Attribute(String name, String value) {

    public Attribute {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("attribute name must not be blank");
        }
    }

    /** Creates a property attribute (no value), e.g. {@code a=rtcp-mux}. */
    public static Attribute property(String name) {
        return new Attribute(name, null);
    }

    /** Whether this is a property attribute (has no value). */
    public boolean isProperty() {
        return value == null;
    }

    /** Case-insensitive comparison of the attribute name. */
    public boolean is(String attributeName) {
        return name.equalsIgnoreCase(attributeName);
    }

    /** The text after {@code a=}: {@code name} or {@code name:value}. */
    public String toSdpValue() {
        return value == null ? name : name + ':' + value;
    }

    @Override
    public String toString() {
        return "a=" + toSdpValue();
    }
}
