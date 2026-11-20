package com.github.mikekwright.sip.softphone.sip.core;

import com.github.mikekwright.sip.softphone.sip.auth.DigestAuthenticator;
import com.github.mikekwright.sip.softphone.sip.dialog.DialogId;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipStatus;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transaction.ClientTransaction;
import com.github.mikekwright.sip.softphone.sip.transaction.ClientTransactionListener;
import com.github.mikekwright.sip.softphone.sip.transaction.InviteServerTransaction;
import com.github.mikekwright.sip.softphone.sip.transaction.ServerTransaction;
import com.github.mikekwright.sip.softphone.sip.transaction.TransactionLayer;
import com.github.mikekwright.sip.softphone.sip.transaction.TransactionUser;
import com.github.mikekwright.sip.softphone.sip.transport.DefaultSipResolver;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;
import com.github.mikekwright.sip.softphone.sip.transport.TransportLayer;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The SIP user agent core (RFC 3261 §8): wires the {@link TransportLayer transport} and {@link TransactionLayer
 * transaction} layers together and acts as their transaction user.
 *
 * <h2>Threading model</h2>
 * All protocol state (transactions, dialogs, sessions) is confined to a single "SIP core" executor thread, so none
 * of it needs locking. Netty I/O threads only parse and frame messages and then hop onto the core executor. Code
 * outside the stack interacts through {@link #execute(Runnable)} / {@link #submit(Callable)} and
 * {@link CompletableFuture}s.
 *
 * <h2>Extending</h2>
 * <ul>
 *   <li>{@link #registerHandler} - handle new request methods outside dialogs (e.g. SUBSCRIBE, REFER).</li>
 *   <li>{@link #registerDialog} - attach a {@link DialogUsage} that receives in-dialog requests.</li>
 *   <li>{@link #addInterceptor} - observe, modify or drop messages on the wire (test automation, tracing).</li>
 *   <li>{@link #sendRequest} - drive arbitrary request flows with full transaction semantics.</li>
 * </ul>
 */
public final class SipStack implements TransactionUser, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SipStack.class);
    /** Above this size UDP must not be used if a congestion controlled transport is available (§18.1.1). */
    private static final int UDP_MTU_LIMIT = 1300;

    private final SipStackConfig config;
    private final EventLoopGroup ioGroup;
    private final boolean ownsIoGroup;
    private final EventExecutor core;
    private final TransportLayer transport;
    private final TransactionLayer transactions;
    private final MessageFactory messages;
    private final DigestAuthenticator authenticator;
    private final Map<SipMethod, RequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<DialogId, DialogUsage> dialogs = new HashMap<>();
    private final List<String> supportedOptionTags = List.of();

    public SipStack(SipStackConfig config) {
        this(config, null);
    }

    /**
     * @param config  stack configuration
     * @param ioGroup a Netty event loop group to share (e.g. with RTP), or {@code null} to create one
     */
    public SipStack(SipStackConfig config, EventLoopGroup ioGroup) {
        this.config = config;
        this.ownsIoGroup = ioGroup == null;
        this.ioGroup = ioGroup != null ? ioGroup
                : new MultiThreadIoEventLoopGroup(config.ioThreads(), new DefaultThreadFactory("sip-io", true),
                NioIoHandler.newFactory());
        this.core = new DefaultEventExecutor(new DefaultThreadFactory("sip-core", true));
        this.transport = new TransportLayer(this.ioGroup, core, new TransportLayer.Options(
                config.pipelineCustomizer(), config.serverSsl(), config.clientSsl(), config.idleConnectionSeconds()));
        this.transactions = new TransactionLayer(transport, core, config.timers());
        this.transactions.setTransactionUser(this);
        this.messages = new MessageFactory(transport, config.userAgent());
        this.authenticator = new DigestAuthenticator(config.credentials());
        registerHandler(SipMethod.OPTIONS, this::answerOptions);
    }

    /** Binds all configured transports. */
    public CompletableFuture<Void> start() {
        transport.setAdvertisedHost(config.advertisedHost());
        return transport.start(config.listenPoints());
    }

    /** Closes transports and stops the executors (the I/O group only if owned by this stack). */
    public CompletableFuture<Void> stop() {
        return transport.stop().handle((v, e) -> {
            core.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            if (ownsIoGroup) {
                ioGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
            return null;
        });
    }

    @Override
    public void close() {
        stop().join();
    }

    public SipStackConfig config() {
        return config;
    }

    public EventLoopGroup ioGroup() {
        return ioGroup;
    }

    /** The single-threaded executor that owns all SIP protocol state. */
    public EventExecutor core() {
        return core;
    }

    public TransportLayer transport() {
        return transport;
    }

    public TransactionLayer transactions() {
        return transactions;
    }

    public MessageFactory messages() {
        return messages;
    }

    public DigestAuthenticator authenticator() {
        return authenticator;
    }

    public void addInterceptor(SipMessageInterceptor interceptor) {
        transport.addInterceptor(interceptor);
    }

    public void removeInterceptor(SipMessageInterceptor interceptor) {
        transport.removeInterceptor(interceptor);
    }

    /** Runs a task on the SIP core executor (inline if already on it). */
    public void execute(Runnable task) {
        if (core.inEventLoop()) {
            task.run();
        } else {
            core.execute(task);
        }
    }

    /** Runs a task on the SIP core executor and returns its result. */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        execute(() -> {
            try {
                cf.complete(task.call());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    /** Registers the handler for out-of-dialog requests of a method, replacing any previous one. */
    public void registerHandler(SipMethod method, RequestHandler handler) {
        handlers.put(method, handler);
    }

    public void unregisterHandler(SipMethod method) {
        handlers.remove(method);
    }

    /** Routes requests carrying this dialog id to the usage. Call on the core executor. */
    public void registerDialog(DialogId id, DialogUsage usage) {
        dialogs.put(id, usage);
    }

    public void unregisterDialog(DialogId id) {
        dialogs.remove(id);
    }

    // ------------------------------------------------------------------ client side

    /**
     * Determines the next hop for a request (RFC 3261 §8.1.2, RFC 3263): the first Route if present, else the
     * outbound proxy, else the Request-URI. Large requests over UDP are switched to TCP when available (§18.1.1).
     */
    public CompletableFuture<TransportAddress> resolveNextHop(SipRequest request) {
        SipUri target;
        List<NameAddress> routes = request.routes();
        if (!routes.isEmpty()) {
            target = routes.getFirst().uri();
        } else if (config.outboundProxy() != null) {
            target = config.outboundProxy();
        } else {
            target = request.requestUri();
        }
        TransportType preferred = config.defaultTransport();
        return config.resolver().resolve(target, preferred).thenApply(candidates -> {
            TransportAddress chosen = candidates.stream()
                    .filter(c -> transport.supports(c.transport()))
                    .findFirst()
                    .orElseGet(() -> new TransportAddress(preferred, candidates.getFirst().address()));
            if (chosen.transport() == TransportType.UDP && target.transport() == null
                    && request.encode().length + 200 > UDP_MTU_LIMIT && transport.supports(TransportType.TCP)) {
                chosen = new TransportAddress(TransportType.TCP, chosen.address());
            }
            return chosen;
        });
    }

    /**
     * Resolves the next hop and sends the request in a new client transaction. The future completes on the core
     * executor once the transaction has been created.
     */
    public CompletableFuture<ClientTransaction> sendRequest(SipRequest request, ClientTransactionListener listener) {
        return resolveNextHop(request).thenCompose(dest -> submit(() -> sendRequest(request, dest, listener)));
    }

    /** Sends a request to a known destination in a new client transaction. Must run on the core executor. */
    public ClientTransaction sendRequest(SipRequest request, TransportAddress destination,
                                         ClientTransactionListener listener) {
        messages.decorate(request);
        return transactions.sendRequest(request, destination, listener);
    }

    /**
     * Sends the ACK for a 2xx response (RFC 3261 §13.2.2.4). ACKs for 2xx are not part of the INVITE transaction:
     * they get their own branch and are sent directly through the transport.
     */
    public CompletableFuture<Void> sendAck(SipRequest ack, TransportAddress destination) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        execute(() -> {
            if (!ack.headers().contains(HeaderNames.VIA)) {
                ack.headers().addFirst(HeaderNames.VIA,
                        transactions.createVia(destination, TransactionLayer.newBranch()).toString());
            }
            transport.send(ack, destination).whenComplete((v, e) -> {
                if (e != null) {
                    cf.completeExceptionally(e);
                } else {
                    cf.complete(null);
                }
            });
        });
        return cf;
    }

    // ------------------------------------------------------------------ TransactionUser (server side)

    @Override
    public void onRequest(ServerTransaction tx, SipRequest request) {
        IncomingRequest in = new IncomingRequest(this, tx);
        try {
            // RFC 3261 §8.2.2.3: reject unknown required extensions
            List<String> unsupported = request.headers().all(HeaderNames.REQUIRE).stream()
                    .filter(t -> !supportedOptionTags.contains(t.trim().toLowerCase()))
                    .toList();
            if (!unsupported.isEmpty() && !request.isMethod(SipMethod.CANCEL)) {
                in.respond(SipStatus.BAD_EXTENSION,
                        r -> r.addHeader(HeaderNames.UNSUPPORTED, String.join(", ", unsupported)));
                return;
            }
            if (request.isMethod(SipMethod.CANCEL)) {
                handleCancel(in);
                return;
            }
            String toTag = request.to().tag();
            if (toTag != null) {
                DialogUsage usage = dialogs.get(new DialogId(request.callId(), toTag, request.from().tag()));
                if (usage == null) {
                    // §12.2.2: request within an unknown dialog
                    in.respond(SipStatus.CALL_TRANSACTION_DOES_NOT_EXIST);
                } else {
                    usage.onRequest(in);
                }
                return;
            }
            RequestHandler handler = handlers.get(request.method());
            if (handler == null) {
                // §8.2.1: 405 for methods we know but do not allow, 501 for methods we do not recognize
                int status = MessageFactory.DEFAULT_ALLOW.contains(request.method())
                        || request.method().equals(SipMethod.REGISTER)
                        ? SipStatus.METHOD_NOT_ALLOWED : SipStatus.NOT_IMPLEMENTED;
                in.respond(status, r -> r.setHeader(HeaderNames.ALLOW, messages.allowHeader()));
                return;
            }
            handler.handle(in);
        } catch (RuntimeException e) {
            log.error("Error handling {}", request.startLine(), e);
            if (!tx.hasFinalResponse()) {
                try {
                    in.respond(SipStatus.SERVER_INTERNAL_ERROR);
                } catch (RuntimeException ignored) {
                    // transaction already answered
                }
            }
        }
    }

    /** RFC 3261 §9.2: answer the CANCEL, then let the INVITE's owner terminate it with 487. */
    private void handleCancel(IncomingRequest in) {
        InviteServerTransaction invite = transactions.findInviteFor(in.request()).orElse(null);
        if (invite == null) {
            in.respond(SipStatus.CALL_TRANSACTION_DOES_NOT_EXIST);
            return;
        }
        in.respond(SipStatus.OK, r -> {
            // the To tag of the 200 to CANCEL should equal the one in the INVITE's response
            if (invite.lastResponse() != null) {
                r.setHeader(HeaderNames.TO, invite.lastResponse().header(HeaderNames.TO));
            }
        });
        if (invite.hasFinalResponse()) {
            return; // too late, the CANCEL has no effect (§9.2)
        }
        Runnable handler = invite.cancelHandler();
        if (handler != null) {
            handler.run();
        } else {
            invite.sendResponse(messages.createResponse(invite.request(), SipStatus.REQUEST_TERMINATED,
                    messages.newTag()));
        }
    }

    @Override
    public void onAck(InboundMessage ack) {
        SipRequest request = ack.request();
        String toTag = request.to().tag();
        DialogUsage usage = toTag == null ? null
                : dialogs.get(new DialogId(request.callId(), toTag, request.from().tag()));
        if (usage != null) {
            usage.onAck(ack);
        } else {
            log.debug("Dropping ACK outside of any dialog: {}", request.callId());
        }
    }

    @Override
    public void onStrayResponse(InboundMessage inbound) {
        SipResponse response = inbound.response();
        String toTag = response.to().tag();
        DialogUsage usage = toTag == null ? null
                : dialogs.get(new DialogId(response.callId(), response.from().tag(), toTag));
        if (usage != null) {
            usage.onStrayResponse(inbound);
        } else {
            log.debug("Dropping stray response {} for {}", response.statusCode(), response.callId());
        }
    }

    /** RFC 3261 §11.2: capabilities in response to OPTIONS. */
    private void answerOptions(IncomingRequest in) {
        in.respond(SipStatus.OK, r -> {
            r.setHeader(HeaderNames.ACCEPT, "application/sdp, text/plain, application/dtmf-relay");
            r.setHeader(HeaderNames.ALLOW, messages.allowHeader());
            r.setHeader(HeaderNames.SUPPORTED, "");
        });
    }

    /** Convenience to check whether a URI host is an IP literal. */
    static boolean isNumericHost(SipUri uri) {
        return uri.host() != null && DefaultSipResolver.selectTransport(uri, TransportType.UDP) != null
                && uri.hostAddress().matches("[0-9.:a-fA-F]+");
    }
}
