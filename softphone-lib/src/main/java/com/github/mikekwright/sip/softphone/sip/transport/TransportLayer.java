package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.Via;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The SIP transport layer (RFC 3261 §18). Owns the listening transports, applies the transport level processing
 * rules, runs {@link SipMessageInterceptor interceptors} and hands received messages to the transaction layer on the
 * SIP core executor.
 *
 * <ul>
 *   <li>§18.2.1 / RFC 3581: on received requests add {@code received} when the Via sent-by host differs from the
 *       packet source, and fill in {@code rport} when the client asked for it.</li>
 *   <li>§18.2.2 / RFC 3581: responses go back over the connection the request arrived on (stream transports), or to
 *       {@code maddr}, or to {@code received} plus {@code rport} / sent-by port.</li>
 *   <li>§8.1.1 / §18.3: messages without the mandatory headers are dropped.</li>
 * </ul>
 */
public final class TransportLayer {

    private static final Logger log = LoggerFactory.getLogger(TransportLayer.class);
    private static final Logger wire = LoggerFactory.getLogger("com.github.mikekwright.sip.softphone.wire");

    /** Receives validated inbound messages on the SIP core executor. */
    @FunctionalInterface
    public interface Handler {
        void onMessage(InboundMessage message);
    }

    /** Transport options shared by all listen points. */
    public record Options(PipelineCustomizer pipelineCustomizer, SslContext serverSsl, SslContext clientSsl,
                          int idleConnectionSeconds) {
        public static Options defaults() {
            return new Options(PipelineCustomizer.NONE, null, null, 300);
        }
    }

    private final EventLoopGroup ioGroup;
    private final EventExecutor core;
    private final Options options;
    private final Map<TransportType, SipTransport> transports = new EnumMap<>(TransportType.class);
    private final List<SipMessageInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private volatile Handler handler = m -> {
    };
    private volatile String advertisedHost;

    public TransportLayer(EventLoopGroup ioGroup, EventExecutor core, Options options) {
        this.ioGroup = ioGroup;
        this.core = core;
        this.options = options == null ? Options.defaults() : options;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    /**
     * Sets the host advertised in Via sent-by and Contact (e.g. a public or LAN address). When unset, the bound
     * address is used, or the primary local address when bound to a wildcard address.
     */
    public void setAdvertisedHost(String host) {
        this.advertisedHost = host;
    }

    /** The sent-by / contact address for a transport (RFC 3261 §18.1.1). */
    public HostPort advertisedAddress(TransportType type) {
        SipTransport t = transport(type);
        if (t == null) {
            throw new IllegalStateException("transport " + type + " is not enabled");
        }
        InetSocketAddress local = t.localAddress();
        String host = advertisedHost;
        if (host == null) {
            InetAddress addr = local.getAddress();
            if (addr.isAnyLocalAddress()) {
                try {
                    addr = InetAddress.getLocalHost();
                } catch (UnknownHostException e) {
                    addr = InetAddress.getLoopbackAddress();
                }
            }
            host = addr.getHostAddress();
        }
        return new HostPort(host, local.getPort());
    }

    /** Binds all listen points; completes when every transport is listening. */
    public CompletableFuture<Void> start(List<ListenPoint> listenPoints) {
        List<CompletableFuture<SipTransport>> futures = new ArrayList<>();
        for (ListenPoint lp : listenPoints) {
            InetSocketAddress bind = new InetSocketAddress(lp.host(), lp.port());
            SipTransport.Listener listener = this::onReceived;
            CompletableFuture<SipTransport> f = switch (lp.transport()) {
                case UDP -> UdpSipTransport.bind(ioGroup, bind, listener, options.pipelineCustomizer());
                case TCP -> StreamSipTransport.bind(TransportType.TCP, ioGroup, bind, listener,
                        options.pipelineCustomizer(), null, null, options.idleConnectionSeconds());
                case TLS -> StreamSipTransport.bind(TransportType.TLS, ioGroup, bind, listener,
                        options.pipelineCustomizer(), options.serverSsl(), options.clientSsl(),
                        options.idleConnectionSeconds());
            };
            futures.add(f.thenApply(t -> {
                synchronized (transports) {
                    transports.put(t.type(), t);
                }
                log.info("SIP {} transport listening on {}", t.type(), t.localAddress());
                return t;
            }));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    public CompletableFuture<Void> stop() {
        List<CompletableFuture<Void>> closes = new ArrayList<>();
        synchronized (transports) {
            transports.values().forEach(t -> closes.add(t.close()));
            transports.clear();
        }
        return CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new));
    }

    public void addInterceptor(SipMessageInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    public void removeInterceptor(SipMessageInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    public SipTransport transport(TransportType type) {
        synchronized (transports) {
            return transports.get(type);
        }
    }

    public boolean supports(TransportType type) {
        return transport(type) != null;
    }

    public List<TransportType> transportTypes() {
        synchronized (transports) {
            return List.copyOf(transports.keySet());
        }
    }

    /**
     * Sends a request (or ACK) to the resolved next hop. Must be called on the core executor so interceptors observe
     * a consistent order.
     */
    public CompletableFuture<Void> send(SipMessage message, TransportAddress destination) {
        SipTransport transport = transport(destination.transport());
        if (transport == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "transport " + destination.transport() + " is not enabled"));
        }
        SipMessage out = intercept(message, destination);
        if (out == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (wire.isDebugEnabled()) {
            wire.debug("SEND {} -> {}\n{}", destination.transport(), destination.address(), out);
        }
        return transport.send(out, destination.address());
    }

    /**
     * Sends a response following RFC 3261 §18.2.2 and RFC 3581 §4: over the original connection for stream
     * transports, otherwise to the address derived from the top Via.
     *
     * @param requestContext the inbound request the response answers
     */
    public CompletableFuture<Void> sendResponse(SipResponse response, InboundMessage requestContext) {
        TransportAddress dest = responseDestination(response.topVia(), requestContext);
        Channel ch = requestContext == null ? null : requestContext.channel();
        if (ch != null && requestContext.transport().isReliable() && ch.isActive()) {
            SipMessage out = intercept(response, dest);
            if (out == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (wire.isDebugEnabled()) {
                wire.debug("SEND {} -> {}\n{}", dest.transport(), ch.remoteAddress(), out);
            }
            return NettyFutures.toCompletable(ch.writeAndFlush(out));
        }
        return send(response, dest);
    }

    /** Computes where a response to a request with the given top Via must be sent (RFC 3261 §18.2.2, RFC 3581). */
    public static TransportAddress responseDestination(Via via, InboundMessage requestContext) {
        TransportType type = TransportType.fromToken(via.transport());
        if (type == null) {
            type = requestContext != null ? requestContext.transport() : TransportType.UDP;
        }
        if (requestContext != null && type.isReliable()) {
            return new TransportAddress(type, requestContext.remote());
        }
        String maddr = via.parameter("maddr");
        if (maddr != null) {
            return new TransportAddress(type, new InetSocketAddress(maddr, via.portOrDefault()));
        }
        String host = via.received() != null ? via.received() : via.host();
        int port = via.rport() > 0 ? via.rport() : via.portOrDefault();
        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
        }
        return new TransportAddress(type, new InetSocketAddress(host, port));
    }

    private SipMessage intercept(SipMessage message, TransportAddress destination) {
        SipMessage current = message;
        for (SipMessageInterceptor i : interceptors) {
            current = i.onSend(current, destination);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Invoked on Netty I/O threads; validates and hops to the core executor. */
    private void onReceived(InboundMessage inbound) {
        core.execute(() -> process(inbound));
    }

    private void process(InboundMessage inbound) {
        SipMessage message = inbound.message();
        if (wire.isDebugEnabled()) {
            wire.debug("RECV {} <- {}\n{}", inbound.transport(), inbound.remote(), message);
        }
        if (!hasMandatoryHeaders(message)) {
            log.warn("Dropping message without mandatory headers from {}", inbound.remote());
            return;
        }
        if (message instanceof SipRequest request) {
            stampReceived(request, inbound.remote());
        }
        InboundMessage current = inbound;
        for (SipMessageInterceptor i : interceptors) {
            SipMessage replaced = i.onReceive(current);
            if (replaced == null) {
                return;
            }
            if (replaced != current.message()) {
                current = new InboundMessage(replaced, current.transport(), current.remote(), current.local(),
                        current.channel());
            }
        }
        try {
            handler.onMessage(current);
        } catch (RuntimeException e) {
            log.error("Unhandled error processing {}", message.startLine(), e);
        }
    }

    private static boolean hasMandatoryHeaders(SipMessage m) {
        try {
            m.topVia();
            m.from();
            m.to();
            m.cseq();
            return m.header(HeaderNames.CALL_ID) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** RFC 3261 §18.2.1 and RFC 3581 §4: record the actual source of the request in the top Via. */
    static void stampReceived(SipRequest request, InetSocketAddress source) {
        Via via = request.topVia();
        Via updated = via;
        String sourceIp = source.getAddress().getHostAddress();
        if (!sameAddress(via.host(), source.getAddress()) || via.hasRport()) {
            updated = updated.withParameter("received", sourceIp);
        }
        if (via.hasRport()) {
            updated = updated.withParameter("rport", String.valueOf(source.getPort()));
        }
        if (updated != via) {
            request.headers().removeFirst(HeaderNames.VIA);
            request.headers().addFirst(HeaderNames.VIA, updated.toString());
        }
    }

    private static boolean sameAddress(String host, InetAddress address) {
        String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (!DefaultSipResolver.isNumeric(h)) {
            return false;
        }
        try {
            return InetAddress.getByName(h).equals(address);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
