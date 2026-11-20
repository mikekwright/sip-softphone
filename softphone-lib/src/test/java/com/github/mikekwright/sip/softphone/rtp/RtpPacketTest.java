package com.github.mikekwright.sip.softphone.rtp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RtpPacketTest {

    private static ByteBuf hex(String hex) {
        return Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex.replace(" ", "")));
    }

    @Test
    void decodesKnownMinimalPacket() {
        RtpPacket packet = RtpPacket.decode(hex("80 00 00 01 00 00 00 a0 de ad be ef 01 02"));

        assertThat(packet.version()).isEqualTo(2);
        assertThat(packet.marker()).isFalse();
        assertThat(packet.payloadType()).isZero();
        assertThat(packet.sequenceNumber()).isEqualTo(1);
        assertThat(packet.timestamp()).isEqualTo(160);
        assertThat(packet.ssrc()).isEqualTo(0xDEADBEEFL);
        assertThat(packet.csrcs()).isEmpty();
        assertThat(packet.hasExtension()).isFalse();
        assertThat(packet.hasPadding()).isFalse();
        assertThat(packet.payload()).containsExactly(1, 2);
    }

    @Test
    void encodesKnownPacketWithMarkerAndUnsignedFields() {
        RtpPacket packet = RtpPacket.builder().marker(true).payloadType(96).sequenceNumber(0xFFFF)
                .timestamp(0xFFFFFFFFL).ssrc(0x80000000L).payload(new byte[]{(byte) 0xAB}).build();
        ByteBuf buf = packet.encode(ByteBufAllocator.DEFAULT);
        try {
            assertThat(ByteBufUtil.hexDump(buf)).isEqualTo("80e0ffffffffffff80000000ab");
        } finally {
            buf.release();
        }
    }

    @Test
    void roundTripsCsrcsExtensionAndPadding() {
        RtpPacket original = RtpPacket.builder()
                .payloadType(111).sequenceNumber(4242).timestamp(123_456_789L).ssrc(0x01020304L)
                .addCsrc(0xAAAAAAAAL).addCsrc(7)
                .extension(new RtpPacket.HeaderExtension(0xBEDE, new byte[]{0x10, 0x11, 0, 0}))
                .paddingLength(3)
                .payload(new byte[]{9, 8, 7, 6, 5})
                .build();
        ByteBuf buf = original.encode(ByteBufAllocator.DEFAULT);
        try {
            assertThat(buf.readableBytes()).isEqualTo(original.encodedLength()).isEqualTo(12 + 8 + 8 + 5 + 3);
            assertThat(buf.getByte(0) & 0xFF).isEqualTo(0x80 | 0x20 | 0x10 | 2);
            assertThat(buf.getByte(buf.writerIndex() - 1)).isEqualTo((byte) 3);
            RtpPacket decoded = RtpPacket.decode(buf);
            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.payload()).containsExactly(9, 8, 7, 6, 5);
            assertThat(buf.isReadable()).isFalse();
        } finally {
            buf.release();
        }
    }

    @Test
    void stripsPaddingOnDecode() {
        RtpPacket packet = RtpPacket.decode(hex("a0 00 00 01 00 00 00 00 00 00 00 01 aa bb 00 00 03"));
        assertThat(packet.payload()).containsExactly(0xAA, 0xBB);
        assertThat(packet.paddingLength()).isEqualTo(3);
    }

    @Test
    void rejectsMalformedPackets() {
        assertThatThrownBy(() -> RtpPacket.decode(hex("80 00 00 01 00 00")))
                .isInstanceOf(MalformedPacketException.class).hasMessageContaining("too short");
        assertThatThrownBy(() -> RtpPacket.decode(hex("40 00 00 01 00 00 00 00 00 00 00 01")))
                .isInstanceOf(MalformedPacketException.class).hasMessageContaining("version");
        // CC=2 but no CSRCs
        assertThatThrownBy(() -> RtpPacket.decode(hex("82 00 00 01 00 00 00 00 00 00 00 01 00 00")))
                .isInstanceOf(MalformedPacketException.class);
        // padding count larger than the remaining payload
        assertThatThrownBy(() -> RtpPacket.decode(hex("a0 00 00 01 00 00 00 00 00 00 00 01 aa 09")))
                .isInstanceOf(MalformedPacketException.class).hasMessageContaining("padding");
        // padding count zero
        assertThatThrownBy(() -> RtpPacket.decode(hex("a0 00 00 01 00 00 00 00 00 00 00 01 aa 00")))
                .isInstanceOf(MalformedPacketException.class);
        // extension longer than packet
        assertThatThrownBy(() -> RtpPacket.decode(hex("90 00 00 01 00 00 00 00 00 00 00 01 be de 00 02 00 00")))
                .isInstanceOf(MalformedPacketException.class);
    }

    @Test
    void validatesFieldRanges() {
        assertThatThrownBy(() -> RtpPacket.builder().payloadType(128).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RtpPacket.builder().sequenceNumber(65536).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RtpPacket.builder().timestamp(1L << 32).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RtpPacket.builder().csrcs(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L,
                12L, 13L, 14L, 15L, 16L)).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RtpPacket.HeaderExtension(1, new byte[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withersCreateModifiedCopies() {
        RtpPacket packet = RtpPacket.builder().payloadType(0).sequenceNumber(1).payload(new byte[]{1}).build();
        RtpPacket changed = packet.withMarker(true).withSequenceNumber(2).withTimestamp(3).withSsrc(4)
                .withPayloadType(8);
        assertThat(packet.marker()).isFalse();
        assertThat(changed.marker()).isTrue();
        assertThat(changed.sequenceNumber()).isEqualTo(2);
        assertThat(changed.timestamp()).isEqualTo(3);
        assertThat(changed.ssrc()).isEqualTo(4);
        assertThat(changed.payloadType()).isEqualTo(8);
        assertThat(changed.payload()).containsExactly(1);
    }

    @Test
    void demultiplexesRtcpPerRfc5761() {
        assertThat(RtpPacket.looksLikeRtcp(hex("80 c8 00 06"))).isTrue();  // SR, PT 200
        assertThat(RtpPacket.looksLikeRtcp(hex("81 c9 00 07"))).isTrue();  // RR, PT 201
        assertThat(RtpPacket.looksLikeRtcp(hex("81 ce 00 02"))).isTrue();  // PSFB, PT 206
        assertThat(RtpPacket.looksLikeRtcp(hex("80 00 00 01"))).isFalse(); // PCMU
        assertThat(RtpPacket.looksLikeRtcp(hex("80 e0 00 01"))).isFalse(); // PT 96 with marker = 224
        assertThat(RtpPacket.looksLikeRtcp(hex("80 60 00 01"))).isFalse(); // PT 96 without marker
        assertThat(RtpPacket.looksLikeRtcp(hex("80"))).isFalse();
        ByteBuf buf = hex("ff 80 c8");
        buf.readByte();
        assertThat(RtpPacket.looksLikeRtcp(buf)).isTrue();
        assertThat(buf.readerIndex()).isEqualTo(1);
    }
}
