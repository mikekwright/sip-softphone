package com.github.mikekwright.sip.softphone.nat;

import com.github.mikekwright.sip.softphone.Softphone;
import com.github.mikekwright.sip.softphone.SoftphoneConfig;
import com.github.mikekwright.sip.softphone.SoftphoneListener;
import com.github.mikekwright.sip.softphone.call.Call;
import com.github.mikekwright.sip.softphone.call.CallState;
import com.github.mikekwright.sip.softphone.media.audio.RecordingSink;
import com.github.mikekwright.sip.softphone.media.audio.ToneDetector;
import com.github.mikekwright.sip.softphone.media.audio.ToneSource;
import com.github.mikekwright.sip.softphone.rtp.PortAllocator;
import com.github.mikekwright.sip.softphone.rtp.RtpSession;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.core.SipStackConfig;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NAT features with a STUN server that pretends we are behind a NAT: it reports 127.0.0.2 as our public address
 * (which still reaches us because the phone binds the wildcard address) and shifts RTCP ports, so the test can
 * see that Via, Contact, SDP {@code c=}/{@code m=} and {@code a=rtcp} all use the reflexive addresses.
 */
class NatTraversalTest {

    private StunServer stun;
    private Softphone alice;
    private Softphone bob;

    @AfterEach
    void tearDown() {
        if (alice != null) {
            alice.close();
        }
        if (bob != null) {
            bob.close();
        }
        if (stun != null) {
            stun.close();
        }
    }

    @Test
    void advertisesStunAddressesAndCallStillWorks() throws Exception {
        stun = StunServer.start(new InetSocketAddress("127.0.0.1", 0));
        // RTP range 61100-61198 is above the Linux ephemeral range, so only RTCP (odd) ports there are shifted
        stun.setMapping(a -> new InetSocketAddress("127.0.0.2",
                a.getPort() >= 61100 && a.getPort() <= 61199 && a.getPort() % 2 == 1 ? a.getPort() + 500
                        : a.getPort()));
        alice = Softphone.create(SoftphoneConfig.builder().user("alice").domain("127.0.0.1").bindHost("0.0.0.0")
                .sipPort(0).rtpPorts(61100, 61198).videoCodecs(List.of())
                .stunServer("127.0.0.1:" + stun.localAddress().getPort()).build()).start().join();
        bob = Softphone.create(SoftphoneConfig.builder().user("bob").domain("127.0.0.1").bindHost("127.0.0.1")
                .advertisedHost("127.0.0.1").sipPort(0).rtpPorts(61200, 61298).videoCodecs(List.of())
                .build()).start().join();

        assertThat(alice.localUri().host()).isEqualTo("127.0.0.2");
        AtomicReference<SipRequest> invite = new AtomicReference<>();
        bob.addInterceptor(new SipMessageInterceptor() {
            @Override
            public SipMessage onReceive(InboundMessage in) {
                if (in.message() instanceof SipRequest r && r.method().isInvite()) {
                    invite.compareAndSet(null, r);
                }
                return in.message();
            }
        });
        LinkedBlockingQueue<Call> incoming = new LinkedBlockingQueue<>();
        bob.addListener(new SoftphoneListener() {
            @Override
            public void onIncomingCall(Call call) {
                incoming.add(call);
            }
        });

        Call out = alice.call(bob.localUri().toString());
        out.setAudioSource(new ToneSource(900, 0.5));
        Call in = incoming.poll(5, TimeUnit.SECONDS);
        assertThat(in).isNotNull();
        RecordingSink bobHears = new RecordingSink();
        in.setAudioSink(bobHears);
        in.answer().get(5, TimeUnit.SECONDS);
        out.awaitState(CallState.CONNECTED, Duration.ofSeconds(5));

        SipRequest inv = invite.get();
        assertThat(inv.topVia().host()).isEqualTo("127.0.0.2");
        assertThat(inv.contact().orElseThrow().uri().host()).isEqualTo("127.0.0.2");
        String sdp = inv.bodyAsString();
        assertThat(sdp).contains("c=IN IP4 127.0.0.2");
        RtpSession rtp = out.audioStream().orElseThrow().session();
        assertThat(sdp).contains("m=audio " + rtp.publicRtpAddress().getPort() + " ");
        assertThat(sdp).contains("a=rtcp:" + (rtp.localRtcpPort() + 500) + " IN IP4 127.0.0.2");

        // media still flows: bob sends to 127.0.0.2, which reaches alice's wildcard bound socket
        assertThat(bobHears.awaitSamples(8000, Duration.ofSeconds(5))).isTrue();
        assertThat(ToneDetector.relativePower(bobHears.lastSamples(4000), 8000, 900)).isGreaterThan(0.5);
        in.hangup(); // bob's BYE goes to alice's public Contact
        assertThat(out.ended().get(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void sipKeepAliveSendsCrlfPings() throws Exception {
        try (DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             SipStack stack = new SipStack(SipStackConfig.builder().listen(TransportType.UDP, "127.0.0.1", 0)
                     .keepAliveInterval(Duration.ofMillis(50)).build())) {
            stack.start().join();
            Runnable stop = stack.keepAlive(new TransportAddress(TransportType.UDP,
                    (InetSocketAddress) peer.getLocalSocketAddress()));
            peer.setSoTimeout(2000);
            DatagramPacket p = new DatagramPacket(new byte[16], 16);
            peer.receive(p);
            assertThat(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII)).isEqualTo("\r\n\r\n");
            stop.run();
            Thread.sleep(100);
            assertThat(stack.keepAliveTargets()).isEmpty();
        }
    }

    @Test
    void rtpKeepAliveWhileIdle() throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try (DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            RtpSession session = RtpSession.bind(group, InetAddress.getLoopbackAddress(),
                    new PortAllocator(61300, 61398)).get(5, TimeUnit.SECONDS);
            session.connect((InetSocketAddress) peer.getLocalSocketAddress(), null, false);
            session.enableKeepAlive(Duration.ofMillis(60));
            peer.setSoTimeout(2000);
            DatagramPacket p = new DatagramPacket(new byte[64], 64);
            peer.receive(p);
            assertThat(p.getLength()).isZero(); // RFC 6263 §4.1 zero-length keep-alive
            session.close().get(5, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
