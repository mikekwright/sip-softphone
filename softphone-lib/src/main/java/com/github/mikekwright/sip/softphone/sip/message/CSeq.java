package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Objects;

/**
 * The {@code CSeq} header (RFC 3261 §20.16): a sequence number and the request method, used to order requests
 * within a dialog and to match responses (and CANCEL/ACK) to requests.
 *
 * @param number sequence number, must be less than 2^31
 * @param method request method
 */
public record CSeq(long number, SipMethod method) {

    public CSeq {
        Objects.requireNonNull(method, "method");
        if (number < 0 || number > 0x7FFFFFFFL) {
            throw new IllegalArgumentException("CSeq number out of range: " + number);
        }
    }

    public static CSeq parse(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) {
            throw new SipParseException("malformed CSeq: " + text);
        }
        try {
            return new CSeq(Long.parseLong(parts[0]), SipMethod.of(parts[1]));
        } catch (IllegalArgumentException e) {
            throw new SipParseException("malformed CSeq: " + text, e);
        }
    }

    @Override
    public String toString() {
        return number + " " + method;
    }
}
