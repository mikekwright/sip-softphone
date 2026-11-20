package com.github.mikekwright.sip.softphone.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * An RTP data packet as defined by RFC 3550 §5.1.
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |            contributing source (CSRC) identifiers             |
 * |                             ....                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>Unsigned 32-bit fields (timestamp, SSRC, CSRC) are represented as {@code long} values in the range
 * {@code 0..0xFFFFFFFF}; the sequence number is an {@code int} in {@code 0..0xFFFF}. The header extension
 * (RFC 3550 §5.3.1) is kept as an opaque profile identifier plus data.</p>
 *
 * <p>Instances are immutable "by convention": the payload and extension byte arrays are <em>not</em> copied
 * for performance reasons, so callers must not modify an array after handing it to the {@link Builder}, nor
 * modify arrays returned from the accessors. Instances are therefore safe to share between threads once
 * built. Use {@link #toBuilder()} or the {@code with...} methods to derive modified copies.</p>
 *
 * <p>Padding (the P bit) is handled transparently: {@link #decode(ByteBuf)} strips padding from the payload and
 * records its length in {@link #paddingLength()}; {@link #encode(ByteBuf)} appends {@code paddingLength}
 * bytes whose last octet holds the count, as required by RFC 3550 §5.1.</p>
 */
public final class RtpPacket {

    /** The only RTP version supported (RFC 3550). */
    public static final int VERSION = 2;
    /** Size of the fixed RTP header in bytes. */
    public static final int FIXED_HEADER_SIZE = 12;
    /** Maximum number of contributing sources (4 bit CC field). */
    public static final int MAX_CSRC_COUNT = 15;

    private final int paddingLength;
    private final HeaderExtension extension;
    private final boolean marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final List<Long> csrcs;
    private final byte[] payload;

    private RtpPacket(Builder b) {
        if (b.payloadType < 0 || b.payloadType > 127) {
            throw new IllegalArgumentException("payload type out of range: " + b.payloadType);
        }
        if (b.sequenceNumber < 0 || b.sequenceNumber > 0xFFFF) {
            throw new IllegalArgumentException("sequence number out of range: " + b.sequenceNumber);
        }
        Uint32.check(b.timestamp, "timestamp");
        Uint32.check(b.ssrc, "ssrc");
        if (b.csrcs.size() > MAX_CSRC_COUNT) {
            throw new IllegalArgumentException("too many CSRCs: " + b.csrcs.size());
        }
        for (Long csrc : b.csrcs) {
            Uint32.check(csrc, "csrc");
        }
        if (b.paddingLength < 0 || b.paddingLength > 255) {
            throw new IllegalArgumentException("padding length out of range: " + b.paddingLength);
        }
        this.paddingLength = b.paddingLength;
        this.extension = b.extension;
        this.marker = b.marker;
        this.payloadType = b.payloadType;
        this.sequenceNumber = b.sequenceNumber;
        this.timestamp = b.timestamp;
        this.ssrc = b.ssrc;
        this.csrcs = List.copyOf(b.csrcs);
        this.payload = b.payload == null ? new byte[0] : b.payload;
    }

    /** Creates an empty builder (payload type 0, sequence 0, timestamp 0, SSRC 0, no payload). */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a builder initialised with all fields of this packet. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.paddingLength = paddingLength;
        b.extension = extension;
        b.marker = marker;
        b.payloadType = payloadType;
        b.sequenceNumber = sequenceNumber;
        b.timestamp = timestamp;
        b.ssrc = ssrc;
        b.csrcs = new ArrayList<>(csrcs);
        b.payload = payload;
        return b;
    }

    // ------------------------------------------------------------------------------------------ accessors

    /** Always {@value #VERSION}. */
    public int version() {
        return VERSION;
    }

    /** Whether the P bit is (or will be) set. */
    public boolean hasPadding() {
        return paddingLength > 0;
    }

    /** Number of padding octets including the trailing count octet; 0 when there is no padding. */
    public int paddingLength() {
        return paddingLength;
    }

    /** Whether the X bit is set. */
    public boolean hasExtension() {
        return extension != null;
    }

    /** The header extension (RFC 3550 §5.3.1) or {@code null}. */
    public HeaderExtension extension() {
        return extension;
    }

    /** The M bit; its meaning is defined by the profile (e.g. start of talkspurt, last packet of a video frame). */
    public boolean marker() {
        return marker;
    }

    /** Payload type, 0..127. */
    public int payloadType() {
        return payloadType;
    }

    /** Sequence number, 0..65535. */
    public int sequenceNumber() {
        return sequenceNumber;
    }

    /** RTP timestamp as unsigned 32-bit value. */
    public long timestamp() {
        return timestamp;
    }

    /** Synchronization source identifier as unsigned 32-bit value. */
    public long ssrc() {
        return ssrc;
    }

    /** Contributing source identifiers (unmodifiable). */
    public List<Long> csrcs() {
        return csrcs;
    }

    /** The payload without padding. Do not modify the returned array. */
    public byte[] payload() {
        return payload;
    }

    /** Length of the payload in bytes (excluding header and padding). */
    public int payloadLength() {
        return payload.length;
    }

    /** Total encoded size in bytes. */
    public int encodedLength() {
        int size = FIXED_HEADER_SIZE + 4 * csrcs.size() + payload.length + paddingLength;
        if (extension != null) {
            size += 4 + extension.data().length;
        }
        return size;
    }

    // ------------------------------------------------------------------------------------------ withers

    public RtpPacket withMarker(boolean marker) {
        return toBuilder().marker(marker).build();
    }

    public RtpPacket withPayloadType(int payloadType) {
        return toBuilder().payloadType(payloadType).build();
    }

    public RtpPacket withSequenceNumber(int sequenceNumber) {
        return toBuilder().sequenceNumber(sequenceNumber).build();
    }

    public RtpPacket withTimestamp(long timestamp) {
        return toBuilder().timestamp(timestamp).build();
    }

    public RtpPacket withSsrc(long ssrc) {
        return toBuilder().ssrc(ssrc).build();
    }

    public RtpPacket withPayload(byte[] payload) {
        return toBuilder().payload(payload).build();
    }

    // ------------------------------------------------------------------------------------------ codec

    /**
     * Returns whether a datagram received on a multiplexed port is RTCP rather than RTP, using the rule of
     * RFC 5761 §4: the second octet of RTCP packets (the RTCP packet type) lies in the range 192..223, which
     * would correspond to RTP payload types 64..95 with the marker bit set — a range RFC 5761 forbids for RTP
     * when multiplexing. The buffer's reader index is not modified.
     */
    public static boolean looksLikeRtcp(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return false;
        }
        int second = buf.getUnsignedByte(buf.readerIndex() + 1);
        return second >= 192 && second <= 223;
    }

    /**
     * Decodes one RTP packet from the readable bytes of {@code buf}, consuming all of them. The buffer is not
     * released; ownership stays with the caller.
     *
     * @throws MalformedPacketException if the version is not 2, the buffer is too short for the header, CSRC
     *                                  list or extension, or the padding count is invalid
     */
    public static RtpPacket decode(ByteBuf buf) {
        int length = buf.readableBytes();
        if (length < FIXED_HEADER_SIZE) {
            throw new MalformedPacketException("RTP packet too short: " + length + " bytes");
        }
        int start = buf.readerIndex();
        int b0 = buf.readUnsignedByte();
        int version = b0 >>> 6;
        if (version != VERSION) {
            throw new MalformedPacketException("unsupported RTP version " + version);
        }
        boolean padding = (b0 & 0x20) != 0;
        boolean ext = (b0 & 0x10) != 0;
        int cc = b0 & 0x0F;
        int b1 = buf.readUnsignedByte();
        Builder b = builder()
                .marker((b1 & 0x80) != 0)
                .payloadType(b1 & 0x7F)
                .sequenceNumber(buf.readUnsignedShort())
                .timestamp(buf.readUnsignedInt())
                .ssrc(buf.readUnsignedInt());
        if (buf.readableBytes() < 4 * cc) {
            throw new MalformedPacketException("RTP packet truncated in CSRC list");
        }
        for (int i = 0; i < cc; i++) {
            b.addCsrc(buf.readUnsignedInt());
        }
        if (ext) {
            if (buf.readableBytes() < 4) {
                throw new MalformedPacketException("RTP packet truncated in header extension");
            }
            int profile = buf.readUnsignedShort();
            int words = buf.readUnsignedShort();
            if (buf.readableBytes() < 4 * words) {
                throw new MalformedPacketException("RTP header extension length exceeds packet");
            }
            byte[] data = new byte[4 * words];
            buf.readBytes(data);
            b.extension(new HeaderExtension(profile, data));
        }
        int remaining = buf.readableBytes();
        int paddingLength = 0;
        if (padding) {
            if (remaining == 0) {
                throw new MalformedPacketException("RTP padding bit set but no padding present");
            }
            paddingLength = buf.getUnsignedByte(start + length - 1);
            if (paddingLength == 0 || paddingLength > remaining) {
                throw new MalformedPacketException("invalid RTP padding length " + paddingLength);
            }
        }
        byte[] payload = new byte[remaining - paddingLength];
        buf.readBytes(payload);
        buf.skipBytes(paddingLength);
        return b.paddingLength(paddingLength).payload(payload).build();
    }

    /** Writes this packet to {@code out} at its writer index. */
    public void encode(ByteBuf out) {
        int b0 = VERSION << 6 | (paddingLength > 0 ? 0x20 : 0) | (extension != null ? 0x10 : 0) | csrcs.size();
        out.writeByte(b0);
        out.writeByte((marker ? 0x80 : 0) | payloadType);
        out.writeShort(sequenceNumber);
        out.writeInt((int) timestamp);
        out.writeInt((int) ssrc);
        for (long csrc : csrcs) {
            out.writeInt((int) csrc);
        }
        if (extension != null) {
            out.writeShort(extension.profile());
            out.writeShort(extension.data().length / 4);
            out.writeBytes(extension.data());
        }
        out.writeBytes(payload);
        if (paddingLength > 0) {
            out.writeZero(paddingLength - 1);
            out.writeByte(paddingLength);
        }
    }

    /** Encodes this packet into a new buffer from {@code allocator}; the caller owns (and must release) it. */
    public ByteBuf encode(ByteBufAllocator allocator) {
        ByteBuf buf = allocator.buffer(encodedLength());
        try {
            encode(buf);
            return buf;
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------ object

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RtpPacket p)) {
            return false;
        }
        return paddingLength == p.paddingLength && marker == p.marker && payloadType == p.payloadType
                && sequenceNumber == p.sequenceNumber && timestamp == p.timestamp && ssrc == p.ssrc
                && Objects.equals(extension, p.extension) && csrcs.equals(p.csrcs)
                && Arrays.equals(payload, p.payload);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(paddingLength, extension, marker, payloadType, sequenceNumber, timestamp, ssrc, csrcs);
        return 31 * h + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "RtpPacket[pt=" + payloadType + ", seq=" + sequenceNumber + ", ts=" + timestamp
                + ", ssrc=" + Long.toHexString(ssrc) + (marker ? ", M" : "")
                + (csrcs.isEmpty() ? "" : ", csrcs=" + csrcs)
                + (extension != null ? ", ext=" + extension : "")
                + ", payload=" + payload.length + "B]";
    }

    // ------------------------------------------------------------------------------------------ types

    /**
     * RTP header extension (RFC 3550 §5.3.1): a 16-bit profile defined identifier and data whose length is
     * a multiple of four octets. One-byte and two-byte header formats (RFC 8285) can be parsed from
     * {@link #data()} by higher layers.
     *
     * @param profile profile defined 16-bit value (e.g. {@code 0xBEDE} for RFC 8285 one-byte headers)
     * @param data    extension data; its length must be a multiple of 4. Not copied.
     */
    public record HeaderExtension(int profile, byte[] data) {
        public HeaderExtension {
            if (profile < 0 || profile > 0xFFFF) {
                throw new IllegalArgumentException("extension profile out of range: " + profile);
            }
            Objects.requireNonNull(data, "data");
            if (data.length % 4 != 0 || data.length / 4 > 0xFFFF) {
                throw new IllegalArgumentException("extension data length must be a multiple of 4: " + data.length);
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof HeaderExtension e && e.profile == profile && Arrays.equals(e.data, data);
        }

        @Override
        public int hashCode() {
            return 31 * profile + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "HeaderExtension[profile=0x" + Integer.toHexString(profile) + ", data="
                    + HexFormat.of().formatHex(data) + "]";
        }
    }

    /** Mutable builder for {@link RtpPacket}. Not thread-safe. */
    public static final class Builder {
        private int paddingLength;
        private HeaderExtension extension;
        private boolean marker;
        private int payloadType;
        private int sequenceNumber;
        private long timestamp;
        private long ssrc;
        private List<Long> csrcs = new ArrayList<>();
        private byte[] payload;

        private Builder() {
        }

        /** Number of padding octets to append when encoding (0 = none, otherwise 1..255). */
        public Builder paddingLength(int paddingLength) {
            this.paddingLength = paddingLength;
            return this;
        }

        public Builder extension(HeaderExtension extension) {
            this.extension = extension;
            return this;
        }

        public Builder marker(boolean marker) {
            this.marker = marker;
            return this;
        }

        public Builder payloadType(int payloadType) {
            this.payloadType = payloadType;
            return this;
        }

        public Builder sequenceNumber(int sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder ssrc(long ssrc) {
            this.ssrc = ssrc;
            return this;
        }

        public Builder csrcs(List<Long> csrcs) {
            this.csrcs = new ArrayList<>(csrcs);
            return this;
        }

        public Builder addCsrc(long csrc) {
            this.csrcs.add(csrc);
            return this;
        }

        /** Sets the payload; the array is not copied. */
        public Builder payload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        /** @throws IllegalArgumentException if a field is out of range */
        public RtpPacket build() {
            return new RtpPacket(this);
        }
    }
}
