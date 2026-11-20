package com.github.mikekwright.sip.softphone.sip.session;

import com.github.mikekwright.sip.softphone.sip.core.DialogUsage;
import com.github.mikekwright.sip.softphone.sip.core.IncomingRequest;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.dialog.Dialog;
import com.github.mikekwright.sip.softphone.sip.dialog.DialogState;
import com.github.mikekwright.sip.softphone.sip.flow.NonInviteRequestFlow;
import com.github.mikekwright.sip.softphone.sip.message.CSeq;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipStatus;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason.Cause;
import com.github.mikekwright.sip.softphone.sip.transaction.ClientTransaction;
import com.github.mikekwright.sip.softphone.sip.transaction.ClientTransactionListener;
import com.github.mikekwright.sip.softphone.sip.transaction.InviteServerTransaction;
import com.github.mikekwright.sip.softphone.sip.transaction.ServerTransaction;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * An INVITE dialog usage: the signalling side of one call, for either role (RFC 3261 §13 - §15).
 *
 * <h2>Outgoing (UAC) - RFC 3261 §13.2</h2>
 * <pre>
 *   UAC                                 UAS
 *    |---- INVITE (SDP offer) ------------>|
 *    |&lt;--- 100 Trying ---------------------|   stops INVITE retransmission (Timer A)
 *    |&lt;--- 180 Ringing (To tag) -----------|   early dialog
 *    |&lt;--- 183 Session Progress (SDP) -----|   early media (optional)
 *    |&lt;--- 200 OK (SDP answer, Contact) ---|   dialog confirmed
 *    |---- ACK --------------------------->|   own transaction-less request (§13.2.2.4)
 * </pre>
 * Handles 401/407 challenges, CANCEL before the final response (§9.1, CANCEL is deferred until a provisional
 * response has arrived), retransmitted 2xx (the stored ACK is re-sent) and forked 2xx responses from other
 * branches (acknowledged and immediately released with BYE, §13.2.2.4).
 *
 * <h2>Incoming (UAS) - RFC 3261 §13.3</h2>
 * <pre>
 *   UAC                                 UAS
 *    |---- INVITE ------------------------>|   100 Trying is sent by the transaction
 *    |&lt;--- 180 Ringing -------------------|   {@link #ring()}
 *    |&lt;--- 200 OK (SDP) ------------------|   {@link #accept(String)}; retransmitted T1, 2T1 ... T2 until ACK
 *    |---- ACK --------------------------->|   (no ACK within 64*T1: BYE, §13.3.1.4)
 * </pre>
 * A CANCEL received before the final response is answered with 200 and the INVITE with 487 (§9.2).
 *
 * <h2>Within the dialog</h2>
 * BYE (§15), re-INVITE in both directions including glare handling with 491 and randomized retry (§14),
 * INFO (RFC 6086) and MESSAGE (RFC 3428). Requests with a lower CSeq than the last one are rejected with 500
 * (§12.2.2).
 *
 * <h2>Threading</h2>
 * The state is confined to the SIP core executor; public methods may be called from any thread.
 */
public final class InviteSession implements DialogUsage {

    private static final Logger log = LoggerFactory.getLogger(InviteSession.class);
    private static final String SDP = "application/sdp";

    /** Which side of the INVITE this session represents. */
    public enum Role { UAC, UAS }

    private final SipStack stack;
    private final Role role;
    private final String contactUser;
    private final InviteSessionListener listener;
    private final CompletableFuture<TerminationReason> terminated = new CompletableFuture<>();

    private SessionState state = SessionState.INITIAL;
    private Dialog dialog;
    private TransportType contactTransport;
    private SipRequest invite;

    // UAC state
    private ClientTransaction inviteTx;
    private TransportAddress inviteDestination;
    private boolean provisionalReceived;
    private boolean cancelRequested;
    private boolean cancelSent;
    private boolean authRetried;

    // last ACK we sent for a 2xx, re-sent when the 2xx is retransmitted
    private SipRequest lastAck;
    private TransportAddress lastAckDestination;

    // UAS state
    private InviteServerTransaction serverTx;
    private String localTag;
    private PendingAck pendingAck;
    private boolean byeAfterAck;
    private final List<Runnable> afterAck = new ArrayList<>();

    // re-INVITE we sent
    private boolean reinvitePending;

    /** A 2xx we keep retransmitting until its ACK arrives (RFC 3261 §13.3.1.4). */
    private final class PendingAck {
        final long cseq;
        final SipResponse response;
        final ServerTransaction tx;
        final boolean answerInAck;
        final long deadline;
        long interval;
        ScheduledFuture<?> timer;

        PendingAck(long cseq, SipResponse response, ServerTransaction tx, boolean answerInAck) {
            this.cseq = cseq;
            this.response = response;
            this.tx = tx;
            this.answerInAck = answerInAck;
            this.interval = stack.config().timers().t1Millis();
            this.deadline = System.currentTimeMillis() + stack.config().timers().timeoutMillis();
            schedule();
        }

        void schedule() {
            timer = stack.core().schedule(this::fire, interval, TimeUnit.MILLISECONDS);
        }

        void fire() {
            if (pendingAck != this) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                pendingAck = null;
                log.warn("No ACK for 2xx of {} - releasing the dialog", invite.callId());
                if (state != SessionState.TERMINATED) {
                    sendBye();
                    terminate(Cause.TIMEOUT, SipStatus.REQUEST_TIMEOUT, "no ACK received");
                }
                return;
            }
            if (!tx.isTerminated()) {
                tx.sendResponse(response);
            } else {
                stack.transport().sendResponse(response, tx.origin());
            }
            interval = Math.min(interval * 2, stack.config().timers().t2Millis());
            schedule();
        }

        void cancel() {
            timer.cancel(false);
        }
    }

    private InviteSession(SipStack stack, Role role, String contactUser, InviteSessionListener listener) {
        this.stack = stack;
        this.role = role;
        this.contactUser = contactUser;
        this.listener = listener == null ? new InviteSessionListener() {
        } : listener;
    }

    // =================================================================================================== factories

    /**
     * Creates an outgoing session; call {@link #start()} to send the INVITE.
     *
     * @param from        our address (a tag is generated)
     * @param to          the callee's address (also the Request-URI)
     * @param contactUser user part of our Contact URI
     * @param offerSdp    the SDP offer (outgoing calls always carry an offer in the INVITE)
     */
    public static InviteSession outgoing(SipStack stack, NameAddress from, NameAddress to, String contactUser,
                                         String offerSdp, InviteSessionListener listener) {
        InviteSession s = new InviteSession(stack, Role.UAC, contactUser, listener);
        s.invite = stack.messages().createRequest(SipMethod.INVITE, to.uri(), from, to.withoutParameter("tag"),
                null, 1);
        s.invite.setBody(SDP, offerSdp);
        return s;
    }

    /**
     * Creates the UAS side for a received INVITE. Must be called on the SIP core executor (i.e. from a
     * {@link com.github.mikekwright.sip.softphone.sip.core.RequestHandler}). The early dialog is created and
     * registered immediately so that BYE and CANCEL are routed to this session.
     */
    public static InviteSession incoming(IncomingRequest request, String contactUser,
                                         InviteSessionListener listener) {
        SipStack stack = request.stack();
        InviteSession s = new InviteSession(stack, Role.UAS, contactUser, listener);
        s.invite = request.request();
        s.serverTx = (InviteServerTransaction) request.transaction();
        s.contactTransport = request.origin().transport();
        s.localTag = stack.messages().newTag();
        s.dialog = Dialog.asUas(s.invite, s.localTag, false);
        stack.registerDialog(s.dialog.id(), s);
        s.state = SessionState.INCOMING;
        s.serverTx.onCancel(s::remoteCancel);
        return s;
    }

    // =================================================================================================== accessors

    public Role role() {
        return role;
    }

    /** Current state; reading from other threads gives a recent, possibly stale, value. */
    public SessionState state() {
        return state;
    }

    /** The initial INVITE (sent or received). */
    public SipRequest invite() {
        return invite;
    }

    public String callId() {
        return invite.callId();
    }

    /** The dialog, once created (always present for UAS sessions). */
    public Dialog dialog() {
        return dialog;
    }

    /** The SDP offer contained in the received INVITE, or {@code null} (UAS only). */
    public String remoteOffer() {
        return role == Role.UAS && SDP.equals(invite.contentType()) ? invite.bodyAsString() : null;
    }

    /** Completes when the session terminates. */
    public CompletableFuture<TerminationReason> terminated() {
        return terminated;
    }

    public SipStack stack() {
        return stack;
    }

    // =================================================================================================== UAC

    /** Sends the INVITE. */
    public CompletableFuture<Void> start() {
        if (role != Role.UAC) {
            throw new IllegalStateException("start() is only valid for outgoing sessions");
        }
        return stack.resolveNextHop(invite).thenAcceptAsync(dest -> {
            if (state != SessionState.INITIAL) {
                return; // cancelled before the INVITE went out
            }
            inviteDestination = dest;
            contactTransport = dest.transport();
            invite.setHeader(HeaderNames.CONTACT, contact());
            state = SessionState.CALLING;
            inviteTx = stack.sendRequest(invite, dest, new InviteListener());
        }, stack.core()).whenComplete((v, err) -> {
            if (err != null) {
                stack.execute(() -> terminate(Cause.TRANSPORT_ERROR, SipStatus.SERVICE_UNAVAILABLE,
                        "cannot route INVITE: " + err.getMessage()));
            }
        });
    }

    private final class InviteListener implements ClientTransactionListener {
        @Override
        public void onResponse(ClientTransaction tx, SipResponse response) {
            if (tx != inviteTx) {
                return;
            }
            int code = response.statusCode();
            if (code < 200) {
                handleProvisional(response);
            } else if (code < 300) {
                handleInvite2xx(response);
            } else if ((code == SipStatus.UNAUTHORIZED || code == SipStatus.PROXY_AUTHENTICATION_REQUIRED)
                    && !authRetried && !cancelRequested) {
                retryWithCredentials(response);
            } else {
                if (dialog != null) {
                    stack.unregisterDialog(dialog.id());
                }
                if (cancelRequested) {
                    terminate(Cause.LOCAL_CANCEL, code, response.reasonPhrase());
                } else {
                    terminate(Cause.REMOTE_REJECT, code, response.reasonPhrase());
                }
            }
        }

        @Override
        public void onTimeout(ClientTransaction tx) {
            if (tx == inviteTx) {
                terminate(Cause.TIMEOUT, SipStatus.REQUEST_TIMEOUT, "no response to INVITE");
            }
        }

        @Override
        public void onTransportError(ClientTransaction tx, Throwable cause) {
            if (tx == inviteTx) {
                terminate(Cause.TRANSPORT_ERROR, SipStatus.SERVICE_UNAVAILABLE, String.valueOf(cause.getMessage()));
            }
        }
    }

    private void handleProvisional(SipResponse response) {
        provisionalReceived = true;
        String toTag = response.to().tag();
        if (toTag != null && response.statusCode() > 100 && dialog == null && state == SessionState.CALLING) {
            dialog = Dialog.asUac(invite, response);
            stack.registerDialog(dialog.id(), this);
            state = SessionState.EARLY;
        }
        if (SDP.equals(response.contentType()) && response.hasBody()) {
            listener.onAnswer(this, response.bodyAsString());
        }
        listener.onProvisional(this, response);
        if (cancelRequested && !cancelSent) {
            sendCancel();
        }
    }

    private void handleInvite2xx(SipResponse response) {
        String toTag = response.to().tag();
        if (dialog != null && dialog.state() != DialogState.EARLY
                && toTag != null && !toTag.equals(dialog.remoteTag())) {
            releaseForkedDialog(response);
            return;
        }
        if (state == SessionState.CONFIRMED || state == SessionState.TERMINATED) {
            resendAck(); // retransmitted 2xx
            return;
        }
        if (dialog == null || !dialog.remoteTag().equals(toTag)) {
            if (dialog != null) {
                stack.unregisterDialog(dialog.id());
            }
            dialog = Dialog.asUac(invite, response);
        } else {
            dialog.confirmWith(response);
        }
        dialog.confirm();
        stack.registerDialog(dialog.id(), this);
        sendAck(dialog.createAck(invite.cseq().number()));
        state = SessionState.CONFIRMED;
        if (SDP.equals(response.contentType()) && response.hasBody()) {
            listener.onAnswer(this, response.bodyAsString());
        }
        listener.onEstablished(this);
        if (cancelRequested) {
            // CANCEL raced with the 2xx: the call is up, so release it with BYE (RFC 3261 §9.1, §15)
            sendBye();
            terminate(Cause.LOCAL_CANCEL, SipStatus.OK, "cancelled after answer");
        }
    }

    /** RFC 3261 §13.2.2.4: a 2xx from a second fork must be acknowledged and then terminated with BYE. */
    private void releaseForkedDialog(SipResponse response) {
        Dialog fork = Dialog.asUac(invite, response);
        SipRequest ack = fork.createAck(invite.cseq().number());
        stack.resolveNextHop(ack).thenCompose(dest -> stack.sendAck(ack, dest));
        SipRequest bye = fork.createRequest(SipMethod.BYE);
        stack.execute(() -> NonInviteRequestFlow.send(stack, bye));
        log.info("Released forked dialog {} of call {}", fork.remoteTag(), invite.callId());
    }

    private void retryWithCredentials(SipResponse challenge) {
        SipRequest retry = invite.copy();
        retry.headers().remove(HeaderNames.VIA);
        retry.setHeader(HeaderNames.CSEQ, new CSeq(invite.cseq().number() + 1, SipMethod.INVITE));
        if (!stack.authenticator().authorize(retry, challenge)) {
            terminate(Cause.REMOTE_REJECT, challenge.statusCode(), "no credentials for challenge");
            return;
        }
        authRetried = true;
        invite = retry;
        provisionalReceived = false;
        inviteTx = stack.sendRequest(invite, inviteDestination, new InviteListener());
    }

    /**
     * Cancels the outgoing INVITE (RFC 3261 §9.1). The CANCEL is sent once a provisional response has arrived;
     * the session terminates when the INVITE receives its final (normally 487) response.
     */
    public void cancel() {
        stack.execute(() -> {
            if (role != Role.UAC || state == SessionState.CONFIRMED || state == SessionState.TERMINATED
                    || cancelRequested) {
                return;
            }
            cancelRequested = true;
            if (state == SessionState.INITIAL) {
                terminate(Cause.LOCAL_CANCEL, 0, "cancelled before sending");
                return;
            }
            if (provisionalReceived) {
                sendCancel();
            }
            // §9.1: if the INVITE never completes, give up after 64*T1
            stack.core().schedule(() -> {
                if (state != SessionState.TERMINATED && state != SessionState.CONFIRMED) {
                    terminate(Cause.LOCAL_CANCEL, SipStatus.REQUEST_TERMINATED, "no final response after CANCEL");
                }
            }, stack.config().timers().timeoutMillis(), TimeUnit.MILLISECONDS);
        });
    }

    /** Builds the CANCEL from the INVITE as required by RFC 3261 §9.1 and sends it to the same destination. */
    private void sendCancel() {
        cancelSent = true;
        SipRequest c = new SipRequest(SipMethod.CANCEL, invite.requestUri());
        c.headers().add(HeaderNames.VIA, invite.headers().get(HeaderNames.VIA));
        c.headers().copyFrom(invite.headers(), HeaderNames.ROUTE);
        c.headers().add(HeaderNames.MAX_FORWARDS, "70");
        c.headers().add(HeaderNames.FROM, invite.header(HeaderNames.FROM));
        c.headers().add(HeaderNames.TO, invite.header(HeaderNames.TO));
        c.headers().add(HeaderNames.CALL_ID, invite.callId());
        c.headers().add(HeaderNames.CSEQ, new CSeq(invite.cseq().number(), SipMethod.CANCEL).toString());
        c.setRawBody(new byte[0]);
        stack.sendRequest(c, inviteDestination, (tx, r) -> log.debug("CANCEL answered with {}", r.statusCode()));
    }

    private void sendAck(SipRequest ack) {
        stack.resolveNextHop(ack).thenAcceptAsync(dest -> {
            lastAck = ack;
            lastAckDestination = dest;
            stack.sendAck(ack, dest);
        }, stack.core());
    }

    private void resendAck() {
        if (lastAck != null) {
            stack.transport().send(lastAck, lastAckDestination);
        }
    }

    @Override
    public void onStrayResponse(InboundMessage inbound) {
        SipResponse r = inbound.response();
        if (r.isSuccess() && r.cseq().method().isInvite() && lastAck != null
                && lastAck.cseq().number() == r.cseq().number()) {
            resendAck();
        }
    }

    // =================================================================================================== UAS

    /** Sends 180 Ringing, creating the early dialog on the caller's side. */
    public void ring() {
        provisional(SipStatus.RINGING, null);
    }

    /** Sends 183 Session Progress, optionally with SDP for early media. */
    public void progress(String sdp) {
        provisional(SipStatus.SESSION_PROGRESS, sdp);
    }

    private void provisional(int code, String sdp) {
        stack.execute(() -> {
            if (role != Role.UAS || (state != SessionState.INCOMING && state != SessionState.EARLY)) {
                return;
            }
            SipResponse r = stack.messages().createResponse(invite, code, localTag);
            r.setHeader(HeaderNames.CONTACT, contact());
            if (sdp != null) {
                r.setBody(SDP, sdp);
            }
            serverTx.sendResponse(r);
            state = SessionState.EARLY;
        });
    }

    /**
     * Answers the incoming INVITE with 200 OK. If the INVITE carried an offer {@code sdp} is the answer; otherwise it
     * is our offer and the answer is expected in the ACK.
     */
    public void accept(String sdp) {
        stack.execute(() -> {
            if (role != Role.UAS || (state != SessionState.INCOMING && state != SessionState.EARLY)) {
                throw new IllegalStateException("cannot accept in state " + state);
            }
            SipResponse ok = stack.messages().createResponse(invite, SipStatus.OK, localTag);
            ok.setHeader(HeaderNames.CONTACT, contact());
            ok.setBody(SDP, sdp);
            serverTx.sendResponse(ok);
            state = SessionState.CONNECTING;
            pendingAck = new PendingAck(invite.cseq().number(), ok, serverTx, remoteOffer() == null);
        });
    }

    /** Rejects the incoming INVITE with a final non-2xx response (e.g. 486 Busy Here, 603 Decline). */
    public void reject(int status) {
        stack.execute(() -> {
            if (role != Role.UAS || (state != SessionState.INCOMING && state != SessionState.EARLY)) {
                return;
            }
            serverTx.sendResponse(stack.messages().createResponse(invite, status, localTag));
            terminate(Cause.LOCAL_REJECT, status, "rejected locally");
        });
    }

    private void remoteCancel() {
        if (state == SessionState.INCOMING || state == SessionState.EARLY) {
            serverTx.sendResponse(stack.messages().createResponse(invite, SipStatus.REQUEST_TERMINATED, localTag));
            terminate(Cause.REMOTE_CANCEL, SipStatus.REQUEST_TERMINATED, "caller cancelled");
        }
    }

    @Override
    public void onAck(InboundMessage inbound) {
        SipRequest ack = inbound.request();
        PendingAck p = pendingAck;
        if (p == null || ack.cseq().number() != p.cseq) {
            return; // duplicate or unexpected ACK
        }
        p.cancel();
        pendingAck = null;
        if (p.answerInAck && SDP.equals(ack.contentType()) && ack.hasBody()) {
            listener.onAnswer(this, ack.bodyAsString());
        }
        if (!afterAck.isEmpty()) {
            List<Runnable> deferred = List.copyOf(afterAck);
            afterAck.clear();
            stack.core().execute(() -> deferred.forEach(Runnable::run));
        }
        if (state == SessionState.CONNECTING) {
            dialog.confirm();
            state = SessionState.CONFIRMED;
            listener.onEstablished(this);
            if (byeAfterAck) {
                bye();
            }
        }
    }

    // =================================================================================================== in dialog

    @Override
    public void onRequest(IncomingRequest in) {
        SipRequest request = in.request();
        if (!dialog.acceptRemoteSequence(request)) {
            in.respond(SipStatus.SERVER_INTERNAL_ERROR, r -> r.setHeader(HeaderNames.RETRY_AFTER, "1"));
            return;
        }
        SipMethod m = request.method();
        if (m.equals(SipMethod.BYE)) {
            in.respond(SipStatus.OK);
            if (role == Role.UAS && !serverTx.hasFinalResponse()) {
                serverTx.sendResponse(stack.messages().createResponse(invite, SipStatus.REQUEST_TERMINATED,
                        localTag));
            }
            terminate(Cause.REMOTE_BYE, SipStatus.OK, "remote hangup");
        } else if (m.equals(SipMethod.INVITE)) {
            handleReinvite(in);
        } else if (m.equals(SipMethod.INFO)) {
            listener.onInfo(this, request);
            in.respond(SipStatus.OK);
        } else if (m.equals(SipMethod.MESSAGE)) {
            listener.onMessage(this, request);
            in.respond(SipStatus.OK);
        } else if (m.equals(SipMethod.OPTIONS)) {
            in.respond(SipStatus.OK, r -> r.setHeader(HeaderNames.ALLOW, stack.messages().allowHeader()));
        } else {
            in.respond(SipStatus.METHOD_NOT_ALLOWED,
                    r -> r.setHeader(HeaderNames.ALLOW, stack.messages().allowHeader()));
        }
    }

    /** RFC 3261 §14.2: a re-INVITE from the remote party (e.g. hold / resume, codec change). */
    private void handleReinvite(IncomingRequest in) {
        SipRequest request = in.request();
        if (reinvitePending) {
            in.respond(SipStatus.REQUEST_PENDING); // glare (§14.2)
            return;
        }
        if (state != SessionState.CONFIRMED || pendingAck != null) {
            in.respond(SipStatus.SERVER_INTERNAL_ERROR, r -> r.setHeader(HeaderNames.RETRY_AFTER,
                    String.valueOf(ThreadLocalRandom.current().nextInt(0, 10))));
            return;
        }
        boolean hasOffer = SDP.equals(request.contentType()) && request.hasBody();
        String body = hasOffer ? listener.onRemoteOffer(this, request.bodyAsString())
                : listener.onOfferRequested(this);
        if (body == null) {
            in.respond(SipStatus.NOT_ACCEPTABLE_HERE);
            return;
        }
        request.contact().ifPresent(dialog::refreshTarget);
        SipResponse ok = in.createResponse(SipStatus.OK);
        ok.setHeader(HeaderNames.CONTACT, contact());
        ok.setBody(SDP, body);
        in.respond(ok);
        pendingAck = new PendingAck(request.cseq().number(), ok, in.transaction(), !hasOffer);
    }

    /**
     * Sends a re-INVITE with a new offer (RFC 3261 §14.1), e.g. to put the call on hold. Completes with the SDP
     * answer, or exceptionally with a {@link SessionRequestException} carrying the failure status. A 491 (glare) is
     * retried after a random 2.1 - 4 s delay, up to three times.
     */
    public CompletableFuture<String> reinvite(String offerSdp) {
        CompletableFuture<String> result = new CompletableFuture<>();
        stack.execute(() -> {
            if (state == SessionState.CONFIRMED && !reinvitePending && pendingAck != null) {
                // §14.1: no new INVITE transaction while one is still completing; wait for its ACK
                afterAck.add(() -> reinvite(offerSdp).whenComplete((a, e) -> {
                    if (e != null) {
                        result.completeExceptionally(e);
                    } else {
                        result.complete(a);
                    }
                }));
                return;
            }
            if (state != SessionState.CONFIRMED || reinvitePending) {
                result.completeExceptionally(new IllegalStateException("cannot re-INVITE in state " + state
                        + (reinvitePending ? " (re-INVITE pending)" : "")));
                return;
            }
            reinvitePending = true;
            sendReinvite(offerSdp, result, 0, false);
        });
        return result;
    }

    private void sendReinvite(String offer, CompletableFuture<String> result, int glareRetries, boolean authed) {
        SipRequest req = dialog.createRequest(SipMethod.INVITE);
        req.setHeader(HeaderNames.CONTACT, contact());
        req.setBody(SDP, offer);
        sendReinviteRequest(req, offer, result, glareRetries, authed);
    }

    private void sendReinviteRequest(SipRequest req, String offer, CompletableFuture<String> result,
                                     int glareRetries, boolean authed) {
        stack.messages().decorate(req);
        stack.sendRequest(req, new ClientTransactionListener() {
            @Override
            public void onResponse(ClientTransaction tx, SipResponse r) {
                int code = r.statusCode();
                if (code < 200) {
                    return;
                }
                if (code < 300) {
                    if (result.isDone()) {
                        resendAck();
                        return;
                    }
                    r.contact().ifPresent(dialog::refreshTarget);
                    sendAck(dialog.createAck(req.cseq().number()));
                    reinvitePending = false;
                    String answer = SDP.equals(r.contentType()) ? r.bodyAsString() : null;
                    if (answer != null) {
                        listener.onAnswer(InviteSession.this, answer);
                    }
                    result.complete(answer);
                    return;
                }
                if ((code == 401 || code == 407) && !authed) {
                    SipRequest retry = req.copy();
                    retry.headers().remove(HeaderNames.VIA);
                    retry.setHeader(HeaderNames.CSEQ, new CSeq(dialog.nextLocalSequence(), SipMethod.INVITE));
                    if (stack.authenticator().authorize(retry, r)) {
                        sendReinviteRequest(retry, offer, result, glareRetries, true);
                        return;
                    }
                }
                if (code == SipStatus.REQUEST_PENDING && glareRetries < 3) {
                    long delay = ThreadLocalRandom.current().nextLong(2100, 4000);
                    stack.core().schedule(() -> {
                        if (state == SessionState.CONFIRMED) {
                            sendReinvite(offer, result, glareRetries + 1, false);
                        } else {
                            reinvitePending = false;
                            result.completeExceptionally(new SessionRequestException(r));
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                    return;
                }
                reinvitePending = false;
                result.completeExceptionally(new SessionRequestException(r));
                if (code == SipStatus.CALL_TRANSACTION_DOES_NOT_EXIST || code == SipStatus.REQUEST_TIMEOUT) {
                    // RFC 5057 §5.1: these responses terminate the dialog
                    terminate(Cause.REMOTE_BYE, code, r.reasonPhrase());
                }
            }

            @Override
            public void onTimeout(ClientTransaction tx) {
                reinvitePending = false;
                result.completeExceptionally(new SessionRequestException(NonInviteRequestFlow.localResponse(req,
                        SipStatus.REQUEST_TIMEOUT)));
                sendBye();
                terminate(Cause.TIMEOUT, SipStatus.REQUEST_TIMEOUT, "re-INVITE timed out");
            }

            @Override
            public void onTransportError(ClientTransaction tx, Throwable cause) {
                reinvitePending = false;
                result.completeExceptionally(cause);
            }
        }).exceptionally(err -> {
            stack.execute(() -> {
                reinvitePending = false;
                result.completeExceptionally(err);
            });
            return null;
        });
    }

    /** Sends a request within the dialog (INFO, MESSAGE, OPTIONS ...) and completes with the final response. */
    public CompletableFuture<SipResponse> sendRequest(SipMethod method, String contentType, String body) {
        return stack.submit(() -> {
            if (dialog == null || state == SessionState.TERMINATED) {
                throw new IllegalStateException("no dialog in state " + state);
            }
            SipRequest req = dialog.createRequest(method);
            if (contentType != null && body != null) {
                req.setBody(contentType, body);
            } else {
                req.setRawBody(new byte[0]);
            }
            stack.messages().decorate(req);
            return NonInviteRequestFlow.send(stack, req, dialog::nextLocalSequence, r -> {
            });
        }).thenCompose(f -> f);
    }

    /** Ends a confirmed call with BYE (RFC 3261 §15.1). The session terminates immediately. */
    public void bye() {
        stack.execute(() -> {
            if (state == SessionState.CONNECTING) {
                byeAfterAck = true; // §15: the callee must not send BYE before the ACK (or its timeout)
                return;
            }
            if (state != SessionState.CONFIRMED) {
                return;
            }
            sendBye();
            terminate(Cause.LOCAL_BYE, SipStatus.OK, "local hangup");
        });
    }

    private void sendBye() {
        if (dialog == null) {
            return;
        }
        SipRequest bye = dialog.createRequest(SipMethod.BYE);
        bye.setRawBody(new byte[0]);
        stack.messages().decorate(bye);
        NonInviteRequestFlow.send(stack, bye, dialog::nextLocalSequence, r -> {
        }).thenAccept(r -> log.debug("BYE for {} answered with {}", invite.callId(), r.statusCode()));
    }

    /**
     * Ends the session whatever its state: CANCEL for an outgoing call not yet answered, 603 Decline for an
     * unanswered incoming call, BYE for an established call.
     */
    public void hangup() {
        stack.execute(() -> {
            switch (state) {
                case INITIAL, CALLING, EARLY -> {
                    if (role == Role.UAC) {
                        cancel();
                    } else {
                        reject(SipStatus.DECLINE);
                    }
                }
                case INCOMING -> reject(SipStatus.DECLINE);
                case CONNECTING, CONFIRMED -> bye();
                default -> {
                }
            }
        });
    }

    // =================================================================================================== helpers

    private String contact() {
        return new NameAddress(stack.messages().contactUri(contactUser,
                contactTransport == null ? stack.config().defaultTransport() : contactTransport)).toString();
    }

    private void terminate(Cause cause, int status, String reason) {
        if (state == SessionState.TERMINATED) {
            return;
        }
        state = SessionState.TERMINATED;
        if (pendingAck != null) {
            pendingAck.cancel();
            pendingAck = null;
        }
        if (dialog != null) {
            dialog.terminate();
            // keep the dialog registered briefly so retransmitted BYE / 2xx are still answered
            Dialog d = dialog;
            stack.core().schedule(() -> stack.unregisterDialog(d.id()),
                    stack.config().timers().timeoutMillis(), TimeUnit.MILLISECONDS);
        }
        TerminationReason tr = TerminationReason.of(cause, status, reason);
        log.debug("Session {} terminated: {}", invite.callId(), tr);
        terminated.complete(tr);
        try {
            listener.onTerminated(this, tr);
        } catch (RuntimeException e) {
            log.warn("Session listener failed", e);
        }
    }

    /** Convenience accessor for the remote party of the dialog (From for UAS, To for UAC). */
    public NameAddress remoteParty() {
        return role == Role.UAC ? invite.to() : invite.from();
    }

    public NameAddress localParty() {
        return role == Role.UAC ? invite.from() : invite.to();
    }

    /** The remote target URI, once known. */
    public SipUri remoteTarget() {
        return dialog == null ? null : dialog.remoteTarget();
    }
}
