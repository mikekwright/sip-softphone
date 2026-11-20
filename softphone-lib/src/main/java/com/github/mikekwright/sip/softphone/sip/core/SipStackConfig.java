package com.github.mikekwright.sip.softphone.sip.core;

import com.github.mikekwright.sip.softphone.sip.auth.CredentialsProvider;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transaction.TimerSettings;
import com.github.mikekwright.sip.softphone.sip.transport.DefaultSipResolver;
import com.github.mikekwright.sip.softphone.sip.transport.ListenPoint;
import com.github.mikekwright.sip.softphone.sip.transport.PipelineCustomizer;
import com.github.mikekwright.sip.softphone.sip.transport.SipResolver;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.handler.ssl.SslContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration of a {@link SipStack}. Build with {@link #builder()}.
 */
public final class SipStackConfig {

    private final List<ListenPoint> listenPoints;
    private final TransportType defaultTransport;
    private final String advertisedHost;
    private final String userAgent;
    private final SipUri outboundProxy;
    private final TimerSettings timers;
    private final SipResolver resolver;
    private final CredentialsProvider credentials;
    private final PipelineCustomizer pipelineCustomizer;
    private final SslContext serverSsl;
    private final SslContext clientSsl;
    private final int ioThreads;
    private final int idleConnectionSeconds;

    private SipStackConfig(Builder b) {
        this.listenPoints = List.copyOf(b.listenPoints.isEmpty()
                ? List.of(new ListenPoint(TransportType.UDP, "0.0.0.0", 5060)) : b.listenPoints);
        this.defaultTransport = b.defaultTransport != null ? b.defaultTransport : listenPoints.getFirst().transport();
        this.advertisedHost = b.advertisedHost;
        this.userAgent = b.userAgent;
        this.outboundProxy = b.outboundProxy;
        this.timers = b.timers;
        this.resolver = b.resolver != null ? b.resolver : new DefaultSipResolver();
        this.credentials = b.credentials;
        this.pipelineCustomizer = b.pipelineCustomizer;
        this.serverSsl = b.serverSsl;
        this.clientSsl = b.clientSsl;
        this.ioThreads = b.ioThreads;
        this.idleConnectionSeconds = b.idleConnectionSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ListenPoint> listenPoints() {
        return listenPoints;
    }

    /** Transport used for requests whose target URI does not select one (RFC 3263 §4.1). */
    public TransportType defaultTransport() {
        return defaultTransport;
    }

    /** Host advertised in Via and Contact, or {@code null} to use the bound address. */
    public String advertisedHost() {
        return advertisedHost;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Proxy every out-of-dialog request is sent to (RFC 3261 §8.1.2), or {@code null}. */
    public SipUri outboundProxy() {
        return outboundProxy;
    }

    public TimerSettings timers() {
        return timers;
    }

    public SipResolver resolver() {
        return resolver;
    }

    public CredentialsProvider credentials() {
        return credentials;
    }

    public PipelineCustomizer pipelineCustomizer() {
        return pipelineCustomizer;
    }

    public SslContext serverSsl() {
        return serverSsl;
    }

    public SslContext clientSsl() {
        return clientSsl;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public int idleConnectionSeconds() {
        return idleConnectionSeconds;
    }

    public static final class Builder {
        private final List<ListenPoint> listenPoints = new ArrayList<>();
        private TransportType defaultTransport;
        private String advertisedHost;
        private String userAgent = "sip-softphone/1.0";
        private SipUri outboundProxy;
        private TimerSettings timers = TimerSettings.DEFAULT;
        private SipResolver resolver;
        private CredentialsProvider credentials = CredentialsProvider.NONE;
        private PipelineCustomizer pipelineCustomizer = PipelineCustomizer.NONE;
        private SslContext serverSsl;
        private SslContext clientSsl;
        private int ioThreads = 0;
        private int idleConnectionSeconds = 300;

        /** Adds a listen point; the first one also defines the default transport unless set explicitly. */
        public Builder listen(TransportType transport, String host, int port) {
            listenPoints.add(new ListenPoint(transport, host, port));
            return this;
        }

        public Builder defaultTransport(TransportType transport) {
            this.defaultTransport = transport;
            return this;
        }

        public Builder advertisedHost(String host) {
            this.advertisedHost = host;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent);
            return this;
        }

        public Builder outboundProxy(SipUri proxy) {
            this.outboundProxy = proxy;
            return this;
        }

        public Builder timers(TimerSettings timers) {
            this.timers = Objects.requireNonNull(timers);
            return this;
        }

        public Builder resolver(SipResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        public Builder credentials(CredentialsProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials);
            return this;
        }

        public Builder pipelineCustomizer(PipelineCustomizer customizer) {
            this.pipelineCustomizer = Objects.requireNonNull(customizer);
            return this;
        }

        /** TLS contexts, required when a TLS listen point is configured. */
        public Builder tls(SslContext server, SslContext client) {
            this.serverSsl = server;
            this.clientSsl = client;
            return this;
        }

        /** Number of Netty I/O threads (0 = Netty default of 2 x cores). */
        public Builder ioThreads(int threads) {
            this.ioThreads = threads;
            return this;
        }

        public Builder idleConnectionSeconds(int seconds) {
            this.idleConnectionSeconds = seconds;
            return this;
        }

        public SipStackConfig build() {
            return new SipStackConfig(this);
        }
    }
}
