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

class RtcpCodecTest {

    private static String encodeHex(RtcpPacket... packets) {
        ByteBuf buf = RtcpCodec.encode(List.of(packets), ByteBufAllocator.DEFAULT);
        try {
            return ByteBufUtil.hexDump(buf);
        } finally {
            buf.release();
        }
    }

    private static List<RtcpPacket> roundTrip(RtcpPacket... packets) {
        ByteBuf buf = RtcpCodec.encode(List.of(packets), ByteBufAllocator.DEFAULT);
        try {
            assertThat(buf.readableBytes() % 4).isZero();
            return RtcpCodec.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static ByteBuf hex(String hex) {
        return Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex.replace(" ", "")));
    }

    @Test
    void senderReportWithBlocksRoundTripsAndHasCorrectLength() {
        ReportBlock block = new ReportBlock(0x11223344L, 25, -5, 0x0001_0010L, 77, 0xAABBCCDDL, 65_536);
        SenderReport sr = new SenderReport(0xCAFEBABEL, 0xE1234567_80000000L, 160_000, 50, 8000, List.of(block));

        String hex = encodeHex(sr);
        // V=2, RC=1, PT=200, length = (4 + 24 + 24) / 4 - 1 = 12
        assertThat(hex).startsWith("81c8000c" + "cafebabe" + "e123456780000000" + "00027100" + "00000032"
                + "00001f40");
        // report block: ssrc, fraction 25, cumulative lost -5 as 24-bit two's complement
        assertThat(hex).endsWith("11223344" + "19" + "fffffb" + "00010010" + "0000004d" + "aabbccdd" + "00010000");
        assertThat(roundTrip(sr)).containsExactly(sr);
        assertThat(sr.compactNtpTimestamp()).isEqualTo(0x45678000L);
    }

    @Test
    void receiverReportRoundTrips() {
        ReceiverReport empty = new ReceiverReport(1, List.of());
        assertThat(encodeHex(empty)).isEqualTo("80c90001" + "00000001");
        ReceiverReport rr = new ReceiverReport(0xFFFFFFFFL, List.of(
                new ReportBlock(2, 255, ReportBlock.MAX_CUMULATIVE_LOST, 0xFFFFFFFFL, 0, 0, 0),
                new ReportBlock(3, 0, ReportBlock.MIN_CUMULATIVE_LOST, 0, 0xFFFFFFFFL, 1, 2)));
        assertThat(roundTrip(rr)).containsExactly(rr);
    }

    @Test
    void sourceDescriptionIsPaddedToWordBoundaries() {
        // ssrc + (01 03 'abc') = 9 bytes, then 3 null octets -> chunk of 12 bytes, length field 3
        assertThat(encodeHex(SourceDescription.cname(0x01020304L, "abc")))
                .isEqualTo("81ca0003" + "01020304" + "0103616263" + "000000");
        // items ending exactly on a word boundary need a full word of nulls: ssrc + (01 02 'ab') = 8 bytes
        assertThat(encodeHex(SourceDescription.cname(1, "ab")))
                .isEqualTo("81ca0003" + "00000001" + "01026162" + "00000000");

        SourceDescription multi = new SourceDescription(List.of(
                new SourceDescription.Chunk(1, List.of(new SourceDescription.Item(SourceDescription.CNAME, "user@host"),
                        new SourceDescription.Item(SourceDescription.TOOL, "softphone ✓"))),
                new SourceDescription.Chunk(2, List.of())));
        List<RtcpPacket> decoded = roundTrip(multi);
        assertThat(decoded).containsExactly(multi);
        assertThat(((SourceDescription) decoded.get(0)).chunks().get(0).cname()).contains("user@host");
    }

    @Test
    void byeWithAndWithoutReason() {
        assertThat(encodeHex(new Bye(List.of(0x0AL), "bye"))).isEqualTo("81cb0002" + "0000000a" + "03627965");
        assertThat(encodeHex(Bye.of(5))).isEqualTo("81cb0001" + "00000005");
        Bye bye = new Bye(List.of(1L, 2L), "call ended");
        assertThat(roundTrip(bye)).containsExactly(bye);
    }

    @Test
    void feedbackMessages() {
        PictureLossIndication pli = new PictureLossIndication(1, 2);
        assertThat(encodeHex(pli)).isEqualTo("81ce0002" + "00000001" + "00000002");

        FullIntraRequest fir = new FullIntraRequest(1, List.of(new FullIntraRequest.Entry(0xABCDL, 7)));
        assertThat(encodeHex(fir)).isEqualTo("84ce0004" + "00000001" + "00000000" + "0000abcd" + "07000000");
        assertThat(fir.targets(0xABCDL)).isTrue();

        GenericNack nack = new GenericNack(1, 2, List.of(100, 101, 103, 200));
        assertThat(encodeHex(nack)).isEqualTo("81cd0004" + "00000001" + "00000002" + "00640005" + "00c80000");

        assertThat(roundTrip(pli, fir, nack)).containsExactly(pli, fir, nack);
    }

    @Test
    void nackGroupingWrapsAroundSequenceSpace() {
        GenericNack nack = new GenericNack(1, 2, List.of(65535, 0, 15));
        assertThat(encodeHex(nack)).endsWith("ffff" + "8001");
        assertThat(roundTrip(nack)).containsExactly(nack);
    }

    @Test
    void unknownPacketsArePreservedAndPadded() {
        UnknownRtcpPacket app = new UnknownRtcpPacket(RtcpPacket.PT_APP, 3, HexFormat.of().parseHex("0000000141424344"));
        assertThat(encodeHex(app)).isEqualTo("83cc0002" + "0000000141424344");
        UnknownRtcpPacket odd = new UnknownRtcpPacket(210, 0, new byte[]{1, 2, 3, 4, 5});
        // 5 body bytes are padded with 3 octets (the last holding the count) and the P bit is set
        assertThat(encodeHex(odd)).isEqualTo("a0d20002" + "01020304" + "05000003");
        assertThat(roundTrip(app, odd)).containsExactly(app, odd);
        // An unknown PSFB format type is kept as unknown.
        assertThat(RtcpCodec.decode(hex("8f ce 00 02 00 00 00 01 00 00 00 02")).get(0))
                .isInstanceOf(UnknownRtcpPacket.class);
    }

    @Test
    void decodesCompoundPacketWithPaddingOnLastPacket() {
        // RR (no blocks) + SDES CNAME "a" with P bit and 4 padding octets
        ByteBuf buf = hex("80c90001 00000009" + "a1ca0003 00000009 01016100 00000004");
        List<RtcpPacket> packets = RtcpCodec.decode(buf);
        assertThat(packets).containsExactly(new ReceiverReport(9, List.of()), SourceDescription.cname(9, "a"));
    }

    @Test
    void rejectsMalformedCompounds() {
        assertThatThrownBy(() -> RtcpCodec.decode(hex("40c90001 00000009")))
                .isInstanceOf(MalformedPacketException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> RtcpCodec.decode(hex("80c90005 00000009")))
                .isInstanceOf(MalformedPacketException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> RtcpCodec.decode(hex("81c90001 00000009")))
                .isInstanceOf(MalformedPacketException.class);
        assertThatThrownBy(() -> RtcpCodec.decode(hex("80c9")))
                .isInstanceOf(MalformedPacketException.class);
        assertThatThrownBy(() -> RtcpCodec.decode(hex("a0c90001 000000ff")))
                .isInstanceOf(MalformedPacketException.class).hasMessageContaining("padding");
        assertThatThrownBy(() -> RtcpCodec.decode(Unpooled.EMPTY_BUFFER))
                .isInstanceOf(MalformedPacketException.class);
    }

    @Test
    void ntpTimeConversions() {
        long ntp = NtpTime.fromEpochMillis(1_500);
        assertThat(ntp >>> 32).isEqualTo(NtpTime.EPOCH_OFFSET_SECONDS + 1);
        assertThat(ntp & 0xFFFFFFFFL).isEqualTo(0x80000000L);
        assertThat(NtpTime.toEpochMillis(ntp)).isEqualTo(1_500);
        long now = System.currentTimeMillis();
        assertThat(NtpTime.toEpochMillis(NtpTime.fromEpochMillis(now))).isEqualTo(now);
        assertThat(NtpTime.middle32(0x1122334455667788L)).isEqualTo(0x33445566L);
    }
}
