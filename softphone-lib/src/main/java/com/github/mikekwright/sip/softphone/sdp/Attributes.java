package com.github.mikekwright.sip.softphone.sdp;

import java.util.List;
import java.util.Optional;

/**
 * Attribute list operations shared by {@link SessionDescription} and {@link MediaDescription}.
 * Attribute names are compared case-insensitively.
 */
final class Attributes {

    private Attributes() {
    }

    static Optional<String> first(List<Attribute> attributes, String name) {
        for (Attribute attribute : attributes) {
            if (attribute.is(name)) {
                return Optional.of(attribute.value() == null ? "" : attribute.value());
            }
        }
        return Optional.empty();
    }

    static List<String> values(List<Attribute> attributes, String name) {
        return attributes.stream()
                .filter(a -> a.is(name))
                .map(a -> a.value() == null ? "" : a.value())
                .toList();
    }

    static boolean has(List<Attribute> attributes, String name) {
        return attributes.stream().anyMatch(a -> a.is(name));
    }

    /** The first direction attribute (RFC 8866 §6.7) or {@code null}. */
    static MediaDirection direction(List<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            MediaDirection direction = MediaDirection.fromSdpName(attribute.name());
            if (direction != null && attribute.isProperty()) {
                return direction;
            }
        }
        return null;
    }

    /**
     * Replaces all direction attributes by {@code direction}, keeping the position of the first one that was
     * present (or appending). A {@code null} direction only removes them.
     */
    static void setDirection(List<Attribute> attributes, MediaDirection direction) {
        int position = -1;
        for (int i = attributes.size() - 1; i >= 0; i--) {
            if (MediaDirection.fromSdpName(attributes.get(i).name()) != null) {
                attributes.remove(i);
                position = i;
            }
        }
        if (direction == null) {
            return;
        }
        Attribute attribute = Attribute.property(direction.sdpName());
        if (position < 0) {
            attributes.add(attribute);
        } else {
            attributes.add(position, attribute);
        }
    }

    /**
     * Values of a payload-type keyed attribute such as {@code fmtp} or {@code rtcp-fb}: for every attribute whose
     * value starts with {@code <key> } returns the remainder.
     */
    static List<String> keyed(List<Attribute> attributes, String name, String key) {
        return attributes.stream()
                .filter(a -> a.is(name) && a.value() != null)
                .map(a -> a.value().trim())
                .filter(v -> v.startsWith(key + " ") || v.startsWith(key + "\t"))
                .map(v -> v.substring(key.length()).trim())
                .toList();
    }
}
