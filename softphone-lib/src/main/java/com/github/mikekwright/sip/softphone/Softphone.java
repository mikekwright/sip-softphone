package com.github.mikekwright.sip.softphone;

import com.github.mikekwright.sip.softphone.call.Call;
import com.github.mikekwright.sip.softphone.call.CallContext;
import com.github.mikekwright.sip.softphone.call.CallDirection;
import com.github.mikekwright.sip.softphone.call.CallOptions;
import com.github.mikekwright.sip.softphone.call.CallState;
import com.github.mikekwright.sip.softphone.media.audio.AudioCodecRegistry;
import com.github.mikekwright.sip.softphone.rtp.PortAllocator;
import com.github.mikekwright.sip.softphone.rtp.RtpSession;
import com.github.mikekwright.sip.softphone.rtp.RtpSessionConfig;
import com.github.mikekwright.sip.softphone.sdp.SdpParser;
import com.github.mikekwright.sip.softphone.sdp.SessionDescription;
import com.github.mikekwright.sip.softphone.sip.auth.Credentials;
import com.github.mikekwright.sip.softphone.sip.auth.CredentialsProvider;
import com.github.mikekwright.sip.softphone.sip.core.IncomingRequest;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.core.SipStackConfig;
import com.github.mikekwright.sip.softphone.sip.flow.NonInviteRequestFlow;
import com.github.mikekwright.sip.softphone.sip.flow.Registration;
import com.github.mikekwright.sip.softphone.sip.flow.RegistrationState;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipStatus;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A complete SIP softphone: the entry point of the library.
 *
 * <pre>{@code
 * try (Softphone alice = Softphone.create(SoftphoneConfig.builder().user("alice").sipPort(0).build())) {
 *     alice.start().join();
 *     Call call = alice.call("sip:bob@127.0.0.1:5062");
 *     call.setAudioSource(new ToneSource(440, 0.3));
 *     call.connected().get(10, TimeUnit.SECONDS);
 *     call.sendDtmf("1234").join();
 *     call.hangup();
 * }
 * }</pre>
 *
 * <p>A softphone owns one {@link SipStack} (transport, transactions, dialogs) and creates RTP sessions per call.
 * It registers with a registrar ({@link #register()}), places calls ({@link #call}), receives calls
 * ({@link SoftphoneListener#onIncomingCall}) and exchanges SIP MESSAGEs. Everything below this facade remains
 * accessible ({@link #stack()}) for tests that need to go off the beaten path.</p>
 *
 * <p>Thread-safe. Listener callbacks are delivered on a single "softphone-events" thread.</p>
 */
public final class Softphone implements AutoCloseable, CallContext {

    private static final Logger log = LoggerFactory.getLogger(Softphone.class);

    private final SoftphoneConfig config;
    private final SipStack stack;
    private final AudioCodecRegistry audioCodecs = AudioCodecRegistry.defaults();
    private final PortAllocator ports;
    private final ExecutorService events;
    private final List<SoftphoneListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Registration registration;
    private volatile String mediaHost;

    private Softphone(SoftphoneConfig config) {
        this.config = config;
        SipStackConfig.Builder sb = SipStackConfig.builder()
                .listen(config.transport(), config.bindHost(), config.sipPort())
                .advertisedHost(config.advertisedHost())
                .userAgent(config.userAgent())
                .outboundProxy(config.outboundProxy())
                .timers(config.timers())
                .resolver(config.resolver())
                .pipelineCustomizer(config.pipelineCustomizer())
                .tls(config.serverSsl(), config.clientSsl())
                .ioThreads(config.ioThreads())
                .stunServer(config.stunServer())
                .keepAliveInterval(config.keepAliveInterval());
        if (config.password() != null) {
            sb.credentials(CredentialsProvider.of(new Credentials(config.authUser(), config.password())));
        }
        this.stack = new SipStack(sb.build());
        this.ports = new PortAllocator(config.rtpPortMin(), config.rtpPortMax());
        this.events = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "softphone-events-" + config.user());
            t.setDaemon(true);
            return t;
        });
        stack.registerHandler(SipMethod.INVITE, this::onInvite);
        stack.registerHandler(SipMethod.MESSAGE, this::onMessage);
    }

    /** Creates a softphone; call {@link #start()} before use. */
    public static Softphone create(SoftphoneConfig config) {
        return new Softphone(config);
    }

    /** Binds the SIP transport. Completes with this softphone. */
    public CompletableFuture<Softphone> start() {
        return stack.start().thenApply(v -> {
            String host = config.mediaHost() != null ? config.mediaHost()
                    : stack.publicHost() != null ? stack.publicHost()
                    : stack.transport().advertisedAddress(config.transport()).host();
            mediaHost = host;
            log.info("Softphone {} ready at {}", config.user(), localUri());
            return this;
        });
    }

    /** Hangs up all calls, unregisters (best effort) and releases all sockets and threads. */
    public CompletableFuture<Void> stop() {
        calls.forEach(Call::hangup);
        CompletableFuture<?> unregister = registration != null && registration.state() == RegistrationState.REGISTERED
                ? registration.unregister().completeOnTimeout(null, 2, java.util.concurrent.TimeUnit.SECONDS)
                : CompletableFuture.completedFuture(null);
        return unregister.handle((r, e) -> null)
                .thenCompose(v -> stack.stop())
                .whenComplete((v, e) -> events.shutdown());
    }

    @Override
    public void close() {
        stop().join();
    }

    // ============================================================================================ identity

    @Override
    public SoftphoneConfig config() {
        return config;
    }

    /** Our address-of-record, e.g. {@code "Alice" <sip:alice@example.com>}. */
    public NameAddress aor() {
        return new NameAddress(config.displayName(), SipUri.sip(config.user(), config.domain()));
    }

    /** The URI other phones can call us on directly (our contact address). */
    public SipUri localUri() {
        return stack.messages().contactUri(config.user(), config.transport());
    }

    @Override
    public SipStack stack() {
        return stack;
    }

    public void addListener(SoftphoneListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SoftphoneListener listener) {
        listeners.remove(listener);
    }

    /** Shortcut for {@code stack().addInterceptor(...)}: observe every SIP message on the wire. */
    public void addInterceptor(SipMessageInterceptor interceptor) {
        stack.addInterceptor(interceptor);
    }

    /** Active (not yet ended) calls. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    // ============================================================================================ registration

    /** Registers with the configured registrar (refreshing automatically). */
    public CompletableFuture<SipResponse> register() {
        if (config.registrar() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no registrar configured"));
        }
        Registration r = registration;
        if (r == null) {
            r = new Registration(stack, config.registrar(), aor(), config.user(), config.transport(),
                    config.registerExpires(), (state, response) -> {
                        int status = response == null ? 0 : response.statusCode();
                        dispatchAll(l -> l.onRegistrationStateChanged(state, status));
                    });
            registration = r;
        }
        return r.register();
    }

    public CompletableFuture<SipResponse> unregister() {
        Registration r = registration;
        return r == null ? CompletableFuture.failedFuture(new IllegalStateException("not registered"))
                : r.unregister();
    }

    public RegistrationState registrationState() {
        Registration r = registration;
        return r == null ? RegistrationState.UNREGISTERED : r.state();
    }

    // ============================================================================================ calls

    /** Places an audio call. {@code target} may be a SIP URI or {@code user@host[:port]}. */
    public Call call(String target) {
        return call(target, CallOptions.audioOnly());
    }

    /** Places a call with the given options; returns immediately with the call in state CALLING. */
    public Call call(String target, CallOptions options) {
        NameAddress to = new NameAddress(resolveTarget(target));
        Call call = CallContext.newCall(this, CallDirection.OUTGOING, options, to);
        calls.add(call);
        CallContext.startOutgoing(call, aor(), to);
        return call;
    }

    /**
     * Turns user input into a SIP URI: full URIs are kept, {@code user@host[:port]} gets {@code sip:} and a bare
     * user name gets our domain.
     */
    public SipUri resolveTarget(String target) {
        String t = target.trim();
        if (t.startsWith("<") && t.endsWith(">")) {
            t = t.substring(1, t.length() - 1);
        }
        if (!t.startsWith("sip:") && !t.startsWith("sips:")) {
            t = "sip:" + (t.contains("@") ? t : t + "@" + config.domain());
        }
        return SipUri.parse(t);
    }

    private void onInvite(IncomingRequest in) {
        SipRequest invite = in.request();
        SessionDescription offer = null;
        if (invite.hasBody()) {
            if (!"application/sdp".equals(invite.contentType())) {
                in.respond(SipStatus.UNSUPPORTED_MEDIA_TYPE, r -> r.setHeader(HeaderNames.ACCEPT, "application/sdp"));
                return;
            }
            try {
                offer = SdpParser.parse(invite.bodyAsString());
            } catch (RuntimeException e) {
                in.respond(SipStatus.BAD_REQUEST, r -> r.setStatus(400, "Malformed SDP"));
                return;
            }
        }
        boolean video = !config.videoCodecs().isEmpty();
        Call call = CallContext.newCall(this, CallDirection.INCOMING, new CallOptions(true, video, null),
                invite.from());
        calls.add(call);
        CallContext.attachIncoming(call, in, offer);
        dispatchAll(l -> l.onIncomingCall(call));
    }

    // ============================================================================================ messaging

    /** Sends a text message (SIP MESSAGE, RFC 3428) outside of any call. Completes with the final response. */
    public CompletableFuture<SipResponse> sendMessage(String target, String text) {
        return request(SipMethod.MESSAGE, target, "text/plain;charset=UTF-8", text);
    }

    /** Sends OPTIONS (RFC 3261 §11), e.g. as a ping / capability query. */
    public CompletableFuture<SipResponse> options(String target) {
        return request(SipMethod.OPTIONS, target, null, null);
    }

    /** Sends any out-of-dialog request with an optional body; digest challenges are answered. */
    public CompletableFuture<SipResponse> request(SipMethod method, String target, String contentType, String body) {
        SipUri uri = resolveTarget(target);
        return stack.submit(() -> {
            SipRequest r = stack.messages().createRequest(method, uri, aor(), new NameAddress(uri), null, 1);
            if (contentType != null) {
                r.setBody(contentType, body);
            } else {
                r.setRawBody(new byte[0]);
            }
            return NonInviteRequestFlow.send(stack, r);
        }).thenCompose(f -> f);
    }

    private void onMessage(IncomingRequest in) {
        SipRequest m = in.request();
        in.respond(SipStatus.OK);
        NameAddress from = m.from();
        String type = m.contentType();
        String body = m.bodyAsString();
        dispatchAll(l -> l.onMessage(from, type, body));
    }

    // ============================================================================================ CallContext

    @Override
    public AudioCodecRegistry audioCodecs() {
        return audioCodecs;
    }

    @Override
    public CompletableFuture<RtpSession> bindRtp() {
        InetAddress bind;
        try {
            bind = InetAddress.getByName(config.bindHost());
        } catch (UnknownHostException e) {
            return CompletableFuture.failedFuture(e);
        }
        RtpSessionConfig rtpConfig = RtpSessionConfig.builder()
                .cname(config.user() + "@" + mediaHost())
                .symmetricLatching(config.symmetricRtp())
                .build();
        return RtpSession.bind(stack.ioGroup(), bind, ports, rtpConfig).thenCompose(session -> {
            session.enableKeepAlive(config.keepAliveInterval());
            if (config.stunServer() == null) {
                return CompletableFuture.completedFuture(session);
            }
            // Learn the NAT mappings of both ports so that SDP advertises reachable addresses (RFC 8489)
            CompletableFuture<java.net.InetSocketAddress> rtp =
                    com.github.mikekwright.sip.softphone.nat.StunClient.discover(session.rtpChannel(), config.stunServer());
            CompletableFuture<java.net.InetSocketAddress> rtcp =
                    com.github.mikekwright.sip.softphone.nat.StunClient.discover(session.rtcpChannel(), config.stunServer());
            return rtp.thenCombine(rtcp, (a, b) -> {
                session.setPublicAddresses(a, b);
                log.debug("STUN: RTP {} -> {}, RTCP -> {}", session.localRtpPort(), a, b);
                return session;
            }).exceptionally(err -> {
                log.warn("STUN discovery for RTP port {} failed, advertising the local port: {}",
                        session.localRtpPort(), err.getMessage());
                return session;
            });
        });
    }

    @Override
    public String mediaHost() {
        return mediaHost;
    }

    @Override
    public void dispatch(Runnable event) {
        if (!events.isShutdown()) {
            events.execute(event);
        }
    }

    private void dispatchAll(Consumer<SoftphoneListener> event) {
        dispatch(() -> {
            for (SoftphoneListener l : listeners) {
                try {
                    event.accept(l);
                } catch (RuntimeException e) {
                    log.warn("Softphone listener failed", e);
                }
            }
        });
    }

    @Override
    public void onCallStateChanged(Call call, CallState state) {
        dispatchAll(l -> l.onCallStateChanged(call, state));
    }

    @Override
    public void onCallEnded(Call call) {
        calls.remove(call);
    }

    @Override
    public String toString() {
        return "Softphone[" + config.user() + "]";
    }
}
