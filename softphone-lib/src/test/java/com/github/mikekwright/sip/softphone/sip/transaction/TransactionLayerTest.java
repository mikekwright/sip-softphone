package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.auth.CredentialsProvider;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.core.TestStacks;
import com.github.mikekwright.sip.softphone.sip.flow.NonInviteRequestFlow;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionLayerTest {

    private SipStack client;
    private SipStack server;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private SipRequest options(SipUri target) {
        return client.messages().createRequest(SipMethod.MESSAGE, target,
                new NameAddress(SipUri.sip("alice", "127.0.0.1")), new NameAddress(target), null, 1)
                .setBody("text/plain", "hi");
    }

    @Test
    void retransmitsUntilResponseOverUdpAndAbsorbsDuplicates() throws Exception {
        client = TestStacks.start(TransportType.UDP, TestStacks.FAST, CredentialsProvider.NONE);
        server = TestStacks.start(TransportType.UDP, TestStacks.FAST, CredentialsProvider.NONE);
        TestStacks.Recorder serverWire = new TestStacks.Recorder();
        server.addInterceptor(serverWire);
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger drops = new AtomicInteger();
        // drop the first two requests so Timer E has to retransmit
        serverWire.dropIncoming = m -> m.startLine().startsWith("MESSAGE") && drops.incrementAndGet() <= 2;
        server.registerHandler(SipMethod.MESSAGE, in -> {
            handled.incrementAndGet();
            in.respond(200);
        });

        SipResponse r = NonInviteRequestFlow.send(client, options(TestStacks.uri(server, "bob")))
                .get(5, TimeUnit.SECONDS);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(handled).hasValue(1);
        assertThat(r.to().tag()).isNotNull();
    }

    @Test
    void nonInviteTimesOutWith408() throws Exception {
        client = TestStacks.start(TransportType.UDP, TestStacks.FAST, CredentialsProvider.NONE);
        TestStacks.Recorder wire = new TestStacks.Recorder();
        client.addInterceptor(wire);
        // nobody listens on this port
        SipUri nowhere = SipUri.sip("bob", "127.0.0.1", 9);
        SipResponse r = NonInviteRequestFlow.send(client, options(nowhere)).get(5, TimeUnit.SECONDS);
        assertThat(r.statusCode()).isIn(408, 503);
        if (r.statusCode() == 408) {
            // Timer E: T1, 2T1, 4T1, then capped at T2 within 64*T1
            assertThat(wire.countSent("MESSAGE")).isGreaterThan(5);
        }
    }

    @Test
    void unknownMethodGets501AndInDialogUnknownGets481() throws Exception {
        client = TestStacks.start(TransportType.TCP);
        server = TestStacks.start(TransportType.TCP);
        SipUri target = TestStacks.uri(server, "bob");
        SipRequest publish = client.messages().createRequest(SipMethod.of("FOO"), target,
                new NameAddress(SipUri.sip("alice", "127.0.0.1")), new NameAddress(target), null, 1);
        assertThat(NonInviteRequestFlow.send(client, publish).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(501);

        SipRequest bye = client.messages().createRequest(SipMethod.BYE, target,
                new NameAddress(SipUri.sip("alice", "127.0.0.1")), new NameAddress(target).withTag("nope"), null, 1);
        assertThat(NonInviteRequestFlow.send(client, bye).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(481);

        SipRequest opt = client.messages().createRequest(SipMethod.OPTIONS, target,
                new NameAddress(SipUri.sip("alice", "127.0.0.1")), new NameAddress(target), null, 1);
        SipResponse ok = NonInviteRequestFlow.send(client, opt).get(5, TimeUnit.SECONDS);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.header("Allow")).contains("INVITE");
    }

    @Test
    void requireWithUnknownExtensionIsRejected() throws Exception {
        client = TestStacks.start(TransportType.UDP);
        server = TestStacks.start(TransportType.UDP);
        SipUri target = TestStacks.uri(server, "bob");
        SipRequest opt = client.messages().createRequest(SipMethod.OPTIONS, target,
                new NameAddress(SipUri.sip("alice", "127.0.0.1")), new NameAddress(target), null, 1);
        opt.addHeader("Require", "100rel");
        SipResponse r = NonInviteRequestFlow.send(client, opt).get(5, TimeUnit.SECONDS);
        assertThat(r.statusCode()).isEqualTo(420);
        assertThat(r.header("Unsupported")).isEqualTo("100rel");
    }
}
