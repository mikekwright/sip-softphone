package com.github.mikekwright.sip.softphone.sip.message;

/**
 * Thrown when a SIP message, URI or header value does not conform to the RFC 3261 §25 grammar.
 */
public class SipParseException extends RuntimeException {

    public SipParseException(String message) {
        super(message);
    }

    public SipParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
