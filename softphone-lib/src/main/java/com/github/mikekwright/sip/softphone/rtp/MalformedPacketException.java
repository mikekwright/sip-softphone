package com.github.mikekwright.sip.softphone.rtp;

/**
 * Thrown when an RTP or RTCP packet cannot be decoded because it violates the wire format of
 * RFC 3550 (wrong version, truncated header, inconsistent length or padding, ...).
 *
 * <p>Receivers are expected to catch this exception and silently discard the offending datagram,
 * as recommended by RFC 3550 §A.1 and §A.2 (packet validity checks).</p>
 */
public class MalformedPacketException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public MalformedPacketException(String message) {
        super(message);
    }
}
