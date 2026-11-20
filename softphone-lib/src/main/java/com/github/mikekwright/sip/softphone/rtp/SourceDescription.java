package com.github.mikekwright.sip.softphone.rtp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * RTCP source description (SDES, packet type 202), RFC 3550 §6.5. Consists of one chunk per source, each
 * holding a list of items; every compound RTCP packet must contain at least the CNAME item of the sender
 * (RFC 3550 §6.1).
 *
 * @param chunks at most 31 chunks
 */
public record SourceDescription(List<Chunk> chunks) implements RtcpPacket {

    /** Canonical end-point identifier, RFC 3550 §6.5.1. */
    public static final int CNAME = 1;
    /** User name, RFC 3550 §6.5.2. */
    public static final int NAME = 2;
    /** Electronic mail address, RFC 3550 §6.5.3. */
    public static final int EMAIL = 3;
    /** Phone number, RFC 3550 §6.5.4. */
    public static final int PHONE = 4;
    /** Geographic user location, RFC 3550 §6.5.5. */
    public static final int LOC = 5;
    /** Application or tool name, RFC 3550 §6.5.6. */
    public static final int TOOL = 6;
    /** Notice/status, RFC 3550 §6.5.7. */
    public static final int NOTE = 7;
    /** Private extensions, RFC 3550 §6.5.8. */
    public static final int PRIV = 8;

    public SourceDescription {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        if (chunks.size() > 31) {
            throw new IllegalArgumentException("at most 31 SDES chunks");
        }
    }

    /** Convenience factory for the common single-source CNAME-only SDES packet. */
    public static SourceDescription cname(long ssrc, String cname) {
        return new SourceDescription(List.of(new Chunk(ssrc, List.of(new Item(CNAME, cname)))));
    }

    @Override
    public int packetType() {
        return PT_SDES;
    }

    /**
     * An SDES chunk: the items describing one source.
     *
     * @param ssrc  SSRC or CSRC being described (uint32)
     * @param items the items (an item list may be empty)
     */
    public record Chunk(long ssrc, List<Item> items) {
        public Chunk {
            Uint32.check(ssrc, "ssrc");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }

        /** The value of the first CNAME item, if any. */
        public Optional<String> cname() {
            return items.stream().filter(i -> i.type() == CNAME).map(Item::value).findFirst();
        }
    }

    /**
     * An SDES item. The text is encoded as UTF-8 and must not exceed 255 octets.
     *
     * @param type  item type (1..255, see the constants of {@link SourceDescription})
     * @param value item text
     */
    public record Item(int type, String value) {
        public Item {
            if (type < 1 || type > 255) {
                throw new IllegalArgumentException("SDES item type out of range: " + type);
            }
            Objects.requireNonNull(value, "value");
            if (value.getBytes(StandardCharsets.UTF_8).length > 255) {
                throw new IllegalArgumentException("SDES item longer than 255 octets");
            }
        }
    }
}
