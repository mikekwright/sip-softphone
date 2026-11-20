package com.github.mikekwright.sip.softphone.nat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StunMessageTest {

    private static final byte[] TX = HexFormat.of().parseHex("b7e7a701bc34d686fa87dfae");

    @Test
    void encodesBindingRequestHeaderAndFingerprint() {
        StunMessage req = new StunMessage(StunMessage.BINDING_REQUEST, TX, null, 0, null);
        ByteBuf buf = Unpooled.buffer();
        req.encode(buf);
        assertThat(buf.readableBytes()).isEqualTo(20 + 8);
        assertThat(buf.getUnsignedShort(0)).isEqualTo(0x0001);
        assertThat(buf.getUnsignedShort(2)).isEqualTo(8);
        assertThat(buf.getInt(4)).isEqualTo(0x2112A442);
        byte[] id = new byte[12];
        buf.getBytes(8, id);
        assertThat(id).isEqualTo(TX);
        // FINGERPRINT = CRC-32 of everything before it XOR 0x5354554E (RFC 8489 §14.7)
        assertThat(buf.getUnsignedShort(20)).isEqualTo(0x8028);
        CRC32 crc = new CRC32();
        byte[] head = new byte[20];
        buf.getBytes(0, head);
        crc.update(head);
        assertThat(buf.getInt(24)).isEqualTo((int) crc.getValue() ^ 0x5354554E);
        assertThat(StunMessage.isStun(buf)).isTrue();
        assertThat(StunMessage.decode(buf).isRequest()).isTrue();
    }

    @Test
    void decodesHandBuiltXorMappedAddress() {
        // RFC 8489 §14.2: X-Port = port ^ 0x2112, X-Address = address ^ magic cookie
        // 192.0.2.1:32853 -> port 0x8055 ^ 0x2112 = 0xA147, address 0xC0000201 ^ 0x2112A442 = 0xE112A643
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0x0101).writeShort(12).writeInt(0x2112A442).writeBytes(TX);
        buf.writeShort(0x0020).writeShort(8).writeByte(0).writeByte(1).writeShort(0xA147).writeInt(0xE112A643);
        StunMessage m = StunMessage.decode(buf);
        assertThat(m.isSuccessResponse()).isTrue();
        assertThat(m.mappedAddress()).isEqualTo(new InetSocketAddress("192.0.2.1", 32853));
    }

    @Test
    void roundTripsIpv4AndIpv6Responses() {
        for (String ip : new String[]{"203.0.113.9", "2001:db8:1234:5678:11:2233:4455:6677"}) {
            StunMessage m = StunMessage.bindingSuccess(TX, new InetSocketAddress(ip, 40000), "test");
            ByteBuf buf = Unpooled.buffer();
            m.encode(buf);
            StunMessage back = StunMessage.decode(buf);
            assertThat(back.mappedAddress()).isEqualTo(new InetSocketAddress(ip, 40000));
            assertThat(back.software()).isEqualTo("test");
        }
    }

    @Test
    void rejectsCorruptedFingerprintAndRecognizesOtherProtocols() {
        ByteBuf buf = Unpooled.buffer();
        StunMessage.bindingSuccess(TX, new InetSocketAddress("198.51.100.1", 5060), null).encode(buf);
        buf.setByte(buf.writerIndex() - 1, buf.getByte(buf.writerIndex() - 1) ^ 0x01);
        assertThatThrownBy(() -> StunMessage.decode(buf)).hasMessageContaining("FINGERPRINT");

        ByteBuf rtp = Unpooled.wrappedBuffer(new byte[]{(byte) 0x80, 0, 0, 1, 0, 0, 0, 0, 0x21, 0x12, (byte) 0xA4,
                0x42, 0, 0, 0, 0, 0, 0, 0, 0});
        assertThat(StunMessage.isStun(rtp)).isFalse();
        assertThat(StunMessage.isStun(Unpooled.copiedBuffer("INVITE sip:a@b SIP/2.0\r\n\r\n",
                java.nio.charset.StandardCharsets.US_ASCII))).isFalse();
    }
}
