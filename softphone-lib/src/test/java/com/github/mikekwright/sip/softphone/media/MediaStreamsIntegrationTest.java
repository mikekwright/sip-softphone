package com.github.mikekwright.sip.softphone.media;

import com.github.mikekwright.sip.softphone.media.audio.RecordingSink;
import com.github.mikekwright.sip.softphone.media.audio.ToneDetector;
import com.github.mikekwright.sip.softphone.media.audio.ToneSource;
import com.github.mikekwright.sip.softphone.media.dtmf.DtmfTones;
import com.github.mikekwright.sip.softphone.media.video.EncodedVideoFrame;
import com.github.mikekwright.sip.softphone.rtp.PortAllocator;
import com.github.mikekwright.sip.softphone.rtp.RtpSession;
import com.github.mikekwright.sip.softphone.rtp.RtpSessionConfig;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests of audio and video streams between two RTP sessions on the loopback interface.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MediaStreamsIntegrationTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final int TELEPHONE_EVENT_PT = 101;
    private static final int VP8_PT = 96;

    private static EventLoopGroup group;
    private static PortAllocator ports;

    @BeforeAll
    static void setUp() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        int base = 40_000 + 2 * ThreadLocalRandom.current().nextInt(4_000);
        ports = new PortAllocator(base, base + 1_999);
    }

    @AfterAll
    static void tearDown() throws Exception {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
    }

    private static RtpSession bind() throws Exception {
        return RtpSession.bind(group, LOOPBACK, ports, RtpSessionConfig.defaults()).get(5, TimeUnit.SECONDS);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private static MediaStreamSpec audioSpec(InetSocketAddress remote, boolean telephoneEvents, boolean send) {
        MediaFormat pcmu = new MediaFormat(0, "PCMU", 8000);
        MediaFormat events = new MediaFormat(TELEPHONE_EVENT_PT, "telephone-event", 8000, 1, "0-15", List.of());
        Integer te = telephoneEvents ? TELEPHONE_EVENT_PT : null;
        return new MediaStreamSpec(MediaType.AUDIO, remote, null, false, pcmu,
                telephoneEvents ? List.of(pcmu, events) : List.of(pcmu), te, te, send, true, 20);
    }

    private static MediaStreamSpec videoSpec(InetSocketAddress remote) {
        MediaFormat vp8 = new MediaFormat(VP8_PT, "VP8", 90_000, 1, null, List.of("nack pli", "ccm fir"));
        return new MediaStreamSpec(MediaType.VIDEO, remote, null, false, vp8, List.of(vp8), null, null,
                true, true, 0);
    }

    private record Dtmf(char digit, int durationMillis) {
    }

    @Test
    void audioToneAndTelephoneEventsFlowBetweenStreams() throws Exception {
        RtpSession sessionA = bind();
        RtpSession sessionB = bind();
        AudioStream a = new AudioStream(sessionA);
        AudioStream b = new AudioStream(sessionB);
        RecordingSink recording = new RecordingSink();
        List<Dtmf> digits = new CopyOnWriteArrayList<>();
        try {
            a.setSource(new ToneSource(1000, 0.5));
            b.setSink(recording);
            b.setListener(new AudioStreamListener() {
                @Override
                public void onDtmf(char digit, int durationMillis) {
                    digits.add(new Dtmf(digit, durationMillis));
                }
            });
            b.start(audioSpec(sessionA.localRtpAddress(), true, true));
            a.start(audioSpec(sessionB.localRtpAddress(), true, true));

            assertThat(recording.awaitSamples(4000, Duration.ofSeconds(10))).isTrue();
            assertThat(recording.sampleRate()).isEqualTo(8000);
            short[] heard = recording.lastSamples(3200);
            assertThat(ToneDetector.relativePower(heard, 8000, 1000)).isGreaterThan(0.9);
            assertThat(ToneDetector.dominantFrequency(heard, 8000, 500, 1000, 2000)).isEqualTo(1000);

            a.sendDtmf("123#").get(10, TimeUnit.SECONDS);
            await("4 DTMF digits", () -> digits.size() >= 4);
            Thread.sleep(300); // give any duplicate report a chance to show up
            assertThat(digits).extracting(Dtmf::digit).containsExactly('1', '2', '3', '#');
            assertThat(digits).extracting(Dtmf::durationMillis).containsOnly(100);

            assertThat(a.packetsSent()).isGreaterThan(0);
            assertThat(b.packetsReceived()).isGreaterThan(0);
            assertThat(b.packetsLost()).isZero();

            // hold: A stops sending, its clock keeps running
            a.update(audioSpec(sessionB.localRtpAddress(), true, false));
            Thread.sleep(100);
            long sentBeforeHold = a.packetsSent();
            Thread.sleep(200);
            assertThat(a.packetsSent()).isEqualTo(sentBeforeHold);
            a.update(audioSpec(sessionB.localRtpAddress(), true, true));
            await("audio after resume", () -> a.packetsSent() > sentBeforeHold + 3);
        } finally {
            CompletableFuture.allOf(a.stop(), b.stop()).get(5, TimeUnit.SECONDS);
        }
        assertThat(sessionA.isClosed()).isTrue();
        assertThat(sessionB.isClosed()).isTrue();
        assertThat(sessionA.senderStatistics().packetsSent()).isEqualTo(a.packetsSent());
        assertThat(a.sendDtmf("1")).isCompletedExceptionally();
    }

    @Test
    void inBandDtmfFallbackWithoutTelephoneEvents() throws Exception {
        RtpSession sessionA = bind();
        RtpSession sessionB = bind();
        AudioStream a = new AudioStream(sessionA);
        AudioStream b = new AudioStream(sessionB);
        RecordingSink recording = new RecordingSink();
        try {
            b.setSink(recording);
            b.start(audioSpec(sessionA.localRtpAddress(), false, true));
            a.start(audioSpec(sessionB.localRtpAddress(), false, true));
            assertThat(recording.awaitSamples(800, Duration.ofSeconds(10))).isTrue();

            a.sendDtmf("5").get(10, TimeUnit.SECONDS);
            Thread.sleep(200);
            short[] samples = recording.samples();
            boolean found = false;
            for (int offset = 0; offset + 320 <= samples.length && !found; offset += 160) {
                found = DtmfTones.detect(Arrays.copyOfRange(samples, offset, offset + 320), 8000)
                        .filter(d -> d == '5').isPresent();
            }
            assertThat(found).as("in-band DTMF '5' detected in received audio").isTrue();
            assertThatThrownBy(() -> a.sendDtmf("5x").join()).hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            CompletableFuture.allOf(a.stop(), b.stop()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void videoFramesAndKeyFrameRequestsFlowBetweenStreams() throws Exception {
        RtpSession sessionA = bind();
        RtpSession sessionB = bind();
        VideoStream a = new VideoStream(sessionA);
        VideoStream b = new VideoStream(sessionB);
        List<EncodedVideoFrame> received = new CopyOnWriteArrayList<>();
        CountDownLatch keyFrameRequested = new CountDownLatch(1);
        try {
            b.setSink(received::add);
            a.setKeyFrameRequestListener(keyFrameRequested::countDown);
            b.start(videoSpec(sessionA.localRtpAddress()));
            a.start(videoSpec(sessionB.localRtpAddress()));

            Random random = new Random(7);
            List<EncodedVideoFrame> sent = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                byte[] data = new byte[i == 0 ? 6000 : 1500 + 300 * i];
                random.nextBytes(data);
                boolean key = i == 0;
                data[0] = (byte) (key ? data[0] & ~1 : data[0] | 1);
                EncodedVideoFrame frame = new EncodedVideoFrame(data, key, i * 33_333L, "VP8");
                sent.add(frame);
                a.sendFrame(frame);
                Thread.sleep(5);
            }
            await("4 video frames", () -> received.size() >= 4);
            for (int i = 0; i < 4; i++) {
                assertThat(received.get(i).data()).isEqualTo(sent.get(i).data());
                assertThat(received.get(i).keyFrame()).isEqualTo(i == 0);
                assertThat(received.get(i).codec()).isEqualTo("VP8");
                // relative timestamps survive the 90 kHz round trip, give or take one 11 us clock tick
                assertThat(received.get(i).timestampMicros()).isBetween(i * 33_333L - 12, i * 33_333L);
            }
            assertThat(a.framesSent()).isEqualTo(4);
            assertThat(b.framesReceived()).isEqualTo(4);
            assertThat(b.keyFrameRequestsSent()).isZero();

            b.requestKeyFrame();
            assertThat(keyFrameRequested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(a.keyFrameRequestsReceived()).isEqualTo(1);
            assertThat(b.keyFrameRequestsSent()).isEqualTo(1);
        } finally {
            CompletableFuture.allOf(a.stop(), b.stop()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void videoReceiverRequestsKeyFrameWhenFirstFrameIsNotDecodable() throws Exception {
        RtpSession sessionA = bind();
        RtpSession sessionB = bind();
        VideoStream a = new VideoStream(sessionA);
        VideoStream b = new VideoStream(sessionB);
        CountDownLatch keyFrameRequested = new CountDownLatch(1);
        try {
            a.setKeyFrameRequestListener(keyFrameRequested::countDown);
            b.start(videoSpec(sessionA.localRtpAddress()));
            a.start(videoSpec(sessionB.localRtpAddress()));
            byte[] delta = new byte[100];
            delta[0] = 1; // inter frame
            a.sendFrame(new EncodedVideoFrame(delta, false, 0, "VP8"));
            assertThat(keyFrameRequested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(b.framesReceived()).isZero();
        } finally {
            CompletableFuture.allOf(a.stop(), b.stop()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void streamsValidateSpecs() throws Exception {
        RtpSession session = bind();
        AudioStream audio = new AudioStream(session);
        try {
            assertThatThrownBy(() -> audio.start(videoSpec(session.localRtpAddress())))
                    .isInstanceOf(IllegalArgumentException.class);
            MediaFormat opus = new MediaFormat(111, "opus", 48_000, 2, null, List.of());
            assertThatThrownBy(() -> audio.start(new MediaStreamSpec(MediaType.AUDIO, null, null, false, opus,
                    List.of(opus), null, null, true, true, 20))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> audio.update(audioSpec(null, false, true)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(audio.sendDtmf("1")).isCompletedExceptionally();
            audio.start(audioSpec(null, false, true));
            assertThatThrownBy(() -> audio.start(audioSpec(null, false, true)))
                    .isInstanceOf(IllegalStateException.class);
            VideoStream video = new VideoStream(session);
            MediaFormat vp9 = new MediaFormat(98, "VP9", 90_000);
            assertThatThrownBy(() -> video.start(new MediaStreamSpec(MediaType.VIDEO, null, null, false, vp9,
                    List.of(vp9), null, null, true, true, 0))).isInstanceOf(IllegalArgumentException.class);
        } finally {
            audio.stop().get(5, TimeUnit.SECONDS);
        }
        assertThat(audio.isRunning()).isFalse();
    }
}
