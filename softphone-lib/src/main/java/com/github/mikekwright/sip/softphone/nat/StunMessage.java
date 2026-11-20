package com.github.mikekwright.sip.softphone.nat;

import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;

/**
 * A STUN message (RFC 8489 §5): the Binding request/response subset needed to discover the public
 * ("server reflexive") transport address that a NAT assigned to a local UDP socket.
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0 0|     STUN Message Type     |         Message Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Magic Cookie                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     Transaction ID (96 bits)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * Supported attributes: XOR-MAPPED-ADDRESS (§14.2), MAPPED-ADDRESS (§14.1, legacy servers), ERROR-CODE (§14.8),
 * SOFTWARE (§14.14) and FINGERPRINT (§14.7). Unknown attributes are skipped.
 *
 * @param type          message type, e.g. {@link #BINDING_REQUEST}
 * @param transactionId 12 byte transaction id
 * @param mappedAddress the reflexive address (responses), or {@code null}
 * @param errorCode     the ERROR-CODE value for error responses, or 0
 * @param software      SOFTWARE attribute value, or {@code null}
 */
public record StunMessage(int type, byte[] transactionId, InetSocketAddress mappedAddress, int errorCode,
                          String software) {

    public static final int MAGIC_COOKIE = 0x2112A442;
    public static final int BINDING_REQUEST = 0x0001;
    public static final int BINDING_INDICATION = 0x0011;
    public static final int BINDING_SUCCESS = 0x0101;
    public static final int BINDING_ERROR = 0x0111;

    static final int ATTR_MAPPED_ADDRESS = 0x0001;
    static final int ATTR_ERROR_CODE = 0x0009;
    static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    static final int ATTR_SOFTWARE = 0x8022;
    static final int ATTR_FINGERPRINT = 0x8028;
    private static final int FINGERPRINT_XOR = 0x5354554E;
    private static final int HEADER = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    public StunMessage {
        if (transactionId == null || transactionId.length != 12) {
            throw new IllegalArgumentException("transaction id must be 12 bytes");
        }
    }

    /** A Binding request with a fresh random transaction id. */
    public static StunMessage bindingRequest() {
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        return new StunMessage(BINDING_REQUEST, id, null, 0, null);
    }

    /** The success response to a request, reporting {@code mapped} as XOR-MAPPED-ADDRESS. */
    public static StunMessage bindingSuccess(byte[] transactionId, InetSocketAddress mapped, String software) {
        return new StunMessage(BINDING_SUCCESS, transactionId, mapped, 0, software);
    }

    public boolean isRequest() {
        return (type & 0x0110) == 0x0000;
    }

    public boolean isSuccessResponse() {
        return (type & 0x0110) == 0x0100;
    }

    public boolean isErrorResponse() {
        return (type & 0x0110) == 0x0110;
    }

    /** Transaction id as hex, usable as a map key. */
    public String transactionKey() {
        return HexFormat.of().formatHex(transactionId);
    }

    /**
     * RFC 7983 / RFC 8489 §5 demultiplexing test: the first two bits are zero, the length is a multiple of four,
     * and bytes 4-7 contain the magic cookie. Does not consume the buffer.
     */
    public static boolean isStun(ByteBuf buf) {
        int i = buf.readerIndex();
        if (buf.readableBytes() < HEADER) {
            return false;
        }
        return (buf.getByte(i) & 0xC0) == 0 && (buf.getUnsignedShort(i + 2) & 0x03) == 0
                && buf.getInt(i + 4) == MAGIC_COOKIE
                && buf.getUnsignedShort(i + 2) + HEADER == buf.readableBytes();
    }

    /** Encodes the message, always appending a FINGERPRINT attribute. */
    public void encode(ByteBuf out) {
        int start = out.writerIndex();
        out.writeShort(type);
        out.writeShort(0); // length, patched below
        out.writeInt(MAGIC_COOKIE);
        out.writeBytes(transactionId);
        if (mappedAddress != null) {
            writeXorAddress(out, mappedAddress);
        }
        if (errorCode > 0) {
            out.writeShort(ATTR_ERROR_CODE);
            out.writeShort(4);
            out.writeShort(0);
            out.writeByte(errorCode / 100);
            out.writeByte(errorCode % 100);
        }
        if (software != null) {
            byte[] s = software.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeShort(ATTR_SOFTWARE);
            out.writeShort(s.length);
            out.writeBytes(s);
            out.writeZero((4 - s.length % 4) % 4);
        }
        // FINGERPRINT (§14.7): CRC-32 of the message up to the attribute, with the length already covering it
        out.setShort(start + 2, out.writerIndex() - start - HEADER + 8);
        CRC32 crc = new CRC32();
        byte[] soFar = new byte[out.writerIndex() - start];
        out.getBytes(start, soFar);
        crc.update(soFar);
        out.writeShort(ATTR_FINGERPRINT);
        out.writeShort(4);
        out.writeInt((int) crc.getValue() ^ FINGERPRINT_XOR);
    }

    private void writeXorAddress(ByteBuf out, InetSocketAddress addr) {
        byte[] ip = addr.getAddress().getAddress();
        out.writeShort(ATTR_XOR_MAPPED_ADDRESS);
        out.writeShort(4 + ip.length);
        out.writeByte(0);
        out.writeByte(ip.length == 4 ? 0x01 : 0x02);
        out.writeShort(addr.getPort() ^ (MAGIC_COOKIE >>> 16));
        byte[] key = xorKey();
        for (int i = 0; i < ip.length; i++) {
            out.writeByte(ip[i] ^ key[i]);
        }
    }

    /** Magic cookie followed by the transaction id: the XOR key for addresses (§14.2). */
    private byte[] xorKey() {
        byte[] key = new byte[16];
        key[0] = 0x21;
        key[1] = 0x12;
        key[2] = (byte) 0xA4;
        key[3] = 0x42;
        System.arraycopy(transactionId, 0, key, 4, 12);
        return key;
    }

    /**
     * Decodes a STUN message. Throws {@link IllegalArgumentException} for malformed input or a wrong
     * FINGERPRINT. Does not consume the buffer.
     */
    public static StunMessage decode(ByteBuf buf) {
        if (!isStun(buf)) {
            throw new IllegalArgumentException("not a STUN message");
        }
        int base = buf.readerIndex();
        int type = buf.getUnsignedShort(base);
        int length = buf.getUnsignedShort(base + 2);
        byte[] id = new byte[12];
        buf.getBytes(base + 8, id);
        InetSocketAddress xorMapped = null;
        InetSocketAddress mapped = null;
        int error = 0;
        String software = null;
        int pos = base + HEADER;
        int end = base + HEADER + length;
        while (pos + 4 <= end) {
            int attr = buf.getUnsignedShort(pos);
            int len = buf.getUnsignedShort(pos + 2);
            int value = pos + 4;
            if (value + len > end) {
                throw new IllegalArgumentException("STUN attribute overruns message");
            }
            switch (attr) {
                case ATTR_XOR_MAPPED_ADDRESS -> xorMapped = readAddress(buf, value, len, id, true);
                case ATTR_MAPPED_ADDRESS -> mapped = readAddress(buf, value, len, id, false);
                case ATTR_ERROR_CODE -> error = len >= 4
                        ? (buf.getByte(value + 2) & 0x07) * 100 + (buf.getByte(value + 3) & 0xFF) : 0;
                case ATTR_SOFTWARE -> software = buf.toString(value, len, java.nio.charset.StandardCharsets.UTF_8);
                case ATTR_FINGERPRINT -> {
                    CRC32 crc = new CRC32();
                    byte[] covered = new byte[pos - base];
                    buf.getBytes(base, covered);
                    crc.update(covered);
                    if (((int) crc.getValue() ^ FINGERPRINT_XOR) != buf.getInt(value)) {
                        throw new IllegalArgumentException("STUN FINGERPRINT mismatch");
                    }
                }
                default -> {
                    // comprehension-optional or unknown attribute: ignore
                }
            }
            pos = value + len + (4 - len % 4) % 4;
        }
        return new StunMessage(type, id, xorMapped != null ? xorMapped : mapped, error, software);
    }

    private static InetSocketAddress readAddress(ByteBuf buf, int at, int len, byte[] id, boolean xor) {
        int family = buf.getByte(at + 1);
        int ipLen = family == 0x01 ? 4 : family == 0x02 ? 16 : -1;
        if (ipLen < 0 || len < 4 + ipLen) {
            throw new IllegalArgumentException("bad STUN address attribute");
        }
        int port = buf.getUnsignedShort(at + 2);
        byte[] ip = new byte[ipLen];
        buf.getBytes(at + 4, ip);
        if (xor) {
            port ^= MAGIC_COOKIE >>> 16;
            byte[] key = new StunMessage(BINDING_REQUEST, id, null, 0, null).xorKey();
            for (int i = 0; i < ipLen; i++) {
                ip[i] ^= key[i];
            }
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StunMessage m && m.type == type && Arrays.equals(m.transactionId, transactionId);
    }

    @Override
    public int hashCode() {
        return 31 * type + Arrays.hashCode(transactionId);
    }

    @Override
    public String toString() {
        return String.format("STUN[type=0x%04x, tx=%s, mapped=%s%s]", type, transactionKey(), mappedAddress,
                errorCode > 0 ? ", error=" + errorCode : "");
    }
}
