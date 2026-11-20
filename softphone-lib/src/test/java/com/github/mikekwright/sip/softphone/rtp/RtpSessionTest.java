package com.github.mikekwright.sip.softphone.rtp;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RtpSessionTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static EventLoopGroup group;
    private static PortAllocator ports;
    private final List<RtpSession> sessions = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        int base = 30_000 + 2 * ThreadLocalRandom.current().nextInt(4_000);
        ports = new PortAllocator(base, base + 1_999);
    }

    @AfterAll
    static void tearDown() throws Exception {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void closeSessions() throws Exception {
        for (RtpSession session : sessions) {
            session.close().get(5, TimeUnit.SECONDS);
        }
    }

    private RtpSession bind(RtpSessionConfig config) throws Exception {
        RtpSession session = RtpSession.bind(group, LOOPBACK, ports, config).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private RtpSession bind() throws Exception {
        return bind(RtpSessionConfig.defaults());
    }

    private static final class Recorder implements RtpSessionListener {
        final BlockingQueue<RtpPacket> rtp = new LinkedBlockingQueue<>();
        final BlockingQueue<InetSocketAddress> rtpSenders = new LinkedBlockingQueue<>();
        final BlockingQueue<List<RtcpPacket>> rtcp = new LinkedBlockingQueue<>();
        final BlockingQueue<InetSocketAddress> rtcpSenders = new LinkedBlockingQueue<>();

        @Override
        public void onRtp(RtpPacket packet, InetSocketAddress sender) {
            rtpSenders.add(sender); // before the packet: tests poll the packet, then its sender
            rtp.add(packet);
        }

        @Override
        public void onRtcp(List<RtcpPacket> packets, InetSocketAddress sender) {
            rtcpSenders.add(sender);
            rtcp.add(packets);
        }

        /** Waits for an RTCP compound containing a packet of the given type. */
        <T extends RtcpPacket> T awaitRtcp(Class<T> type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                List<RtcpPacket> compound = rtcp.poll(100, TimeUnit.MILLISECONDS);
                if (compound != null) {
                    for (RtcpPacket packet : compound) {
                        if (type.isInstance(packet)) {
                            return type.cast(packet);
                        }
                    }
                }
            }
            throw new AssertionError("no " + type.getSimpleName() + " received");
        }
    }

    private static RtpPacket packet(RtpSession from, int seq) {
        return RtpPacket.builder().payloadType(0).sequenceNumber(seq).timestamp(160L * seq).ssrc(from.ssrc())
                .payload(new byte[]{1, 2, 3, (byte) seq}).build();
    }

    @Test
    void bindsEvenRtpPortWithRtcpOnNextPort() throws Exception {
        RtpSession session = bind();
        assertThat(session.localRtpPort() % 2).isZero();
        assertThat(session.localRtcpPort()).isEqualTo(session.localRtpPort() + 1);
        assertThat(session.localRtpAddress().getAddress()).isEqualTo(LOOPBACK);
        assertThat(session.ssrc()).isBetween(0L, 0xFFFFFFFFL);
        assertThat(session.eventLoop()).isNotNull();
    }

    @Test
    void skipsPortsThatAreAlreadyInUse() throws Exception {
        PortAllocator single = new PortAllocator(ports.firstPort(), ports.firstPort() + 3);
        try (DatagramSocket blocker = new DatagramSocket(new InetSocketAddress(LOOPBACK, single.firstPort()))) {
            assertThat(blocker.isBound()).isTrue();
            RtpSession session = RtpSession.bind(group, LOOPBACK, single, RtpSessionConfig.defaults())
                    .get(5, TimeUnit.SECONDS);
            sessions.add(session);
            assertThat(session.localRtpPort()).isEqualTo(single.firstPort() + 2);
        } catch (java.net.BindException e) {
            // the port happened to be used by someone else; nothing to verify
        }
    }

    @Test
    void failsWhenNoPortCanBeBound() throws Exception {
        PortAllocator single = new PortAllocator(ports.lastPort(), ports.lastPort() + 1);
        try (DatagramSocket blocker = new DatagramSocket(new InetSocketAddress(LOOPBACK, single.firstPort()))) {
            assertThat(blocker.isBound()).isTrue();
            assertThatThrownBy(() -> RtpSession.bind(group, LOOPBACK, single, RtpSessionConfig.defaults())
                    .get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void exchangesRtpAndUpdatesStatistics() throws Exception {
        RtpSession a = bind();
        RtpSession b = bind();
        Recorder recorder = new Recorder();
        b.setListener(recorder);
        a.connect(b.localRtpAddress(), null, false);
        assertThat(a.remoteRtcpAddress().getPort()).isEqualTo(b.localRtpPort() + 1);

        for (int i = 0; i < 5; i++) {
            assertThat(a.send(packet(a, i))).isTrue();
        }
        for (int i = 0; i < 5; i++) {
            RtpPacket received = recorder.rtp.poll(5, TimeUnit.SECONDS);
            assertThat(received).isEqualTo(packet(a, i));
            assertThat(recorder.rtpSenders.poll()).isEqualTo(a.localRtpAddress());
        }
        assertThat(a.senderStatistics().packetsSent()).isEqualTo(5);
        assertThat(a.senderStatistics().octetsSent()).isEqualTo(20);
        assertThat(b.receptionStatistics(a.ssrc())).hasValueSatisfying(stats -> {
            assertThat(stats.packetsReceived()).isEqualTo(5);
            assertThat(stats.packetsLost()).isZero();
        });
    }

    @Test
    void doesNotSendWithoutRemote() throws Exception {
        RtpSession a = bind();
        assertThat(a.send(packet(a, 1))).isFalse();
        a.connect(new InetSocketAddress("0.0.0.0", 4000), null, false);
        assertThat(a.send(packet(a, 1))).isFalse();
        assertThat(a.sendRtcp(List.of(Bye.of(a.ssrc())))).isFalse();
    }

    @Test
    void sendsRtcpToRtcpPort() throws Exception {
        RtpSession a = bind();
        RtpSession b = bind();
        Recorder recorder = new Recorder();
        b.setListener(recorder);
        a.connect(b.localRtpAddress(), null, false);

        a.sendFeedback(new PictureLossIndication(a.ssrc(), b.ssrc()));
        PictureLossIndication pli = recorder.awaitRtcp(PictureLossIndication.class);
        assertThat(pli.senderSsrc()).isEqualTo(a.ssrc());
        assertThat(pli.mediaSsrc()).isEqualTo(b.ssrc());
        assertThat(recorder.rtcpSenders.poll()).isEqualTo(a.localRtcpAddress());
    }

    @Test
    void multiplexesRtcpOnRtpPort() throws Exception {
        RtpSession a = bind();
        RtpSession b = bind();
        Recorder recorder = new Recorder();
        b.setListener(recorder);
        a.connect(b.localRtpAddress(), null, true);
        assertThat(a.remoteRtcpAddress()).isEqualTo(b.localRtpAddress());

        a.send(packet(a, 1));
        a.sendRtcp(List.of(new ReceiverReport(a.ssrc(), List.of()), SourceDescription.cname(a.ssrc(), "x")));
        assertThat(recorder.rtp.poll(5, TimeUnit.SECONDS)).isEqualTo(packet(a, 1));
        SourceDescription sdes = recorder.awaitRtcp(SourceDescription.class);
        assertThat(sdes.chunks().get(0).cname()).contains("x");
        assertThat(recorder.rtcpSenders.poll()).isEqualTo(a.localRtpAddress());
        assertThat(recorder.rtp).isEmpty();
    }

    @Test
    void sendsPeriodicSenderReportsWithCname() throws Exception {
        RtpSession a = bind(RtpSessionConfig.builder().cname("alice@test").rtcpIntervalMillis(100).build());
        RtpSession b = bind(RtpSessionConfig.builder().rtcpIntervalMillis(100).build());
        Recorder atB = new Recorder();
        Recorder atA = new Recorder();
        b.setListener(atB);
        a.setListener(atA);
        a.connect(b.localRtpAddress(), null, false);
        b.connect(a.localRtpAddress(), null, false);

        for (int i = 0; i < 10; i++) {
            a.send(packet(a, i));
        }
        SenderReport sr = atB.awaitRtcp(SenderReport.class);
        assertThat(sr.senderSsrc()).isEqualTo(a.ssrc());
        assertThat(sr.packetCount()).isEqualTo(10);
        assertThat(sr.octetCount()).isEqualTo(40);
        SourceDescription sdes = atB.awaitRtcp(SourceDescription.class);
        assertThat(sdes.chunks().get(0).cname()).contains("alice@test");

        // B never sent RTP, so it reports with RRs carrying a block about A.
        ReceiverReport rr = atA.awaitRtcp(ReceiverReport.class);
        assertThat(rr.senderSsrc()).isEqualTo(b.ssrc());
        while (rr.reportBlocks().isEmpty()) {
            rr = atA.awaitRtcp(ReceiverReport.class);
        }
        ReportBlock block = rr.reportBlocks().get(0);
        assertThat(block.ssrc()).isEqualTo(a.ssrc());
        assertThat(block.cumulativeLost()).isZero();
        assertThat(block.extendedHighestSequence()).isEqualTo(9);
        // B received A's SR before, so LSR echoes it eventually.
        while (rr.reportBlocks().isEmpty() || rr.reportBlocks().get(0).lastSenderReport() == 0) {
            rr = atA.awaitRtcp(ReceiverReport.class);
        }
        assertThat(rr.reportBlocks().get(0).lastSenderReport()).isNotZero();
    }

    @Test
    void sendsByeOnClose() throws Exception {
        RtpSession a = RtpSession.bind(group, LOOPBACK, ports, RtpSessionConfig.defaults()).get(5, TimeUnit.SECONDS);
        RtpSession b = bind();
        Recorder recorder = new Recorder();
        b.setListener(recorder);
        a.connect(b.localRtpAddress(), null, false);

        a.close().get(5, TimeUnit.SECONDS);
        assertThat(a.isClosed()).isTrue();
        Bye bye = recorder.awaitRtcp(Bye.class);
        assertThat(bye.ssrcs()).containsExactly(a.ssrc());
        assertThat(a.close()).isCompleted();
        assertThat(a.send(packet(a, 1))).isFalse();
    }

    @Test
    void latchesToObservedSourceAddress() throws Exception {
        RtpSession a = bind();
        RtpSession b = bind(RtpSessionConfig.builder().symmetricLatching(true).build());
        Recorder atA = new Recorder();
        Recorder atB = new Recorder();
        a.setListener(atA);
        b.setListener(atB);
        // B was told a wrong address for A (e.g. A's private address behind a NAT).
        InetSocketAddress wrong = new InetSocketAddress(LOOPBACK, a.localRtpPort() == 9 ? 11 : 9);
        b.connect(wrong, null, false);
        a.connect(b.localRtpAddress(), null, false);

        a.send(packet(a, 1));
        assertThat(atB.rtp.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(b.remoteRtpAddress()).isEqualTo(a.localRtpAddress());

        b.send(packet(b, 7));
        assertThat(atA.rtp.poll(5, TimeUnit.SECONDS)).isEqualTo(packet(b, 7));
    }

    @Test
    void doesNotLatchWhenDisabled() throws Exception {
        RtpSession a = bind();
        RtpSession b = bind();
        Recorder atB = new Recorder();
        b.setListener(atB);
        InetSocketAddress configured = new InetSocketAddress(LOOPBACK, 9);
        b.connect(configured, null, false);
        a.connect(b.localRtpAddress(), null, false);
        a.send(packet(a, 1));
        assertThat(atB.rtp.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(b.remoteRtpAddress()).isEqualTo(configured);
    }

    @Test
    void survivesGarbageDatagrams() throws Exception {
        RtpSession a = bind();
        RtpSession b = bind();
        Recorder recorder = new Recorder();
        b.setListener(recorder);
        try (DatagramSocket socket = new DatagramSocket(0, LOOPBACK)) {
            byte[] junk = {1, 2, 3};
            socket.send(new DatagramPacket(junk, junk.length, b.localRtpAddress()));
            byte[] badRtcp = {(byte) 0x80, (byte) 0xC8, 0x00, 0x40};
            socket.send(new DatagramPacket(badRtcp, badRtcp.length, b.localRtpAddress()));
            socket.send(new DatagramPacket(junk, junk.length, b.localRtcpAddress()));
        }
        a.connect(b.localRtpAddress(), null, false);
        a.send(packet(a, 3));
        assertThat(recorder.rtp.poll(5, TimeUnit.SECONDS)).isEqualTo(packet(a, 3));
        assertThat(recorder.rtcp).isEmpty();
    }
}
