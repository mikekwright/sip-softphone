package com.github.mikekwright.sip.softphone;

import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transaction.TimerSettings;
import com.github.mikekwright.sip.softphone.sip.transport.PipelineCustomizer;
import com.github.mikekwright.sip.softphone.sip.transport.SipResolver;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.handler.ssl.SslContext;

import java.util.List;
import java.util.Objects;

/**
 * Configuration of a {@link Softphone}: identity, account, network and media settings. Build with
 * {@link #builder()}; all settings have defaults suitable for a local test call.
 *
 * <pre>{@code
 * SoftphoneConfig config = SoftphoneConfig.builder()
 *         .user("alice").domain("example.com").displayName("Alice")
 *         .password("secret")                 // answers 401/407 challenges
 *         .registrar("sip:example.com")       // enables register()
 *         .transport(TransportType.UDP).sipPort(0)
 *         .videoCodecs(List.of("VP8"))
 *         .build();
 * }</pre>
 */
public final class SoftphoneConfig {

    private final String user;
    private final String domain;
    private final String displayName;
    private final String authUser;
    private final String password;
    private final SipUri registrar;
    private final int registerExpires;
    private final SipUri outboundProxy;
    private final TransportType transport;
    private final String bindHost;
    private final int sipPort;
    private final String advertisedHost;
    private final String mediaHost;
    private final int rtpPortMin;
    private final int rtpPortMax;
    private final List<String> audioCodecs;
    private final List<String> videoCodecs;
    private final int telephoneEventPayloadType;
    private final String userAgent;
    private final TimerSettings timers;
    private final SipResolver resolver;
    private final PipelineCustomizer pipelineCustomizer;
    private final SslContext serverSsl;
    private final SslContext clientSsl;
    private final int ioThreads;
    private final boolean symmetricRtp;
    private final String stunServer;
    private final java.time.Duration keepAliveInterval;

    private SoftphoneConfig(Builder b) {
        this.user = b.user;
        this.domain = b.domain;
        this.displayName = b.displayName;
        this.authUser = b.authUser != null ? b.authUser : b.user;
        this.password = b.password;
        this.registrar = b.registrar;
        this.registerExpires = b.registerExpires;
        this.outboundProxy = b.outboundProxy;
        this.transport = b.transport;
        this.bindHost = b.bindHost;
        this.sipPort = b.sipPort;
        this.advertisedHost = b.advertisedHost;
        this.mediaHost = b.mediaHost;
        this.rtpPortMin = b.rtpPortMin;
        this.rtpPortMax = b.rtpPortMax;
        this.audioCodecs = List.copyOf(b.audioCodecs);
        this.videoCodecs = List.copyOf(b.videoCodecs);
        this.telephoneEventPayloadType = b.telephoneEventPayloadType;
        this.userAgent = b.userAgent;
        this.timers = b.timers;
        this.resolver = b.resolver;
        this.pipelineCustomizer = b.pipelineCustomizer;
        this.serverSsl = b.serverSsl;
        this.clientSsl = b.clientSsl;
        this.ioThreads = b.ioThreads;
        this.symmetricRtp = b.symmetricRtp;
        this.stunServer = b.stunServer;
        this.keepAliveInterval = b.keepAliveInterval;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.user = user;
        b.domain = domain;
        b.displayName = displayName;
        b.authUser = authUser;
        b.password = password;
        b.registrar = registrar;
        b.registerExpires = registerExpires;
        b.outboundProxy = outboundProxy;
        b.transport = transport;
        b.bindHost = bindHost;
        b.sipPort = sipPort;
        b.advertisedHost = advertisedHost;
        b.mediaHost = mediaHost;
        b.rtpPortMin = rtpPortMin;
        b.rtpPortMax = rtpPortMax;
        b.audioCodecs = audioCodecs;
        b.videoCodecs = videoCodecs;
        b.telephoneEventPayloadType = telephoneEventPayloadType;
        b.userAgent = userAgent;
        b.timers = timers;
        b.resolver = resolver;
        b.pipelineCustomizer = pipelineCustomizer;
        b.serverSsl = serverSsl;
        b.clientSsl = clientSsl;
        b.ioThreads = ioThreads;
        b.symmetricRtp = symmetricRtp;
        b.stunServer = stunServer;
        b.keepAliveInterval = keepAliveInterval;
        return b;
    }

    /** User part of our address-of-record and Contact. */
    public String user() {
        return user;
    }

    /** Domain of our address-of-record ({@code sip:user@domain}). */
    public String domain() {
        return domain;
    }

    public String displayName() {
        return displayName;
    }

    /** Username for digest authentication (defaults to {@link #user()}). */
    public String authUser() {
        return authUser;
    }

    public String password() {
        return password;
    }

    /** Registrar URI; {@code null} disables registration. */
    public SipUri registrar() {
        return registrar;
    }

    public int registerExpires() {
        return registerExpires;
    }

    public SipUri outboundProxy() {
        return outboundProxy;
    }

    public TransportType transport() {
        return transport;
    }

    public String bindHost() {
        return bindHost;
    }

    public int sipPort() {
        return sipPort;
    }

    /** Address advertised in Via/Contact; {@code null} = detect from the bound address. */
    public String advertisedHost() {
        return advertisedHost;
    }

    /** Address advertised in SDP for RTP; {@code null} = same as the SIP advertised host. */
    public String mediaHost() {
        return mediaHost;
    }

    public int rtpPortMin() {
        return rtpPortMin;
    }

    public int rtpPortMax() {
        return rtpPortMax;
    }

    /** Audio codecs in preference order, e.g. {@code PCMU}, {@code PCMA}. */
    public List<String> audioCodecs() {
        return audioCodecs;
    }

    /** Video codecs in preference order ({@code VP8}, {@code H264}); empty disables video. */
    public List<String> videoCodecs() {
        return videoCodecs;
    }

    /** Payload type offered for RFC 4733 telephone-event, or -1 to disable RFC 4733 DTMF. */
    public int telephoneEventPayloadType() {
        return telephoneEventPayloadType;
    }

    public String userAgent() {
        return userAgent;
    }

    public TimerSettings timers() {
        return timers;
    }

    public SipResolver resolver() {
        return resolver;
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

    /** Whether RTP sessions switch to the observed source address of incoming media (NAT friendliness). */
    public boolean symmetricRtp() {
        return symmetricRtp;
    }

    /**
     * STUN server ({@code host[:port]}) used to learn the public address of the SIP and RTP sockets when the
     * softphone runs behind a NAT, or {@code null} to advertise local addresses.
     */
    public String stunServer() {
        return stunServer;
    }

    /** Interval of SIP CRLF and RTP keep-alives that hold NAT bindings open; zero disables them. */
    public java.time.Duration keepAliveInterval() {
        return keepAliveInterval;
    }

    public static final class Builder {
        private String user = "softphone";
        private String domain = "localhost";
        private String displayName;
        private String authUser;
        private String password;
        private SipUri registrar;
        private int registerExpires = 3600;
        private SipUri outboundProxy;
        private TransportType transport = TransportType.UDP;
        private String bindHost = "0.0.0.0";
        private int sipPort = 5060;
        private String advertisedHost;
        private String mediaHost;
        private int rtpPortMin = 20000;
        private int rtpPortMax = 29998;
        private List<String> audioCodecs = List.of("PCMU", "PCMA");
        private List<String> videoCodecs = List.of("VP8", "H264");
        private int telephoneEventPayloadType = 101;
        private String userAgent = "sip-softphone/1.0";
        private TimerSettings timers = TimerSettings.DEFAULT;
        private SipResolver resolver;
        private PipelineCustomizer pipelineCustomizer = PipelineCustomizer.NONE;
        private SslContext serverSsl;
        private SslContext clientSsl;
        private int ioThreads;
        private boolean symmetricRtp = true;
        private String stunServer;
        private java.time.Duration keepAliveInterval = java.time.Duration.ofSeconds(25);

        public Builder user(String user) {
            this.user = Objects.requireNonNull(user);
            return this;
        }

        public Builder domain(String domain) {
            this.domain = Objects.requireNonNull(domain);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder authUser(String authUser) {
            this.authUser = authUser;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder registrar(SipUri registrar) {
            this.registrar = registrar;
            return this;
        }

        public Builder registrar(String registrar) {
            this.registrar = registrar == null || registrar.isBlank() ? null : SipUri.parse(normalize(registrar));
            return this;
        }

        public Builder registerExpires(int seconds) {
            this.registerExpires = seconds;
            return this;
        }

        public Builder outboundProxy(SipUri proxy) {
            this.outboundProxy = proxy;
            return this;
        }

        public Builder outboundProxy(String proxy) {
            this.outboundProxy = proxy == null || proxy.isBlank() ? null : SipUri.parse(normalize(proxy));
            return this;
        }

        public Builder transport(TransportType transport) {
            this.transport = Objects.requireNonNull(transport);
            return this;
        }

        public Builder bindHost(String host) {
            this.bindHost = Objects.requireNonNull(host);
            return this;
        }

        /** Local SIP port; 0 picks an ephemeral port. */
        public Builder sipPort(int port) {
            this.sipPort = port;
            return this;
        }

        public Builder advertisedHost(String host) {
            this.advertisedHost = host;
            return this;
        }

        public Builder mediaHost(String host) {
            this.mediaHost = host;
            return this;
        }

        public Builder rtpPorts(int min, int max) {
            if (min <= 0 || max < min + 1) {
                throw new IllegalArgumentException("invalid RTP port range " + min + "-" + max);
            }
            this.rtpPortMin = min;
            this.rtpPortMax = max;
            return this;
        }

        public Builder audioCodecs(List<String> codecs) {
            this.audioCodecs = List.copyOf(codecs);
            return this;
        }

        public Builder videoCodecs(List<String> codecs) {
            this.videoCodecs = List.copyOf(codecs);
            return this;
        }

        public Builder telephoneEventPayloadType(int pt) {
            this.telephoneEventPayloadType = pt;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent);
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

        public Builder pipelineCustomizer(PipelineCustomizer customizer) {
            this.pipelineCustomizer = Objects.requireNonNull(customizer);
            return this;
        }

        public Builder tls(SslContext server, SslContext client) {
            this.serverSsl = server;
            this.clientSsl = client;
            return this;
        }

        public Builder ioThreads(int threads) {
            this.ioThreads = threads;
            return this;
        }

        public Builder symmetricRtp(boolean enabled) {
            this.symmetricRtp = enabled;
            return this;
        }

        /** Enables NAT discovery with a STUN server, e.g. {@code stun.example.org:3478}. */
        public Builder stunServer(String server) {
            this.stunServer = server == null || server.isBlank() ? null : server.trim();
            return this;
        }

        /** Keep-alive interval for NAT bindings (default 25 s); {@code Duration.ZERO} disables keep-alives. */
        public Builder keepAliveInterval(java.time.Duration interval) {
            this.keepAliveInterval = Objects.requireNonNull(interval);
            return this;
        }

        public SoftphoneConfig build() {
            return new SoftphoneConfig(this);
        }

        private static String normalize(String uri) {
            return uri.startsWith("sip:") || uri.startsWith("sips:") ? uri : "sip:" + uri;
        }
    }
}
