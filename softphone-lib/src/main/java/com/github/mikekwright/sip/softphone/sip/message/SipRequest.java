package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Objects;

/**
 * A SIP request (RFC 3261 §7.1): {@code Method SP Request-URI SP SIP-Version CRLF}, headers and body.
 */
public final class SipRequest extends SipMessage {

    private final SipMethod method;
    private SipUri requestUri;

    public SipRequest(SipMethod method, SipUri requestUri) {
        this(method, requestUri, new SipHeaders());
    }

    public SipRequest(SipMethod method, SipUri requestUri, SipHeaders headers) {
        super(headers);
        this.method = Objects.requireNonNull(method, "method");
        this.requestUri = Objects.requireNonNull(requestUri, "requestUri");
    }

    public SipMethod method() {
        return method;
    }

    public boolean isMethod(SipMethod m) {
        return method.equals(m);
    }

    public SipUri requestUri() {
        return requestUri;
    }

    public SipRequest setRequestUri(SipUri uri) {
        this.requestUri = Objects.requireNonNull(uri, "uri");
        return this;
    }

    @Override
    public String startLine() {
        return method + " " + requestUri + " " + SIP_VERSION;
    }

    @Override
    public SipRequest copy() {
        SipRequest r = new SipRequest(method, requestUri, new SipHeaders(headers));
        r.body = body.clone();
        return r;
    }

    // Covariant overrides for fluent use
    @Override
    public SipRequest setHeader(String name, Object value) {
        super.setHeader(name, value);
        return this;
    }

    @Override
    public SipRequest addHeader(String name, Object value) {
        super.addHeader(name, value);
        return this;
    }

    @Override
    public SipRequest setBody(String contentType, String content) {
        super.setBody(contentType, content);
        return this;
    }

    @Override
    public SipRequest setBody(String contentType, byte[] content) {
        super.setBody(contentType, content);
        return this;
    }
}
