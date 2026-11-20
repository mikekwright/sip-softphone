package com.github.mikekwright.sip.softphone.sip.session;

import com.github.mikekwright.sip.softphone.sip.auth.Credentials;
import com.github.mikekwright.sip.softphone.sip.auth.CredentialsProvider;
import com.github.mikekwright.sip.softphone.sip.auth.DigestAlgorithm;
import com.github.mikekwright.sip.softphone.sip.auth.DigestServer;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.core.TestStacks;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason.Cause;
import com.github.mikekwright.sip.softphone.sip.transaction.TimerSettings;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class InviteSessionTest {

    private static final String OFFER = "v=0\r\no=a 1 1 IN IP4 127.0.0.1\r\ns=-\r\n";
    private static final String ANSWER = "v=0\r\no=b 2 2 IN IP4 127.0.0.1\r\ns=-\r\n";

    private SipStack alice;
    private SipStack bob;

    @AfterEach
    void tearDown() {
        alice.close();
        bob.close();
    }

    /** Records listener callbacks as strings. */
    static final class Events implements InviteSessionListener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final CompletableFuture<TerminationReason> terminated = new CompletableFuture<>();
        volatile String answerForReinvite = ANSWER;

        @Override
        public void onProvisional(InviteSession s, SipResponse r) {
            events.add("provisional " + r.statusCode());
        }

        @Override
        public void onAnswer(InviteSession s, String sdp) {
            events.add("answer " + sdp.substring(0, 3));
        }

        @Override
        public void onEstablished(InviteSession s) {
            events.add("established");
        }

        @Override
        public String onRemoteOffer(InviteSession s, String offer) {
            events.add("reoffer");
            return answerForReinvite;
        }

        @Override
        public void onInfo(InviteSession s, SipRequest info) {
            events.add("info " + info.bodyAsString());
        }

        @Override
        public void onTerminated(InviteSession s, TerminationReason reason) {
            events.add("terminated " + reason.cause());
            terminated.complete(reason);
        }

        String await(String prefix) throws InterruptedException {
            while (true) {
                String e = events.poll(5, TimeUnit.SECONDS);
                assertThat(e).as("waiting for '%s'", prefix).isNotNull();
                if (e.startsWith(prefix)) {
                    return e;
                }
            }
        }
    }

    private void startStacks(TransportType t, TimerSettings timers) {
        alice = TestStacks.start(t, timers, CredentialsProvider.of(new Credentials("alice", "pw")));
        bob = TestStacks.start(t, timers, CredentialsProvider.NONE);
    }

    private InviteSession call(Events events) {
        SipUri bobUri = TestStacks.uri(bob, "bob");
        InviteSession s = InviteSession.outgoing(alice, new NameAddress("Alice", SipUri.sip("alice", "127.0.0.1")),
                new NameAddress(bobUri), "alice", OFFER, events);
        s.start();
        return s;
    }

    private AtomicReference<InviteSession> answerWith(Events events, Consumer<InviteSession> behaviour) {
        AtomicReference<InviteSession> ref = new AtomicReference<>();
        bob.registerHandler(SipMethod.INVITE, in -> {
            InviteSession s = InviteSession.incoming(in, "bob", events);
            ref.set(s);
            behaviour.accept(s);
        });
        return ref;
    }

    @ParameterizedTest
    @EnumSource(value = TransportType.class, names = {"UDP", "TCP"})
    void basicCallAndHangup(TransportType transport) throws Exception {
        startStacks(transport, TimerSettings.DEFAULT);
        Events bobEvents = new Events();
        AtomicReference<InviteSession> bobSession = answerWith(bobEvents, s -> {
            assertThat(s.remoteOffer()).isEqualTo(OFFER);
            s.ring();
            s.accept(ANSWER);
        });
        Events aliceEvents = new Events();
        InviteSession a = call(aliceEvents);

        aliceEvents.await("provisional 180");
        aliceEvents.await("answer");
        aliceEvents.await("established");
        bobEvents.await("established");
        assertThat(a.state()).isEqualTo(SessionState.CONFIRMED);
        assertThat(a.dialog().remoteTag()).isEqualTo(bobSession.get().dialog().localTag());

        // in-dialog INFO
        SipResponse info = a.sendRequest(SipMethod.INFO, "application/dtmf-relay", "Signal=5\r\nDuration=160\r\n")
                .get(5, TimeUnit.SECONDS);
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(bobEvents.await("info")).contains("Signal=5");

        a.bye();
        assertThat(aliceEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.LOCAL_BYE);
        assertThat(bobEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.REMOTE_BYE);
    }

    @Test
    void calleeHangsUp() throws Exception {
        startStacks(TransportType.UDP, TimerSettings.DEFAULT);
        Events bobEvents = new Events();
        AtomicReference<InviteSession> bobSession = answerWith(bobEvents, s -> s.accept(ANSWER));
        Events aliceEvents = new Events();
        call(aliceEvents);
        bobEvents.await("established");
        bobSession.get().hangup();
        assertThat(aliceEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.REMOTE_BYE);
        assertThat(bobEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.LOCAL_BYE);
    }

    @Test
    void cancelWhileRinging() throws Exception {
        startStacks(TransportType.UDP, TimerSettings.DEFAULT);
        Events bobEvents = new Events();
        answerWith(bobEvents, InviteSession::ring);
        Events aliceEvents = new Events();
        InviteSession a = call(aliceEvents);
        aliceEvents.await("provisional 180");
        a.cancel();
        TerminationReason aliceReason = aliceEvents.terminated.get(5, TimeUnit.SECONDS);
        assertThat(aliceReason.cause()).isEqualTo(Cause.LOCAL_CANCEL);
        assertThat(aliceReason.statusCode()).isEqualTo(487);
        assertThat(bobEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.REMOTE_CANCEL);
    }

    @Test
    void rejectedCall() throws Exception {
        startStacks(TransportType.UDP, TimerSettings.DEFAULT);
        Events bobEvents = new Events();
        answerWith(bobEvents, s -> s.reject(486));
        Events aliceEvents = new Events();
        call(aliceEvents);
        TerminationReason r = aliceEvents.terminated.get(5, TimeUnit.SECONDS);
        assertThat(r.cause()).isEqualTo(Cause.REMOTE_REJECT);
        assertThat(r.statusCode()).isEqualTo(486);
        assertThat(r.isFailure()).isTrue();
    }

    @Test
    void reinviteInBothDirections() throws Exception {
        startStacks(TransportType.UDP, TimerSettings.DEFAULT);
        Events bobEvents = new Events();
        AtomicReference<InviteSession> bobSession = answerWith(bobEvents, s -> s.accept(ANSWER));
        Events aliceEvents = new Events();
        InviteSession a = call(aliceEvents);
        aliceEvents.await("established");
        bobEvents.await("established");

        assertThat(a.reinvite(OFFER).get(5, TimeUnit.SECONDS)).isEqualTo(ANSWER);
        bobEvents.await("reoffer");

        aliceEvents.answerForReinvite = OFFER;
        assertThat(bobSession.get().reinvite(ANSWER).get(5, TimeUnit.SECONDS)).isEqualTo(OFFER);
        aliceEvents.await("reoffer");

        // rejecting an offer yields 488 and keeps the call up
        bobEvents.answerForReinvite = null;
        assertThat(a.reinvite(OFFER)).failsWithin(5, TimeUnit.SECONDS);
        assertThat(a.state()).isEqualTo(SessionState.CONFIRMED);
        a.bye();
        bobEvents.terminated.get(5, TimeUnit.SECONDS);
    }

    @Test
    void lostAckIsRecoveredByRetransmitted2xx() throws Exception {
        startStacks(TransportType.UDP, TestStacks.FAST);
        TestStacks.Recorder aliceWire = new TestStacks.Recorder();
        alice.addInterceptor(aliceWire);
        AtomicInteger acks = new AtomicInteger();
        aliceWire.dropOutgoing = m -> m.startLine().startsWith("ACK") && acks.incrementAndGet() == 1;
        Events bobEvents = new Events();
        answerWith(bobEvents, s -> s.accept(ANSWER));
        Events aliceEvents = new Events();
        call(aliceEvents);
        bobEvents.await("established");
        assertThat(aliceWire.countReceived("SIP/2.0 200")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void missingAckTerminatesCallWithBye() throws Exception {
        startStacks(TransportType.UDP, TestStacks.FAST);
        TestStacks.Recorder aliceWire = new TestStacks.Recorder();
        alice.addInterceptor(aliceWire);
        aliceWire.dropOutgoing = m -> m.startLine().startsWith("ACK");
        Events bobEvents = new Events();
        answerWith(bobEvents, s -> s.accept(ANSWER));
        Events aliceEvents = new Events();
        call(aliceEvents);
        assertThat(bobEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.TIMEOUT);
        assertThat(aliceEvents.terminated.get(5, TimeUnit.SECONDS).cause()).isEqualTo(Cause.REMOTE_BYE);
    }

    @Test
    void inviteIsAuthenticatedAfter407() throws Exception {
        startStacks(TransportType.UDP, TimerSettings.DEFAULT);
        DigestServer proxyAuth = new DigestServer("bob.example", DigestAlgorithm.SHA_256, u -> "pw");
        Events bobEvents = new Events();
        AtomicInteger challenges = new AtomicInteger();
        bob.registerHandler(SipMethod.INVITE, in -> {
            if (proxyAuth.verify(in.request(), true) != DigestServer.Result.OK) {
                challenges.incrementAndGet();
                SipResponse r = in.createResponse(407, bob.messages().newTag());
                in.respond(proxyAuth.challenge(r, false));
                return;
            }
            InviteSession.incoming(in, "bob", bobEvents).accept(ANSWER);
        });
        Events aliceEvents = new Events();
        InviteSession a = call(aliceEvents);
        aliceEvents.await("established");
        assertThat(challenges).hasValue(1);
        assertThat(a.invite().cseq().number()).isEqualTo(2);
        a.bye();
        bobEvents.terminated.get(5, TimeUnit.SECONDS);
    }
}
