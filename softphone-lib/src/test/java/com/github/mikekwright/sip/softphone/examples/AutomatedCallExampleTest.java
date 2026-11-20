package com.github.mikekwright.sip.softphone.examples;

import com.github.mikekwright.sip.softphone.Softphone;
import com.github.mikekwright.sip.softphone.SoftphoneConfig;
import com.github.mikekwright.sip.softphone.SoftphoneListener;
import com.github.mikekwright.sip.softphone.call.Call;
import com.github.mikekwright.sip.softphone.call.CallListener;
import com.github.mikekwright.sip.softphone.call.CallState;
import com.github.mikekwright.sip.softphone.media.audio.RecordingSink;
import com.github.mikekwright.sip.softphone.media.audio.ToneDetector;
import com.github.mikekwright.sip.softphone.media.audio.ToneSource;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example from docs/guides/library-usage.md, kept compiling and passing as a test: an IVR-style automated
 * test where a "system under test" answers, plays a prompt tone and collects DTMF.
 */
class AutomatedCallExampleTest {

    private static SoftphoneConfig config(String user) {
        return SoftphoneConfig.builder()
                .user(user)
                .domain("127.0.0.1")
                .bindHost("127.0.0.1")
                .advertisedHost("127.0.0.1")
                .sipPort(0)                 // ephemeral port: tests can run in parallel
                .videoCodecs(List.of())     // audio only
                .build();
    }

    @Test
    void callIvrSendDigitsAndVerifyPrompt() throws Exception {
        // --- the "system under test": answers, plays a 700 Hz prompt and records digits
        try (Softphone ivr = Softphone.create(config("ivr")).start().join();
             Softphone tester = Softphone.create(config("tester")).start().join()) {

            LinkedBlockingQueue<Character> digits = new LinkedBlockingQueue<>();
            ivr.addListener(new SoftphoneListener() {
                @Override
                public void onIncomingCall(Call call) {
                    call.setAudioSource(new ToneSource(700, 0.4));
                    call.addListener(new CallListener() {
                        @Override
                        public void onDtmf(Call c, char digit, int durationMillis) {
                            digits.add(digit);
                        }
                    });
                    call.answer();
                }
            });

            // --- the test driver
            List<String> ladder = new CopyOnWriteArrayList<>();
            tester.addInterceptor(new SipMessageInterceptor() {
                @Override
                public SipMessage onReceive(InboundMessage in) {
                    ladder.add("<- " + in.message().startLine());
                    return in.message();
                }
            });

            RecordingSink heard = new RecordingSink();
            Call call = tester.call(ivr.localUri().toString());
            call.setAudioSink(heard);
            call.awaitState(CallState.CONNECTED, Duration.ofSeconds(5));

            // the prompt is audible
            assertThat(heard.awaitSamples(4000, Duration.ofSeconds(5))).isTrue();
            assertThat(ToneDetector.relativePower(heard.lastSamples(4000), heard.sampleRate(), 700))
                    .isGreaterThan(0.5);

            // menu selection via RFC 4733
            call.sendDtmf("2#").get(5, TimeUnit.SECONDS);
            assertThat(digits.poll(5, TimeUnit.SECONDS)).isEqualTo('2');
            assertThat(digits.poll(5, TimeUnit.SECONDS)).isEqualTo('#');

            call.hangup();
            call.ended().get(5, TimeUnit.SECONDS);
            assertThat(ladder).anyMatch(l -> l.contains("200 OK"));
        }
    }
}
