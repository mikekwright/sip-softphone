package com.github.mikekwright.sip.softphone.media.video;

import com.github.mikekwright.sip.softphone.rtp.RtpPacket;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoPayloadTest {

    private static final Random RANDOM = new Random(42);

    /** Converts payloads of one frame into RTP packets (marker on the last). */
    private static final class PacketSource {
        private int seq = 65_530; // exercise sequence wrap-around
        private final long ssrc = 0x1234;

        List<RtpPacket> packets(List<byte[]> payloads, long timestamp) {
            List<RtpPacket> packets = new ArrayList<>();
            for (int i = 0; i < payloads.size(); i++) {
                packets.add(RtpPacket.builder().payloadType(96).sequenceNumber(seq).timestamp(timestamp).ssrc(ssrc)
                        .marker(i == payloads.size() - 1).payload(payloads.get(i)).build());
                seq = (seq + 1) & 0xFFFF;
            }
            return packets;
        }
    }

    private static EncodedVideoFrame push(VideoDepacketizer depacketizer, List<RtpPacket> packets) {
        EncodedVideoFrame result = null;
        for (RtpPacket packet : packets) {
            EncodedVideoFrame frame = depacketizer.push(packet);
            if (frame != null) {
                assertThat(result).as("only the marker packet completes a frame").isNull();
                result = frame;
            }
        }
        return result;
    }

    private static byte[] vp8Frame(int size, boolean key) {
        byte[] data = new byte[size];
        RANDOM.nextBytes(data);
        data[0] = (byte) (key ? data[0] & ~1 : data[0] | 1);
        return data;
    }

    // ===================================================================================== VP8

    @Test
    void vp8PacketizerUsesMinimalDescriptor() {
        EncodedVideoFrame frame = new EncodedVideoFrame(vp8Frame(2500, true), true, 0, "VP8");
        List<byte[]> payloads = new Vp8Packetizer().packetize(frame, 1000);
        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(0)[0]).isEqualTo((byte) 0x10);
        assertThat(payloads.get(1)[0]).isEqualTo((byte) 0x00);
        assertThat(payloads.get(2)[0]).isEqualTo((byte) 0x00);
        assertThat(payloads).allMatch(p -> p.length <= 1000);
        assertThat(payloads.get(2)).hasSize(1 + 2500 - 2 * 999);
        assertThat(new Vp8Packetizer().packetize(new EncodedVideoFrame(new byte[0], true, 0, "VP8"), 100))
                .containsExactly(new byte[]{0x10});
    }

    @Test
    void vp8RoundTripWithFragmentationAndTimestamps() {
        Vp8Packetizer packetizer = new Vp8Packetizer();
        Vp8Depacketizer depacketizer = new Vp8Depacketizer();
        PacketSource source = new PacketSource();
        long baseTimestamp = 0xFFFFF000L; // wraps during the test

        for (int i = 0; i < 5; i++) {
            boolean key = i == 0;
            EncodedVideoFrame frame = new EncodedVideoFrame(vp8Frame(500 + 1000 * i, key), key, 0, "VP8");
            List<RtpPacket> packets = source.packets(packetizer.packetize(frame, 1200),
                    (baseTimestamp + 3000L * i) & 0xFFFFFFFFL);
            EncodedVideoFrame received = push(depacketizer, packets);
            assertThat(received).isNotNull();
            assertThat(received.data()).isEqualTo(frame.data());
            assertThat(received.keyFrame()).isEqualTo(key);
            assertThat(received.codec()).isEqualTo("VP8");
            assertThat(received.timestampMicros()).isEqualTo(i * 33_333L + i / 3);
        }
        assertThat(depacketizer.consumeKeyFrameNeeded()).isFalse();
    }

    @Test
    void vp8LossDropsFrameAndWaitsForKeyFrame() {
        Vp8Packetizer packetizer = new Vp8Packetizer();
        Vp8Depacketizer depacketizer = new Vp8Depacketizer();
        PacketSource source = new PacketSource();

        assertThat(push(depacketizer, source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(3000, true), true, 0, "VP8"), 1200), 0))).isNotNull();

        // lose the middle packet of a delta frame
        List<RtpPacket> lossy = new ArrayList<>(source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(3000, false), false, 0, "VP8"), 1200), 3000));
        lossy.remove(1);
        assertThat(push(depacketizer, lossy)).isNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isFalse();

        // the following intact delta frame is undecodable and dropped too
        assertThat(push(depacketizer, source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(800, false), false, 0, "VP8"), 1200), 6000))).isNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();

        // a key frame resynchronises
        byte[] key = vp8Frame(2000, true);
        EncodedVideoFrame resumed = push(depacketizer, source.packets(packetizer.packetize(
                new EncodedVideoFrame(key, true, 0, "VP8"), 1200), 9000));
        assertThat(resumed).isNotNull();
        assertThat(resumed.data()).isEqualTo(key);
        assertThat(depacketizer.consumeKeyFrameNeeded()).isFalse();
    }

    @Test
    void vp8FirstFrameMustBeKeyFrameAndLostMarkerIsDetected() {
        Vp8Packetizer packetizer = new Vp8Packetizer();
        Vp8Depacketizer depacketizer = new Vp8Depacketizer();
        PacketSource source = new PacketSource();
        assertThat(push(depacketizer, source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(100, false), false, 0, "VP8"), 1200), 0))).isNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();

        // key frame whose marker packet is lost, followed by the next frame
        List<RtpPacket> first = new ArrayList<>(source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(2000, true), true, 0, "VP8"), 1200), 3000));
        first.remove(first.size() - 1);
        source.seq = (source.seq - 1) & 0xFFFF; // the lost packet's number is reused: no sequence gap
        assertThat(push(depacketizer, first)).isNull();
        EncodedVideoFrame next = push(depacketizer, source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(500, true), true, 0, "VP8"), 1200), 6000));
        assertThat(next).isNotNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();
    }

    @Test
    void vp8DepacketizerSkipsExtendedDescriptors() {
        byte[] media = vp8Frame(10, true);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x90);              // X=1, S=1, PID=0
        payload.write(0xF0);              // I, L, T, K present
        payload.write(0x80 | 0x12);       // M=1: 15-bit picture ID
        payload.write(0x34);
        payload.write(0x05);              // TL0PICIDX
        payload.write(0x40);              // TID/Y/KEYIDX
        payload.writeBytes(media);
        RtpPacket packet = RtpPacket.builder().payloadType(96).marker(true).payload(payload.toByteArray()).build();
        EncodedVideoFrame frame = new Vp8Depacketizer().push(packet);
        assertThat(frame).isNotNull();
        assertThat(frame.data()).isEqualTo(media);

        assertThat(Vp8Depacketizer.descriptorLength(new byte[]{(byte) 0x90, (byte) 0x80, 0x7F})).isEqualTo(3);
        assertThat(Vp8Depacketizer.descriptorLength(new byte[]{(byte) 0x90, (byte) 0x80, (byte) 0x80, 1})).isEqualTo(4);
        assertThat(Vp8Depacketizer.descriptorLength(new byte[]{(byte) 0x90, (byte) 0x80})).isEqualTo(-1);
        assertThat(Vp8Depacketizer.descriptorLength(new byte[]{(byte) 0x90, 0x20, 0})).isEqualTo(3);
        assertThat(Vp8Depacketizer.descriptorLength(new byte[0])).isEqualTo(-1);
    }

    @Test
    void depacketizerIgnoresDuplicatesAndResetsOnNewSource() {
        Vp8Packetizer packetizer = new Vp8Packetizer();
        Vp8Depacketizer depacketizer = new Vp8Depacketizer();
        PacketSource source = new PacketSource();
        List<RtpPacket> packets = source.packets(packetizer.packetize(
                new EncodedVideoFrame(vp8Frame(2000, true), true, 0, "VP8"), 1200), 0);
        assertThat(depacketizer.push(packets.get(0))).isNull();
        assertThat(depacketizer.push(packets.get(0))).isNull(); // duplicate ignored
        assertThat(depacketizer.push(packets.get(1))).isNotNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isFalse();

        RtpPacket other = packets.get(1).withSsrc(99).withSequenceNumber(7);
        assertThat(depacketizer.push(other)).isNull(); // continuation without start from a new source
        depacketizer.reset();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isFalse();
    }

    // ===================================================================================== H.264

    private static byte[] nal(int header, int size) {
        byte[] nal = new byte[size];
        RANDOM.nextBytes(nal);
        nal[0] = (byte) header;
        // avoid accidental start codes and trailing zeros inside synthetic NAL units
        for (int i = 1; i < size; i++) {
            if (nal[i] == 0) {
                nal[i] = 1;
            }
        }
        return nal;
    }

    @Test
    void splitsAnnexBWithThreeAndFourByteStartCodes() {
        byte[] sps = nal(0x67, 10);
        byte[] pps = nal(0x68, 4);
        byte[] idr = nal(0x65, 20);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(new byte[]{0, 0, 0, 1});
        stream.writeBytes(sps);
        stream.writeBytes(new byte[]{0, 0, 1});
        stream.writeBytes(pps);
        stream.writeBytes(new byte[]{0, 0, 0, 0, 1}); // trailing_zero_8bits before a 4-byte start code
        stream.writeBytes(idr);
        List<byte[]> nals = H264NalUnits.split(stream.toByteArray());
        assertThat(nals).containsExactly(sps, pps, idr);
        assertThat(H264NalUnits.type(nals.get(2))).isEqualTo(H264NalUnits.TYPE_IDR);
        assertThat(H264NalUnits.containsIdr(stream.toByteArray())).isTrue();
        assertThat(H264NalUnits.split(sps)).containsExactly(sps); // no start code at all
        assertThat(H264NalUnits.toAnnexB(List.of(sps, pps))).startsWith(0, 0, 0, 1, 0x67);
    }

    @Test
    void h264RoundTripWithSingleNalAndFuA() {
        byte[] sps = nal(0x67, 12);
        byte[] pps = nal(0x68, 5);
        byte[] idr = nal(0x65, 3000);
        byte[] annexB = H264NalUnits.toAnnexB(List.of(sps, pps, idr));
        EncodedVideoFrame frame = new EncodedVideoFrame(annexB, true, 0, "H264");

        List<byte[]> payloads = new H264Packetizer().packetize(frame, 1200);
        // SPS, PPS as single NAL units; 2999 bytes of IDR body in 1198-byte FU-A fragments -> 3
        assertThat(payloads).hasSize(5);
        assertThat(payloads.get(0)).isEqualTo(sps);
        assertThat(payloads.get(2)[0] & 0x1F).isEqualTo(28);
        assertThat(payloads.get(2)[0] & 0xE0).isEqualTo(0x65 & 0xE0);
        assertThat(payloads.get(2)[1] & 0xFF).isEqualTo(0x80 | 5);
        assertThat(payloads.get(3)[1] & 0xFF).isEqualTo(5);
        assertThat(payloads.get(4)[1] & 0xFF).isEqualTo(0x40 | 5);
        assertThat(payloads).allMatch(p -> p.length <= 1200);

        H264Depacketizer depacketizer = new H264Depacketizer();
        PacketSource source = new PacketSource();
        EncodedVideoFrame received = push(depacketizer, source.packets(payloads, 1000));
        assertThat(received).isNotNull();
        assertThat(received.data()).isEqualTo(annexB);
        assertThat(received.keyFrame()).isTrue();
        assertThat(received.codec()).isEqualTo("H264");

        byte[] delta = H264NalUnits.toAnnexB(List.of(nal(0x41, 1500)));
        EncodedVideoFrame next = push(depacketizer, source.packets(
                new H264Packetizer().packetize(new EncodedVideoFrame(delta, false, 0, "H264"), 1200), 4000));
        assertThat(next.data()).isEqualTo(delta);
        assertThat(next.keyFrame()).isFalse();
        assertThat(next.timestampMicros()).isEqualTo(33_333);
    }

    @Test
    void h264DepacketizesStapA() {
        byte[] sps = nal(0x67, 12);
        byte[] pps = nal(0x68, 5);
        byte[] idr = nal(0x65, 100);
        ByteArrayOutputStream stap = new ByteArrayOutputStream();
        stap.write(0x78); // F=0, NRI=3, type 24
        stap.write(0);
        stap.write(sps.length);
        stap.writeBytes(sps);
        stap.write(0);
        stap.write(pps.length);
        stap.writeBytes(pps);
        PacketSource source = new PacketSource();
        EncodedVideoFrame frame = push(new H264Depacketizer(), source.packets(List.of(stap.toByteArray(), idr), 0));
        assertThat(frame).isNotNull();
        assertThat(frame.data()).isEqualTo(H264NalUnits.toAnnexB(List.of(sps, pps, idr)));
        assertThat(frame.keyFrame()).isTrue();

        // malformed STAP-A (size beyond payload) breaks the frame
        H264Depacketizer depacketizer = new H264Depacketizer();
        byte[] bad = {0x78, 0, 50, 1, 2};
        assertThat(push(depacketizer, source.packets(List.of(bad), 3000))).isNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();
    }

    @Test
    void h264FragmentLossDropsFrame() {
        byte[] idr = H264NalUnits.toAnnexB(List.of(nal(0x65, 5000)));
        List<byte[]> payloads = new H264Packetizer().packetize(new EncodedVideoFrame(idr, true, 0, "H264"), 1200);
        assertThat(payloads).hasSize(5);
        H264Depacketizer depacketizer = new H264Depacketizer();
        PacketSource source = new PacketSource();

        // the FU-A start is lost: the remaining fragments cannot be used
        List<RtpPacket> packets = new ArrayList<>(source.packets(payloads, 0));
        packets.remove(0);
        assertThat(push(depacketizer, packets)).isNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();

        // the FU-A end is missing although the marker arrives (sender bug): frame incomplete
        List<byte[]> truncated = new ArrayList<>(payloads.subList(0, 2));
        assertThat(push(depacketizer, source.packets(truncated, 3000))).isNull();
        assertThat(depacketizer.consumeKeyFrameNeeded()).isTrue();

        // an intact IDR afterwards is delivered
        assertThat(push(depacketizer, source.packets(payloads, 6000))).isNotNull();
        assertThatThrownBy(() -> new H264Packetizer().packetize(new EncodedVideoFrame(new byte[0], true, 0, "H264"),
                1200)).isInstanceOf(IllegalArgumentException.class);
    }

    // ===================================================================================== registry

    @Test
    void payloadFormatRegistry() {
        VideoPayloadFormats formats = VideoPayloadFormats.defaults();
        assertThat(formats.encodingNames()).containsExactly("VP8", "H264");
        assertThat(formats.supports("vp8")).isTrue();
        assertThat(formats.createPacketizer("h264")).get().isInstanceOf(H264Packetizer.class);
        assertThat(formats.createDepacketizer("VP8")).get().isInstanceOf(Vp8Depacketizer.class);
        assertThat(formats.createDepacketizer("VP8").get()).isNotSameAs(formats.createDepacketizer("VP8").get());
        assertThat(formats.createPacketizer("VP9")).isEmpty();

        formats.register("x-raw", Vp8Packetizer::new, Vp8Depacketizer::new);
        assertThat(formats.supports("X-RAW")).isTrue();
        assertThat(VideoPayloadFormats.defaults().supports("x-raw")).isFalse();
    }

    @Test
    void encodedFrameEqualityUsesContent() {
        EncodedVideoFrame a = new EncodedVideoFrame(new byte[]{1, 2}, true, 5, "VP8");
        EncodedVideoFrame b = new EncodedVideoFrame(new byte[]{1, 2}, true, 5, "VP8");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("VP8", "2B", "key");
    }
}
