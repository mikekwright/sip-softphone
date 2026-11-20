package com.github.mikekwright.sip.softphone.call;

import com.github.mikekwright.sip.softphone.Softphone;
import com.github.mikekwright.sip.softphone.SoftphoneConfig;
import com.github.mikekwright.sip.softphone.SoftphoneListener;
import com.github.mikekwright.sip.softphone.media.audio.RecordingSink;
import com.github.mikekwright.sip.softphone.media.audio.ToneDetector;
import com.github.mikekwright.sip.softphone.media.audio.ToneSource;
import com.github.mikekwright.sip.softphone.media.video.EncodedVideoFrame;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason.Cause;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end calls between two softphones over the loopback interface. */
class SoftphoneCallTest {

    private Softphone alice;
    private Softphone bob;
    private final BlockingQueue<Call> bobIncoming = new LinkedBlockingQueue<>();

    @AfterEach
    void tearDown() {
        if (alice != null) {
            alice.close();
        }
        if (bob != null) {
            bob.close();
        }
    }

    private Softphone phone(String user, TransportType transport, List<String> audioCodecs) {
        Softphone p = Softphone.create(SoftphoneConfig.builder()
                .user(user)
                .domain("127.0.0.1")
                .bindHost("127.0.0.1")
                .advertisedHost("127.0.0.1")
                .transport(transport)
                .sipPort(0)
                .audioCodecs(audioCodecs)
                .rtpPorts(40000, 49998)
                .ioThreads(2)
                .build());
        p.start().join();
        return p;
    }

    private void start(TransportType transport) {
        start(transport, List.of("PCMU", "PCMA"), List.of("PCMA", "PCMU"));
    }

    private void start(TransportType transport, List<String> aliceCodecs, List<String> bobCodecs) {
        alice = phone("alice", transport, aliceCodecs);
        bob = phone("bob", transport, bobCodecs);
        bob.addListener(new SoftphoneListener() {
            @Override
            public void onIncomingCall(Call call) {
                bobIncoming.add(call);
            }
        });
    }

    private Call incoming() throws InterruptedException {
        Call c = bobIncoming.poll(5, TimeUnit.SECONDS);
        assertThat(c).as("incoming call").isNotNull();
        return c;
    }

    /** Waits until the sink holds enough audio and returns the relative power of the given tone. */
    private static double tonePower(RecordingSink sink, double frequency) throws Exception {
        assertThat(sink.awaitSamples(8000, Duration.ofSeconds(5))).as("audio received").isTrue();
        short[] tail = sink.lastSamples(4000);
        return ToneDetector.relativePower(tail, sink.sampleRate(), frequency);
    }

    @ParameterizedTest
    @EnumSource(value = TransportType.class, names = {"UDP", "TCP"})
    void audioFlowsBothWaysAndCallEnds(TransportType transport) throws Exception {
        start(transport);
        RecordingSink aliceHears = new RecordingSink();
        Call out = alice.call(bob.localUri().toString());
        out.setAudioSource(new ToneSource(1000, 0.5));
        out.setAudioSink(aliceHears);

        Call in = incoming();
        assertThat(in.state()).isEqualTo(CallState.INCOMING);
        assertThat(in.remoteParty().uri().user()).isEqualTo("alice");
        out.awaitState(CallState.RINGING, Duration.ofSeconds(5));
        RecordingSink bobHears = new RecordingSink();
        in.setAudioSink(bobHears);
        in.setAudioSource(new ToneSource(440, 0.5));
        in.answer().get(5, TimeUnit.SECONDS);

        out.connected().get(5, TimeUnit.SECONDS);
        in.connected().get(5, TimeUnit.SECONDS);
        // Alice prefers PCMU but bob answers with his first common codec in his preference order
        assertThat(out.audioFormat().orElseThrow().encodingName()).isEqualTo("PCMA");

        assertThat(tonePower(bobHears, 1000)).isGreaterThan(0.5);
        assertThat(tonePower(aliceHears, 440)).isGreaterThan(0.5);

        out.hangup();
        assertThat(in.ended().get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.REMOTE_BYE);
        assertThat(out.ended().get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.LOCAL_BYE);
        assertThat(alice.calls()).isEmpty();
    }

    @Test
    void dtmfIsDeliveredOnceEach() throws Exception {
        start(TransportType.UDP);
        Call out = alice.call(bob.localUri().toString());
        Call in = incoming();
        BlockingQueue<Character> digits = new LinkedBlockingQueue<>();
        in.addListener(new CallListener() {
            @Override
            public void onDtmf(Call call, char digit, int durationMillis) {
                digits.add(digit);
            }
        });
        in.answer().get(5, TimeUnit.SECONDS);
        out.connected().get(5, TimeUnit.SECONDS);

        out.sendDtmf("19*#").get(10, TimeUnit.SECONDS);
        StringBuilder got = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            Character d = digits.poll(5, TimeUnit.SECONDS);
            assertThat(d).isNotNull();
            got.append(d);
        }
        assertThat(got).hasToString("19*#");
        Thread.sleep(300);
        assertThat(digits).isEmpty();

        // SIP INFO DTMF arrives through the same callback
        assertThat(out.sendDtmfInfo('7', 200).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(digits.poll(5, TimeUnit.SECONDS)).isEqualTo('7');
    }

    @Test
    void holdAndResume() throws Exception {
        start(TransportType.UDP);
        Call out = alice.call(bob.localUri().toString());
        Call in = incoming();
        List<String> bobHold = new CopyOnWriteArrayList<>();
        in.addListener(new CallListener() {
            @Override
            public void onHoldChanged(Call call, boolean localHold, boolean remoteHold) {
                bobHold.add(localHold + "/" + remoteHold);
            }
        });
        RecordingSink bobHears = new RecordingSink();
        in.setAudioSink(bobHears);
        out.setAudioSource(new ToneSource(800, 0.5));
        in.answer().get(5, TimeUnit.SECONDS);
        out.connected().get(5, TimeUnit.SECONDS);

        out.hold().get(5, TimeUnit.SECONDS);
        assertThat(out.isOnHold()).isTrue();
        assertThat(out.audioSpec().orElseThrow().receive()).isFalse();
        assertThat(in.isRemoteHold()).isTrue();
        assertThat(in.audioSpec().orElseThrow().send()).isFalse();

        out.resume().get(5, TimeUnit.SECONDS);
        assertThat(out.isOnHold()).isFalse();
        assertThat(in.isRemoteHold()).isFalse();
        bobHears.clear();
        assertThat(tonePower(bobHears, 800)).isGreaterThan(0.5);
        Thread.sleep(100);
        assertThat(bobHold).containsExactly("false/true", "false/false");
    }

    @Test
    void videoFramesAndKeyFrameRequests() throws Exception {
        start(TransportType.UDP);
        Call out = alice.call(bob.localUri().toString(), CallOptions.audioVideo());
        Call in = incoming();
        assertThat(in.remoteOffersVideo()).isTrue();
        BlockingQueue<EncodedVideoFrame> bobFrames = new LinkedBlockingQueue<>();
        in.setVideoSink(bobFrames::add);
        CompletableFuture<Void> keyFrameRequested = new CompletableFuture<>();
        out.addListener(new CallListener() {
            @Override
            public void onKeyFrameRequested(Call call) {
                keyFrameRequested.complete(null);
            }
        });
        in.answer(CallOptions.audioVideo()).get(5, TimeUnit.SECONDS);
        out.connected().get(5, TimeUnit.SECONDS);
        assertThat(out.videoFormat().orElseThrow().encodingName()).isEqualTo("VP8");

        byte[] key = new byte[5000];
        key[0] = 0x10; // VP8 payload header: P bit 0 -> key frame
        for (int i = 1; i < key.length; i++) {
            key[i] = (byte) i;
        }
        out.sendVideoFrame(new EncodedVideoFrame(key, true, 0, "VP8"));
        EncodedVideoFrame got = bobFrames.poll(5, TimeUnit.SECONDS);
        assertThat(got).isNotNull();
        assertThat(got.keyFrame()).isTrue();
        assertThat(got.data()).isEqualTo(key);

        in.requestKeyFrame();
        keyFrameRequested.get(5, TimeUnit.SECONDS);
    }

    @Test
    void audioOnlyAnswerRejectsVideo() throws Exception {
        start(TransportType.UDP);
        Call out = alice.call(bob.localUri().toString(), CallOptions.audioVideo());
        Call in = incoming();
        in.answer(CallOptions.audioOnly()).get(5, TimeUnit.SECONDS);
        out.connected().get(5, TimeUnit.SECONDS);
        assertThat(out.hasVideo()).isFalse();
        assertThat(out.audioFormat()).isPresent();
    }

    @Test
    void rejectAndCancel() throws Exception {
        start(TransportType.UDP);
        Call rejected = alice.call(bob.localUri().toString());
        incoming().reject(603);
        assertThat(rejected.ended().get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(603);
        assertThat(rejected.connected()).isCompletedExceptionally();

        Call cancelled = alice.call(bob.localUri().toString());
        Call ringing = incoming();
        cancelled.awaitState(CallState.RINGING, Duration.ofSeconds(5));
        cancelled.hangup();
        assertThat(cancelled.ended().get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.LOCAL_CANCEL);
        assertThat(ringing.ended().get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.REMOTE_CANCEL);
    }

    @Test
    void noCommonCodecIsRejectedWith488() throws Exception {
        start(TransportType.UDP, List.of("PCMU"), List.of("PCMA"));
        Call out = alice.call(bob.localUri().toString());
        Call in = incoming();
        assertThat(in.answer()).failsWithin(5, TimeUnit.SECONDS);
        assertThat(out.ended().get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(488);
    }

    @Test
    void textMessages() throws Exception {
        start(TransportType.UDP);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        bob.addListener(new SoftphoneListener() {
            @Override
            public void onMessage(NameAddress from, String contentType, String body) {
                received.add(from.uri().user() + ": " + body);
            }
        });
        assertThat(alice.sendMessage(bob.localUri().toString(), "hello bob").get(5, TimeUnit.SECONDS).statusCode())
                .isEqualTo(200);
        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("alice: hello bob");
        assertThat(alice.options(bob.localUri().toString()).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
}
