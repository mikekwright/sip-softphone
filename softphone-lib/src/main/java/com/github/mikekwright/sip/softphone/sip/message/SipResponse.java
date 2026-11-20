package com.github.mikekwright.sip.softphone.sip.message;

/**
 * A SIP response (RFC 3261 §7.2): {@code SIP-Version SP Status-Code SP Reason-Phrase CRLF}, headers and body.
 */
public final class SipResponse extends SipMessage {

    private int statusCode;
    private String reasonPhrase;

    public SipResponse(int statusCode) {
        this(statusCode, SipStatus.reasonPhrase(statusCode), new SipHeaders());
    }

    public SipResponse(int statusCode, String reasonPhrase, SipHeaders headers) {
        super(headers);
        if (statusCode < 100 || statusCode > 699) {
            throw new IllegalArgumentException("status code out of range: " + statusCode);
        }
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase == null ? SipStatus.reasonPhrase(statusCode) : reasonPhrase;
    }

    public int statusCode() {
        return statusCode;
    }

    public String reasonPhrase() {
        return reasonPhrase;
    }

    public SipResponse setStatus(int code, String reason) {
        this.statusCode = code;
        this.reasonPhrase = reason == null ? SipStatus.reasonPhrase(code) : reason;
        return this;
    }

    public boolean isProvisional() {
        return SipStatus.isProvisional(statusCode);
    }

    public boolean isSuccess() {
        return SipStatus.isSuccess(statusCode);
    }

    public boolean isFinal() {
        return SipStatus.isFinal(statusCode);
    }

    /** The class of the response: 1 for 1xx, 2 for 2xx and so on. */
    public int statusClass() {
        return statusCode / 100;
    }

    @Override
    public String startLine() {
        return SIP_VERSION + " " + statusCode + " " + reasonPhrase;
    }

    @Override
    public SipResponse copy() {
        SipResponse r = new SipResponse(statusCode, reasonPhrase, new SipHeaders(headers));
        r.body = body.clone();
        return r;
    }

    @Override
    public SipResponse setHeader(String name, Object value) {
        super.setHeader(name, value);
        return this;
    }

    @Override
    public SipResponse addHeader(String name, Object value) {
        super.addHeader(name, value);
        return this;
    }

    @Override
    public SipResponse setBody(String contentType, String content) {
        super.setBody(contentType, content);
        return this;
    }

    @Override
    public SipResponse setBody(String contentType, byte[] content) {
        super.setBody(contentType, content);
        return this;
    }
}
