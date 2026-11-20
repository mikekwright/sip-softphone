package com.github.mikekwright.sip.softphone.sdp;

/**
 * Thrown when an offer or answer violates the offer/answer rules of RFC 3264, e.g. an answer whose {@code m=}
 * lines do not correspond to those of the offer (RFC 3264 §6). In SIP this typically leads to a {@code 488 Not
 * Acceptable Here} response or a {@code BYE}.
 */
public class SdpNegotiationException extends RuntimeException {

    public SdpNegotiationException(String message) {
        super(message);
    }
}
