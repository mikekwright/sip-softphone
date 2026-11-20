package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.Parameters;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.Via;
import com.github.mikekwright.sip.softphone.sip.transport.HostPort;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;
import com.github.mikekwright.sip.softphone.sip.transport.TransportLayer;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The SIP transaction layer (RFC 3261 §17). Creates client transactions for outgoing requests, creates server
 * transactions for incoming requests and matches incoming messages to existing transactions:
 * <ul>
 *   <li>responses by top Via branch and CSeq method (§17.1.3);</li>
 *   <li>requests by top Via branch, sent-by and method, with ACK matching the INVITE (§17.2.3); requests from
 *       pre-RFC 3261 elements (no magic cookie) by Call-ID, From tag, CSeq number and top Via.</li>
 * </ul>
 * Everything runs on the single SIP core executor, which removes the need for locking.
 */
public final class TransactionLayer {

    private static final Logger log = LoggerFactory.getLogger(TransactionLayer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransportLayer transport;
    private final EventExecutor executor;
    private final TimerSettings timerSettings;
    private final Map<String, ClientTransaction> clients = new HashMap<>();
    private final Map<String, ServerTransaction> servers = new HashMap<>();
    private TransactionUser user;

    public TransactionLayer(TransportLayer transport, EventExecutor executor, TimerSettings timerSettings) {
        this.transport = transport;
        this.executor = executor;
        this.timerSettings = timerSettings == null ? TimerSettings.DEFAULT : timerSettings;
        transport.setHandler(this::onMessage);
    }

    public void setTransactionUser(TransactionUser user) {
        this.user = user;
    }

    public TransportLayer transport() {
        return transport;
    }

    public EventExecutor executor() {
        return executor;
    }

    public TimerSettings timerSettings() {
        return timerSettings;
    }

    /** Generates an RFC 3261 branch parameter: the magic cookie plus 64 random bits. */
    public static String newBranch() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return Via.MAGIC_COOKIE + HexFormat.of().formatHex(b);
    }

    /**
     * Creates and starts a client transaction. If the request has no Via, one is pushed with the advertised
     * sent-by for the destination transport, a new branch and the {@code rport} parameter (RFC 3581). Must be called
     * on the SIP core executor.
     */
    public ClientTransaction sendRequest(SipRequest request, TransportAddress destination,
                                         ClientTransactionListener listener) {
        assertCoreThread();
        if (request.isMethod(SipMethod.ACK)) {
            throw new IllegalArgumentException("ACK does not create a client transaction; send it via the transport");
        }
        if (!request.headers().contains(HeaderNames.VIA)) {
            request.headers().addFirst(HeaderNames.VIA, createVia(destination, newBranch()).toString());
        }
        String key = clientKey(request.topVia().branch(), request.method());
        ClientTransaction tx = request.method().isInvite()
                ? new InviteClientTransaction(this, key, request, destination, listener)
                : new NonInviteClientTransaction(this, key, request, destination, listener);
        clients.put(key, tx);
        tx.start();
        return tx;
    }

    /** A Via for a request sent to {@code destination}: advertised sent-by, branch and rport. */
    public Via createVia(TransportAddress destination, String branch) {
        HostPort sentBy = transport.advertisedAddress(destination.transport());
        Parameters p = new Parameters().set("branch", branch).set("rport", null);
        return new Via(destination.transport().viaToken(), sentBy.uriHost(), sentBy.port(), p);
    }

    /** Looks up the INVITE server transaction a CANCEL refers to (RFC 3261 §9.2). */
    public Optional<InviteServerTransaction> findInviteFor(SipRequest cancel) {
        String key = serverKey(cancel, SipMethod.INVITE);
        return servers.get(key) instanceof InviteServerTransaction ist ? Optional.of(ist) : Optional.empty();
    }

    public int activeClientTransactions() {
        return clients.size();
    }

    public int activeServerTransactions() {
        return servers.size();
    }

    void remove(Transaction tx) {
        if (tx instanceof ClientTransaction) {
            clients.remove(tx.key(), tx);
        } else {
            servers.remove(tx.key(), tx);
        }
    }

    private void onMessage(InboundMessage inbound) {
        SipMessage message = inbound.message();
        if (message instanceof SipResponse response) {
            Via via = response.topVia();
            ClientTransaction tx = clients.get(clientKey(via.branch(), response.cseq().method()));
            if (tx != null) {
                tx.receiveResponse(response);
            } else if (user != null) {
                user.onStrayResponse(inbound);
            }
            return;
        }
        SipRequest request = (SipRequest) message;
        if (request.isMethod(SipMethod.ACK)) {
            ServerTransaction tx = servers.get(serverKey(request, SipMethod.INVITE));
            if (tx instanceof InviteServerTransaction ist && ist.state() != TransactionState.ACCEPTED) {
                ist.receiveAck();
            } else if (user != null) {
                user.onAck(inbound);
            }
            return;
        }
        String key = serverKey(request, request.method());
        ServerTransaction existing = servers.get(key);
        if (existing != null) {
            existing.receiveRetransmission(inbound);
            return;
        }
        ServerTransaction tx = request.method().isInvite()
                ? new InviteServerTransaction(this, key, inbound)
                : new NonInviteServerTransaction(this, key, inbound);
        servers.put(key, tx);
        if (user == null) {
            log.warn("No transaction user; dropping {}", request.startLine());
            return;
        }
        user.onRequest(tx, request);
    }

    private static String clientKey(String branch, SipMethod method) {
        return branch + "|" + method.name();
    }

    /** RFC 3261 §17.2.3 server transaction key; ACK and CANCEL are mapped by the caller where needed. */
    static String serverKey(SipRequest request, SipMethod method) {
        Via via = request.topVia();
        if (via.hasRfc3261Branch()) {
            return via.branch() + "|" + via.sentBy().toLowerCase(Locale.ROOT) + "|" + method.name();
        }
        // RFC 2543 compatibility (§17.2.3, last paragraphs)
        return "2543|" + request.callId() + "|" + request.from().tag() + "|" + request.cseq().number() + "|"
                + via + "|" + method.name();
    }

    private void assertCoreThread() {
        if (!executor.inEventLoop()) {
            throw new IllegalStateException("must be called on the SIP core executor");
        }
    }
}
