package com.github.mikekwright.sip.softphone.sip.transport;

import java.util.Locale;

/**
 * The SIP transport protocols supported by the library (RFC 3261 §18).
 */
public enum TransportType {
    /** Unreliable datagram transport; transactions retransmit (Timers A, E, G). */
    UDP(false, 5060),
    /** Reliable stream transport; messages are framed by Content-Length. */
    TCP(true, 5060),
    /** TCP with TLS (RFC 3261 §26.2.1, {@code sips:} URIs). */
    TLS(true, 5061);

    private final boolean reliable;
    private final int defaultPort;

    TransportType(boolean reliable, int defaultPort) {
        this.reliable = reliable;
        this.defaultPort = defaultPort;
    }

    /** Reliable transports disable retransmission timers and use zero wait times (RFC 3261 §17). */
    public boolean isReliable() {
        return reliable;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** Token used in the Via sent-protocol, e.g. {@code UDP}. */
    public String viaToken() {
        return name();
    }

    /** Value used for the {@code transport} URI parameter, e.g. {@code tcp}. */
    public String uriParameter() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Resolves a Via token or URI transport parameter; returns {@code null} if unknown. */
    public static TransportType fromToken(String token) {
        if (token == null) {
            return null;
        }
        for (TransportType t : values()) {
            if (t.name().equalsIgnoreCase(token.trim())) {
                return t;
            }
        }
        return null;
    }
}
