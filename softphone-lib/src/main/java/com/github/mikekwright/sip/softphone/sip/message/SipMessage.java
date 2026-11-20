package com.github.mikekwright.sip.softphone.sip.message;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Common base of SIP requests and responses (RFC 3261 §7): a start line, header fields and an optional body.
 *
 * <p>Messages are mutable so that protocol layers can add headers (e.g. a {@code Via}) as a message moves down the
 * stack; layers that keep a message for later (transactions, dialogs) work on {@link #copy() copies}. Typed accessors
 * parse the raw header values on demand. Instances are not thread-safe.</p>
 */
public abstract sealed class SipMessage permits SipRequest, SipResponse {

    public static final String SIP_VERSION = "SIP/2.0";

    protected final SipHeaders headers;
    protected byte[] body = new byte[0];

    protected SipMessage(SipHeaders headers) {
        this.headers = headers;
    }

    /** The first line of the message, without CRLF. */
    public abstract String startLine();

    /** Creates a deep copy of this message. */
    public abstract SipMessage copy();

    public SipHeaders headers() {
        return headers;
    }

    public String header(String name) {
        return headers.get(name);
    }

    public SipMessage setHeader(String name, Object value) {
        headers.set(name, String.valueOf(value));
        return this;
    }

    public SipMessage addHeader(String name, Object value) {
        headers.add(name, String.valueOf(value));
        return this;
    }

    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public boolean hasBody() {
        return body.length > 0;
    }

    /** Sets the body and its {@code Content-Type}; pass a {@code null} type to remove the body. */
    public SipMessage setBody(String contentType, byte[] content) {
        if (contentType == null || content == null || content.length == 0) {
            this.body = new byte[0];
            headers.remove(HeaderNames.CONTENT_TYPE);
        } else {
            this.body = content.clone();
            headers.set(HeaderNames.CONTENT_TYPE, contentType);
        }
        headers.set(HeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
        return this;
    }

    /**
     * Replaces the body bytes without touching {@code Content-Type}; used by parsers and by code that manages the
     * content type header itself.
     */
    public SipMessage setRawBody(byte[] content) {
        this.body = content == null ? new byte[0] : content.clone();
        headers.set(HeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
        return this;
    }

    public SipMessage setBody(String contentType, String content) {
        return setBody(contentType, content == null ? null : content.getBytes(StandardCharsets.UTF_8));
    }

    /** The media type of the body without parameters, lower-cased, or {@code null}. */
    public String contentType() {
        String ct = headers.get(HeaderNames.CONTENT_TYPE);
        if (ct == null) {
            return null;
        }
        int semi = ct.indexOf(';');
        return (semi < 0 ? ct : ct.substring(0, semi)).trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- typed accessors for the mandatory headers (RFC 3261 §8.1.1) ----

    public List<Via> vias() {
        return headers.all(HeaderNames.VIA, Via::parse);
    }

    public Via topVia() {
        String v = headers.get(HeaderNames.VIA);
        if (v == null) {
            throw new SipParseException("message has no Via header");
        }
        return Via.parse(v);
    }

    public NameAddress from() {
        return NameAddress.parse(required(HeaderNames.FROM));
    }

    public NameAddress to() {
        return NameAddress.parse(required(HeaderNames.TO));
    }

    public String callId() {
        return required(HeaderNames.CALL_ID);
    }

    public CSeq cseq() {
        return CSeq.parse(required(HeaderNames.CSEQ));
    }

    public List<NameAddress> contacts() {
        return headers.all(HeaderNames.CONTACT, NameAddress::parse);
    }

    public Optional<NameAddress> contact() {
        return headers.first(HeaderNames.CONTACT).map(NameAddress::parse);
    }

    public List<NameAddress> recordRoutes() {
        return headers.all(HeaderNames.RECORD_ROUTE, NameAddress::parse);
    }

    public List<NameAddress> routes() {
        return headers.all(HeaderNames.ROUTE, NameAddress::parse);
    }

    /** The {@code Expires} header value in seconds, if present and numeric. */
    public Optional<Integer> expires() {
        return headers.first(HeaderNames.EXPIRES).flatMap(SipMessage::parseInt);
    }

    public Optional<Integer> maxForwards() {
        return headers.first(HeaderNames.MAX_FORWARDS).flatMap(SipMessage::parseInt);
    }

    /** True if the option tag is listed in the given header (Supported, Require, ...). */
    public boolean hasOptionTag(String header, String tag) {
        return headers.all(header).stream().anyMatch(v -> v.trim().equalsIgnoreCase(tag));
    }

    protected String required(String name) {
        String v = headers.get(name);
        if (v == null) {
            throw new SipParseException("missing mandatory header " + name);
        }
        return v;
    }

    private static Optional<Integer> parseInt(String s) {
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Serializes the message to wire format (RFC 3261 §7). {@code Content-Length} is always written and always
     * reflects the actual body length.
     */
    public byte[] encode() {
        StringBuilder sb = new StringBuilder(512);
        sb.append(startLine()).append("\r\n");
        String previous = null;
        for (SipHeaders.Header h : headers) {
            if (h.name().equalsIgnoreCase(HeaderNames.CONTENT_LENGTH)) {
                continue;
            }
            if (h.name().equals(previous) && HeaderNames.isCombinable(h.name())) {
                // RFC 3261 §7.3.1: consecutive values of a list header may share one line
                sb.setLength(sb.length() - 2);
                sb.append(", ").append(h.value()).append("\r\n");
            } else {
                sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
            }
            previous = h.name();
        }
        sb.append(HeaderNames.CONTENT_LENGTH).append(": ").append(body.length).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + body.length);
        out.writeBytes(head);
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** Human readable form (the wire format decoded as UTF-8). */
    @Override
    public String toString() {
        return new String(encode(), StandardCharsets.UTF_8);
    }
}
