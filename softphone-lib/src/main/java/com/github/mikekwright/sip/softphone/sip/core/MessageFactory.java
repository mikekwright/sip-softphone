package com.github.mikekwright.sip.softphone.sip.core;

import com.github.mikekwright.sip.softphone.sip.message.CSeq;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transport.HostPort;
import com.github.mikekwright.sip.softphone.sip.transport.TransportLayer;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Creates requests and responses with the headers RFC 3261 requires (§8.1.1 for requests, §8.2.6 for responses).
 */
public final class MessageFactory {

    /** Methods this UA accepts, advertised in {@code Allow} headers (RFC 3261 §20.5). */
    public static final List<SipMethod> DEFAULT_ALLOW = List.of(SipMethod.INVITE, SipMethod.ACK, SipMethod.CANCEL,
            SipMethod.BYE, SipMethod.OPTIONS, SipMethod.INFO, SipMethod.MESSAGE);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransportLayer transport;
    private final String userAgent;
    private volatile List<SipMethod> allow = DEFAULT_ALLOW;

    public MessageFactory(TransportLayer transport, String userAgent) {
        this.transport = transport;
        this.userAgent = userAgent;
    }

    /** A new globally unique Call-ID (RFC 3261 §8.1.1.4). */
    public String newCallId() {
        return random(16) + "@" + transport.advertisedAddress(transport.transportTypes().getFirst()).host();
    }

    /** A new tag with at least 32 bits of randomness (RFC 3261 §19.3). */
    public String newTag() {
        return random(6);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public void setAllow(List<SipMethod> methods) {
        this.allow = List.copyOf(methods);
    }

    public String allowHeader() {
        return String.join(", ", allow.stream().map(SipMethod::name).toList());
    }

    /** The Contact URI for a user on the given transport (advertised host and port). */
    public SipUri contactUri(String user, TransportType type) {
        HostPort hp = transport.advertisedAddress(type);
        SipUri uri = SipUri.sip(user, hp.uriHost(), hp.port());
        return type == TransportType.UDP ? uri : uri.withParameter("transport", type.uriParameter());
    }

    /**
     * Creates an out-of-dialog request (RFC 3261 §8.1.1): Request-URI, To (no tag), From (new tag), Call-ID, CSeq,
     * Max-Forwards. Via is added by the transaction layer; Contact by the caller where required.
     */
    public SipRequest createRequest(SipMethod method, SipUri requestUri, NameAddress from, NameAddress to,
                                    String callId, long cseq) {
        SipRequest r = new SipRequest(method, requestUri);
        r.headers().add(HeaderNames.MAX_FORWARDS, "70");
        r.headers().add(HeaderNames.FROM, (from.tag() == null ? from.withTag(newTag()) : from).toString());
        r.headers().add(HeaderNames.TO, to.toString());
        r.headers().add(HeaderNames.CALL_ID, callId == null ? newCallId() : callId);
        r.headers().add(HeaderNames.CSEQ, new CSeq(cseq, method).toString());
        decorate(r);
        return r;
    }

    /** Adds User-Agent and, for dialog-creating or capability requests, Allow and Supported. */
    public void decorate(SipRequest r) {
        if (!r.headers().contains(HeaderNames.USER_AGENT)) {
            r.headers().add(HeaderNames.USER_AGENT, userAgent);
        }
        if ((r.isMethod(SipMethod.INVITE) || r.isMethod(SipMethod.OPTIONS) || r.isMethod(SipMethod.REGISTER))
                && !r.headers().contains(HeaderNames.ALLOW)) {
            r.headers().add(HeaderNames.ALLOW, allowHeader());
        }
    }

    /**
     * Creates a response (RFC 3261 §8.2.6.2): copies Via (all values, in order), From, To, Call-ID and CSeq; adds a
     * To tag when given and absent; copies Timestamp into 100 Trying (§8.2.6.1).
     */
    public SipResponse createResponse(SipRequest request, int status, String toTag) {
        SipResponse r = new SipResponse(status);
        r.headers().copyFrom(request.headers(), HeaderNames.VIA);
        r.headers().add(HeaderNames.FROM, request.header(HeaderNames.FROM));
        NameAddress to = request.to();
        if (toTag != null && to.tag() == null) {
            to = to.withTag(toTag);
        }
        r.headers().add(HeaderNames.TO, to.toString());
        r.headers().add(HeaderNames.CALL_ID, request.callId());
        r.headers().add(HeaderNames.CSEQ, request.header(HeaderNames.CSEQ));
        if (status == 100 && request.header(HeaderNames.TIMESTAMP) != null) {
            r.headers().add(HeaderNames.TIMESTAMP, request.header(HeaderNames.TIMESTAMP));
        }
        r.headers().add(HeaderNames.SERVER, userAgent);
        if (status == 405 || (request.isMethod(SipMethod.OPTIONS) && status == 200)
                || (request.isMethod(SipMethod.INVITE) && status >= 200 && status < 300)) {
            r.headers().add(HeaderNames.ALLOW, allowHeader());
        }
        r.setRawBody(new byte[0]);
        return r;
    }
}
