package com.github.mikekwright.sip.softphone.sip.dialog;

import com.github.mikekwright.sip.softphone.sip.message.CSeq;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dialog state (RFC 3261 §12): the peer-to-peer relationship established by an INVITE. Holds the identifiers,
 * sequence numbers, route set and remote target needed to build requests within the dialog (§12.2.1.1) and to
 * validate requests received within it (§12.2.2).
 *
 * <p>Not thread-safe; owned by a session that runs on the SIP core executor.</p>
 */
public final class Dialog {

    private final String callId;
    private final String localTag;
    private String remoteTag;
    private final NameAddress localAddress;
    private final NameAddress remoteAddress;
    private final boolean secure;
    private List<NameAddress> routeSet;
    private SipUri remoteTarget;
    private long localSeq;
    private long remoteSeq;
    private DialogState state;

    private Dialog(String callId, String localTag, String remoteTag, NameAddress localAddress,
                   NameAddress remoteAddress, boolean secure, List<NameAddress> routeSet, SipUri remoteTarget,
                   long localSeq, long remoteSeq, DialogState state) {
        this.callId = callId;
        this.localTag = localTag;
        this.remoteTag = remoteTag;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.secure = secure;
        this.routeSet = routeSet;
        this.remoteTarget = remoteTarget;
        this.localSeq = localSeq;
        this.remoteSeq = remoteSeq;
        this.state = state;
    }

    /**
     * Creates the UAC side of a dialog from the request and a 1xx (with To tag) or 2xx response (RFC 3261 §12.1.2):
     * the route set is the Record-Route list in reverse order, the remote target is the response Contact.
     */
    public static Dialog asUac(SipRequest request, SipResponse response) {
        List<NameAddress> rr = new ArrayList<>(response.recordRoutes());
        Collections.reverse(rr);
        SipUri target = response.contact().map(NameAddress::uri).orElse(request.requestUri());
        NameAddress from = request.from();
        NameAddress to = response.to();
        return new Dialog(request.callId(), from.tag(), to.tag(), from.withoutParameter("tag"),
                to.withoutParameter("tag"), request.requestUri().isSecure(), rr, target,
                request.cseq().number(), -1,
                response.isSuccess() ? DialogState.CONFIRMED : DialogState.EARLY);
    }

    /**
     * Creates the UAS side of a dialog for a request we answer with a tagged response (RFC 3261 §12.1.1): the route
     * set is the Record-Route list in order, the remote target the request Contact.
     *
     * @param localTag the tag placed in the To header of our responses
     */
    public static Dialog asUas(SipRequest request, String localTag, boolean confirmed) {
        SipUri target = request.contact().map(NameAddress::uri).orElse(request.from().uri());
        NameAddress from = request.from();
        NameAddress to = request.to();
        return new Dialog(request.callId(), localTag, from.tag(), to.withoutParameter("tag"),
                from.withoutParameter("tag"), request.requestUri().isSecure(), request.recordRoutes(), target,
                0, request.cseq().number(), confirmed ? DialogState.CONFIRMED : DialogState.EARLY);
    }

    public DialogId id() {
        return new DialogId(callId, localTag, remoteTag);
    }

    public String callId() {
        return callId;
    }

    public String localTag() {
        return localTag;
    }

    public String remoteTag() {
        return remoteTag;
    }

    public NameAddress localAddress() {
        return localAddress;
    }

    public NameAddress remoteAddress() {
        return remoteAddress;
    }

    public SipUri remoteTarget() {
        return remoteTarget;
    }

    public List<NameAddress> routeSet() {
        return List.copyOf(routeSet);
    }

    public long localSequence() {
        return localSeq;
    }

    public long remoteSequence() {
        return remoteSeq;
    }

    public DialogState state() {
        return state;
    }

    public boolean isSecure() {
        return secure;
    }

    public void confirm() {
        if (state == DialogState.EARLY) {
            state = DialogState.CONFIRMED;
        }
    }

    public void terminate() {
        state = DialogState.TERMINATED;
    }

    /**
     * Applies a 2xx for an early dialog or a target refresh (re-INVITE / its 2xx, RFC 3261 §12.2.1.2, §12.2.2):
     * updates the remote target from the Contact header. The route set is not changed after dialog creation.
     */
    public void refreshTarget(NameAddress contact) {
        if (contact != null && !contact.isWildcard()) {
            remoteTarget = contact.uri();
        }
    }

    /** Updates route set and target from the 2xx confirming an early UAC dialog (RFC 3261 §12.1.2, §13.2.2.4). */
    public void confirmWith(SipResponse response) {
        List<NameAddress> rr = new ArrayList<>(response.recordRoutes());
        Collections.reverse(rr);
        routeSet = rr;
        response.contact().ifPresent(this::refreshTarget);
        confirm();
    }

    /**
     * Validates the CSeq of a request received within the dialog (RFC 3261 §12.2.2). Requests with a lower CSeq
     * than the last one must be answered with 500.
     *
     * @return false if the request is out of order
     */
    public boolean acceptRemoteSequence(SipRequest request) {
        long cseq = request.cseq().number();
        if (request.isMethod(SipMethod.ACK) || request.isMethod(SipMethod.CANCEL)) {
            return true;
        }
        if (remoteSeq >= 0 && cseq < remoteSeq) {
            return false;
        }
        remoteSeq = cseq;
        return true;
    }

    /**
     * Builds a request within the dialog (RFC 3261 §12.2.1.1). The local sequence number is incremented for all
     * methods except ACK and CANCEL, which reuse the INVITE's number via {@link #createAck(long)}. Via and Contact
     * are added by the caller / transaction layer.
     */
    public SipRequest createRequest(SipMethod method) {
        long seq = ++localSeq;
        return build(method, seq);
    }

    /** Increments and returns the local sequence number, e.g. for re-sending a request with credentials. */
    public long nextLocalSequence() {
        return ++localSeq;
    }

    /** Builds the ACK for a 2xx response to the INVITE with the given CSeq number (RFC 3261 §13.2.2.4). */
    public SipRequest createAck(long inviteCseq) {
        return build(SipMethod.ACK, inviteCseq);
    }

    private SipRequest build(SipMethod method, long seq) {
        SipUri requestUri;
        List<NameAddress> routes = new ArrayList<>(routeSet);
        if (routes.isEmpty() || routes.getFirst().uri().isLooseRouter()) {
            requestUri = remoteTarget;
        } else {
            // Strict routing (RFC 3261 §12.2.1.1): first route becomes the Request-URI, remote target is appended
            requestUri = routes.removeFirst().uri().withoutParameter("method");
            routes.add(new NameAddress(remoteTarget));
        }
        SipRequest r = new SipRequest(method, requestUri);
        for (NameAddress route : routes) {
            r.headers().add(HeaderNames.ROUTE, route.toString());
        }
        r.headers().add(HeaderNames.MAX_FORWARDS, "70");
        r.headers().add(HeaderNames.FROM, localAddress.withTag(localTag).toString());
        NameAddress to = remoteTag == null ? remoteAddress : remoteAddress.withTag(remoteTag);
        r.headers().add(HeaderNames.TO, to.toString());
        r.headers().add(HeaderNames.CALL_ID, callId);
        r.headers().add(HeaderNames.CSEQ, new CSeq(seq, method).toString());
        return r;
    }

    @Override
    public String toString() {
        return "Dialog[" + id() + ", " + state + "]";
    }
}
