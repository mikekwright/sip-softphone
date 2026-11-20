package com.github.mikekwright.sip.softphone.rtp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An RTP session (RFC 3550 §3, "RTP session") for one media stream: a UDP channel for RTP on an even port and
 * one for RTCP on the next (odd) port, both bound to the same Netty {@link EventLoop}.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Random SSRC (RFC 3550 §8.1) and the CNAME from {@link RtpSessionConfig} (random by default, RFC 7022).</li>
 *   <li>RTP/RTCP multiplexing on the RTP port (RFC 5761): when enabled by {@link #connect}, RTCP is sent from the
 *       RTP port to the remote RTP address. Inbound RTCP on the RTP port is always demultiplexed using
 *       {@link RtpPacket#looksLikeRtcp}, regardless of the setting.</li>
 *   <li>Reception statistics per remote SSRC (RFC 3550 Appendix A) and sender statistics, used for periodic
 *       SR/RR + SDES CNAME compound reports (RFC 3550 §6.4, §6.5) sent at a randomised interval of
 *       {@code [0.5, 1.5] * rtcpIntervalMillis} (RFC 3550 §6.2, simplified: no bandwidth-based computation).</li>
 *   <li>A BYE packet (RFC 3550 §6.6) on {@link #close()}.</li>
 *   <li>Optional symmetric latching (in the spirit of symmetric RTP, RFC 4961): the first packet received after
 *       {@link #connect} redirects the send target to the packet's source address if it differs from the
 *       signalled one, which helps with peers behind NATs.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>All public methods are thread-safe. Inbound processing, listener callbacks and RTCP timers run on
 * {@link #eventLoop()}; media streams built on top of the session are expected to run their own timers on that
 * same event loop so that per-stream state needs no locking. {@link #send} and {@link #sendRtcp} may be called
 * from any thread (Netty hands the write to the event loop); packets sent from one thread keep their order.</p>
 *
 * <h2>Resources</h2>
 * <p>Inbound datagrams are decoded into heap copies and their buffers released immediately; outbound packets
 * are encoded into buffers from the channel allocator that Netty releases after the write.
 * The channels use {@link NioDatagramChannel}, so the event loop group must support NIO, e.g.
 * {@code new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())}. The group is owned by the caller and
 * is not shut down by {@link #close()}.</p>
 */
public final class RtpSession {

    private static final Logger log = LoggerFactory.getLogger(RtpSession.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Upper bound of remote sources tracked, protecting against floods of spoofed SSRCs. */
    private static final int MAX_REMOTE_SOURCES = 31;
    private static final RtpSessionListener NO_LISTENER = new RtpSessionListener() {
    };

    private final RtpSessionConfig config;
    private final EventLoop eventLoop;
    private final Channel rtpChannel;
    private final Channel rtcpChannel;
    private final long ssrc;
    private final SenderStatistics senderStatistics = new SenderStatistics();
    private final Map<Long, ReceptionStatistics> reception = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    private volatile Map<Integer, Integer> clockRates = Map.of();
    private volatile RtpSessionListener listener = NO_LISTENER;
    private volatile InetSocketAddress remoteRtp;
    private volatile InetSocketAddress remoteRtcp;
    private volatile boolean rtcpMux;
    private volatile boolean symmetricLatching;
    private volatile boolean rtpLatched;
    private volatile boolean rtcpLatched;

    /** Only touched on the event loop. */
    private ScheduledFuture<?> rtcpTimer;

    private RtpSession(RtpSessionConfig config, EventLoop eventLoop, Channel rtpChannel, Channel rtcpChannel) {
        this.config = config;
        this.eventLoop = eventLoop;
        this.rtpChannel = rtpChannel;
        this.rtcpChannel = rtcpChannel;
        this.ssrc = RANDOM.nextInt() & Uint32.MASK;
        this.symmetricLatching = config.symmetricLatching();
    }

    // ===================================================================================== binding

    /**
     * Binds a new session: picks RTP ports from {@code ports} (RTCP = RTP + 1) and binds both channels on
     * {@code bindAddress}, trying further port pairs when a bind fails, up to
     * {@link RtpSessionConfig#maxBindAttempts()} (and at most once per pair of the range).
     *
     * @param group       the event loop group; one of its loops is chosen for the session
     * @param bindAddress local address to bind (may be a wildcard address)
     * @param ports       port allocator
     * @param config      session configuration
     * @return a future completed with the bound session, or exceptionally with an {@link IOException} when no
     *         port pair could be bound
     */
    public static CompletableFuture<RtpSession> bind(EventLoopGroup group, InetAddress bindAddress,
                                                     PortAllocator ports, RtpSessionConfig config) {
        CompletableFuture<RtpSession> result = new CompletableFuture<>();
        int attempts = Math.min(config.maxBindAttempts(), ports.pairCount());
        tryBind(group.next(), bindAddress, ports, config, 1, attempts, result);
        return result;
    }

    /** Binds with {@link RtpSessionConfig#defaults()}. */
    public static CompletableFuture<RtpSession> bind(EventLoopGroup group, InetAddress bindAddress,
                                                     PortAllocator ports) {
        return bind(group, bindAddress, ports, RtpSessionConfig.defaults());
    }

    private static void tryBind(EventLoop loop, InetAddress address, PortAllocator ports, RtpSessionConfig config,
                                int attempt, int maxAttempts, CompletableFuture<RtpSession> result) {
        int port = ports.nextRtpPort();
        InboundHandler rtpHandler = new InboundHandler(false);
        InboundHandler rtcpHandler = new InboundHandler(true);
        ChannelFuture rtpBind = newBootstrap(loop, rtpHandler).bind(address, port);
        rtpBind.addListener((ChannelFuture f1) -> {
            if (!f1.isSuccess()) {
                retryBind(loop, address, ports, config, attempt, maxAttempts, result, port, f1.cause());
                return;
            }
            newBootstrap(loop, rtcpHandler).bind(address, port + 1).addListener((ChannelFuture f2) -> {
                if (!f2.isSuccess()) {
                    f1.channel().close();
                    retryBind(loop, address, ports, config, attempt, maxAttempts, result, port, f2.cause());
                    return;
                }
                RtpSession session = new RtpSession(config, loop, f1.channel(), f2.channel());
                rtpHandler.session = session;
                rtcpHandler.session = session;
                session.scheduleNextReport();
                log.debug("RTP session {} bound to {}:{}/{}", Long.toHexString(session.ssrc), address.getHostAddress(),
                        port, port + 1);
                result.complete(session);
            });
        });
    }

    private static void retryBind(EventLoop loop, InetAddress address, PortAllocator ports, RtpSessionConfig config,
                                  int attempt, int maxAttempts, CompletableFuture<RtpSession> result, int port,
                                  Throwable cause) {
        log.debug("RTP bind of {}:{} failed: {}", address.getHostAddress(), port, cause.toString());
        if (attempt >= maxAttempts) {
            result.completeExceptionally(new IOException("could not bind an RTP/RTCP port pair on "
                    + address.getHostAddress() + " after " + attempt + " attempts", cause));
        } else {
            tryBind(loop, address, ports, config, attempt + 1, maxAttempts, result);
        }
    }

    private static Bootstrap newBootstrap(EventLoop loop, InboundHandler handler) {
        return new Bootstrap().group(loop).channel(NioDatagramChannel.class).handler(handler);
    }

    // ===================================================================================== accessors

    /** Our randomly chosen synchronization source identifier (uint32). */
    public long ssrc() {
        return ssrc;
    }

    /** The configuration this session was bound with. */
    public RtpSessionConfig config() {
        return config;
    }

    /** The CNAME announced in SDES packets. */
    public String cname() {
        return config.cname();
    }

    /** The event loop all I/O and timers of this session run on; streams schedule their media clocks here. */
    public EventLoop eventLoop() {
        return eventLoop;
    }

    public int localRtpPort() {
        return localRtpAddress().getPort();
    }

    public int localRtcpPort() {
        return localRtcpAddress().getPort();
    }

    public InetSocketAddress localRtpAddress() {
        return (InetSocketAddress) rtpChannel.localAddress();
    }

    public InetSocketAddress localRtcpAddress() {
        return (InetSocketAddress) rtcpChannel.localAddress();
    }

    /** Current RTP send target (after latching, if it happened) or {@code null}. */
    public InetSocketAddress remoteRtpAddress() {
        return remoteRtp;
    }

    /** Current RTCP send target or {@code null}. */
    public InetSocketAddress remoteRtcpAddress() {
        return remoteRtcp;
    }

    public boolean isRtcpMux() {
        return rtcpMux;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Send-side counters of our SSRC. */
    public SenderStatistics senderStatistics() {
        return senderStatistics;
    }

    /** Reception statistics of all remote sources seen so far (a snapshot of the collection). */
    public Collection<ReceptionStatistics> receptionStatistics() {
        return List.copyOf(reception.values());
    }

    /** Reception statistics of one remote source. */
    public Optional<ReceptionStatistics> receptionStatistics(long remoteSsrc) {
        return Optional.ofNullable(reception.get(remoteSsrc));
    }

    // ===================================================================================== configuration

    /**
     * Sets (or clears, with {@code null}) the remote transport addresses.
     *
     * @param remoteRtp  where to send RTP; {@code null} stops sending
     * @param remoteRtcp where to send RTCP; {@code null} means the RTP address when {@code rtcpMux}, else RTP port + 1
     * @param rtcpMux    whether RTCP is multiplexed on the RTP port (RFC 5761)
     */
    public void connect(InetSocketAddress remoteRtp, InetSocketAddress remoteRtcp, boolean rtcpMux) {
        InetSocketAddress rtcp = remoteRtcp;
        if (rtcp == null && remoteRtp != null) {
            rtcp = rtcpMux ? remoteRtp
                    : remoteRtp.isUnresolved()
                    ? InetSocketAddress.createUnresolved(remoteRtp.getHostString(), remoteRtp.getPort() + 1)
                    : new InetSocketAddress(remoteRtp.getAddress(), remoteRtp.getPort() + 1);
        }
        this.rtcpMux = rtcpMux;
        this.remoteRtcp = rtcp;
        this.remoteRtp = remoteRtp;
        this.rtpLatched = false;
        this.rtcpLatched = false;
    }

    /**
     * Enables or disables symmetric latching: when on, the first RTP (or RTCP) packet received after
     * {@link #connect} from an address different from the configured remote redirects the corresponding send
     * target to that address. Latching happens once per {@code connect}.
     */
    public void setSymmetricLatching(boolean enabled) {
        this.symmetricLatching = enabled;
    }

    public boolean isSymmetricLatching() {
        return symmetricLatching;
    }

    /** Sets the listener for inbound packets ({@code null} removes it). */
    public void setListener(RtpSessionListener listener) {
        this.listener = listener == null ? NO_LISTENER : listener;
    }

    /**
     * Declares the RTP clock rates of payload types, used for jitter computation (RFC 3550 A.8) and for
     * extrapolating sender report timestamps. Unknown payload types use
     * {@link RtpSessionConfig#defaultClockRate()}.
     */
    public void setPayloadClockRates(Map<Integer, Integer> payloadTypeToClockRate) {
        this.clockRates = Map.copyOf(payloadTypeToClockRate);
    }

    private int clockRate(int payloadType) {
        Integer rate = clockRates.get(payloadType);
        return rate != null ? rate : config.defaultClockRate();
    }

    // ===================================================================================== sending

    /**
     * Sends an RTP packet to the remote RTP address and updates the sender statistics. Callers should use
     * {@link #ssrc()} as the packet's SSRC.
     *
     * @return {@code false} if the packet was dropped because the session is closed or has no usable remote
     */
    public boolean send(RtpPacket packet) {
        InetSocketAddress target = remoteRtp;
        if (closed.get() || !isUsable(target)) {
            return false;
        }
        ByteBuf buf = packet.encode(rtpChannel.alloc());
        senderStatistics.onPacketSent(packet.payloadLength(), packet.timestamp(), System.nanoTime(),
                clockRate(packet.payloadType()));
        rtpChannel.writeAndFlush(new DatagramPacket(buf, target)).addListener(LOG_FAILURE);
        return true;
    }

    /**
     * Sends {@code packets} as one compound RTCP packet to the remote RTCP address (or from the RTP port to the
     * RTP address when multiplexed). The caller is responsible for compound packet rules (RFC 3550 §6.1);
     * see {@link #sendFeedback(RtcpPacket)} for feedback messages.
     *
     * @return {@code false} if dropped because the session is closed or has no usable remote
     */
    public boolean sendRtcp(List<? extends RtcpPacket> packets) {
        if (closed.get()) {
            return false;
        }
        return writeRtcp(packets) != null;
    }

    /**
     * Sends a feedback message (e.g. {@link PictureLossIndication}, {@link GenericNack}) in a minimal compound
     * packet of an empty receiver report, an SDES CNAME and the message, as required by RFC 4585 §3.1.
     */
    public boolean sendFeedback(RtcpPacket feedback) {
        return sendRtcp(List.of(new ReceiverReport(ssrc, List.of()), SourceDescription.cname(ssrc, cname()),
                feedback));
    }

    private ChannelFuture writeRtcp(List<? extends RtcpPacket> packets) {
        boolean mux = rtcpMux;
        InetSocketAddress target = mux ? remoteRtp : remoteRtcp;
        if (!isUsable(target)) {
            return null;
        }
        Channel channel = mux ? rtpChannel : rtcpChannel;
        ByteBuf buf = RtcpCodec.encode(packets, channel.alloc());
        ChannelFuture future = channel.writeAndFlush(new DatagramPacket(buf, target));
        future.addListener(LOG_FAILURE);
        return future;
    }

    private static boolean isUsable(InetSocketAddress address) {
        return address != null && !address.isUnresolved() && address.getPort() > 0
                && !address.getAddress().isAnyLocalAddress();
    }

    private static final ChannelFutureListener LOG_FAILURE = f -> {
        if (!f.isSuccess()) {
            log.debug("RTP/RTCP send failed: {}", f.cause().toString());
        }
    };

    // ===================================================================================== RTCP reports

    /** Builds the periodic report compound: SR (if we sent since the last report) or RR, plus SDES CNAME. */
    private List<RtcpPacket> buildReport() {
        long now = System.nanoTime();
        List<ReportBlock> blocks = new ArrayList<>();
        for (ReceptionStatistics stats : reception.values()) {
            if (stats.hasReceived() && blocks.size() < 31) {
                blocks.add(stats.toReportBlock(now));
            }
        }
        RtcpPacket report = senderStatistics.consumeSentSinceLastReport()
                ? senderStatistics.toSenderReport(ssrc, NtpTime.now(), now, blocks)
                : new ReceiverReport(ssrc, blocks);
        return List.of(report, SourceDescription.cname(ssrc, cname()));
    }

    private void scheduleNextReport() {
        if (closed.get()) {
            return;
        }
        long interval = config.rtcpIntervalMillis();
        long delay = (long) (interval * (0.5 + ThreadLocalRandom.current().nextDouble()));
        rtcpTimer = eventLoop.schedule(() -> {
            try {
                if (!closed.get()) {
                    writeRtcp(buildReport());
                }
            } catch (RuntimeException e) {
                log.warn("failed to send RTCP report", e);
            }
            scheduleNextReport();
        }, Math.max(1, delay), TimeUnit.MILLISECONDS);
    }

    // ===================================================================================== closing

    /**
     * Closes the session: stops RTCP reports, sends a final report with a BYE (RFC 3550 §6.6) if a remote is
     * known, then closes both channels. Idempotent.
     *
     * @return a future completed once both channels are closed
     */
    public CompletableFuture<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return closeFuture;
        }
        try {
            eventLoop.execute(this::doClose);
        } catch (RejectedExecutionException e) {
            // Event loop already shut down: the channels are gone with it.
            closeFuture.complete(null);
        }
        return closeFuture;
    }

    private void doClose() {
        if (rtcpTimer != null) {
            rtcpTimer.cancel(false);
        }
        ChannelFuture bye = null;
        try {
            List<RtcpPacket> compound = new ArrayList<>(buildReport());
            compound.add(Bye.of(ssrc));
            bye = writeRtcp(compound);
        } catch (RuntimeException e) {
            log.debug("failed to send RTCP BYE", e);
        }
        if (bye == null) {
            closeChannels();
        } else {
            bye.addListener(f -> closeChannels());
        }
    }

    private void closeChannels() {
        ChannelFuture rtp = rtpChannel.close();
        ChannelFuture rtcp = rtcpChannel.close();
        rtp.addListener(f1 -> rtcp.addListener(f2 -> closeFuture.complete(null)));
    }

    // ===================================================================================== inbound

    private void onDatagram(ByteBuf content, InetSocketAddress sender, boolean fromRtcpPort) {
        if (closed.get()) {
            return;
        }
        if (!fromRtcpPort && !RtpPacket.looksLikeRtcp(content)) {
            onRtpDatagram(content, sender);
        } else {
            onRtcpDatagram(content, sender, fromRtcpPort);
        }
    }

    private void onRtpDatagram(ByteBuf content, InetSocketAddress sender) {
        RtpPacket packet;
        try {
            packet = RtpPacket.decode(content);
        } catch (MalformedPacketException e) {
            log.debug("dropping malformed RTP packet from {}: {}", sender, e.getMessage());
            return;
        }
        if (packet.ssrc() == ssrc) {
            log.debug("dropping RTP packet carrying our own SSRC from {} (loop or collision)", sender);
            return;
        }
        latchRtp(sender);
        ReceptionStatistics stats = statisticsFor(packet.ssrc());
        if (stats != null) {
            stats.onPacket(packet.sequenceNumber(), packet.timestamp(), packet.payloadLength(), System.nanoTime(),
                    clockRate(packet.payloadType()));
        }
        try {
            listener.onRtp(packet, sender);
        } catch (RuntimeException e) {
            log.warn("RTP listener failed", e);
        }
    }

    private void onRtcpDatagram(ByteBuf content, InetSocketAddress sender, boolean fromRtcpPort) {
        List<RtcpPacket> packets;
        try {
            packets = RtcpCodec.decode(content);
        } catch (MalformedPacketException e) {
            log.debug("dropping malformed RTCP packet from {}: {}", sender, e.getMessage());
            return;
        }
        if (fromRtcpPort) {
            latchRtcp(sender);
        } else {
            latchRtp(sender);
        }
        long now = System.nanoTime();
        for (RtcpPacket packet : packets) {
            if (packet instanceof SenderReport sr && sr.senderSsrc() != ssrc) {
                ReceptionStatistics stats = statisticsFor(sr.senderSsrc());
                if (stats != null) {
                    stats.onSenderReport(sr.ntpTimestamp(), now);
                }
            }
        }
        try {
            listener.onRtcp(packets, sender);
        } catch (RuntimeException e) {
            log.warn("RTCP listener failed", e);
        }
    }

    private ReceptionStatistics statisticsFor(long remoteSsrc) {
        ReceptionStatistics stats = reception.get(remoteSsrc);
        if (stats == null && reception.size() < MAX_REMOTE_SOURCES) {
            stats = reception.computeIfAbsent(remoteSsrc, ReceptionStatistics::new);
        }
        return stats;
    }

    private void latchRtp(InetSocketAddress sender) {
        if (!symmetricLatching || rtpLatched) {
            return;
        }
        InetSocketAddress current = remoteRtp;
        if (current == null) {
            return;
        }
        rtpLatched = true;
        if (!sender.equals(current)) {
            log.info("symmetric latching: RTP target {} -> {}", current, sender);
            remoteRtp = sender;
            if (rtcpMux) {
                remoteRtcp = sender;
            }
        }
    }

    private void latchRtcp(InetSocketAddress sender) {
        if (!symmetricLatching || rtcpLatched || rtcpMux) {
            return;
        }
        InetSocketAddress current = remoteRtcp;
        if (current == null) {
            return;
        }
        rtcpLatched = true;
        if (!sender.equals(current)) {
            log.info("symmetric latching: RTCP target {} -> {}", current, sender);
            remoteRtcp = sender;
        }
    }

    @Override
    public String toString() {
        return "RtpSession[ssrc=" + Long.toHexString(ssrc) + ", local=" + rtpChannel.localAddress()
                + ", remote=" + remoteRtp + (rtcpMux ? ", rtcp-mux" : "") + "]";
    }

    /** Netty handler for one of the two channels; auto-releases inbound datagrams. */
    private static final class InboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final boolean rtcpPort;
        private volatile RtpSession session;

        InboundHandler(boolean rtcpPort) {
            this.rtcpPort = rtcpPort;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            RtpSession s = session;
            if (s != null) {
                s.onDatagram(packet.content(), packet.sender(), rtcpPort);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // UDP errors (e.g. ICMP port unreachable) are not fatal for the session.
            log.debug("RTP channel {} error: {}", ctx.channel().localAddress(), cause.toString());
        }
    }
}
