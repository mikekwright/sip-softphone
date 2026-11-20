package com.github.mikekwright.sip.softphone.sip.session;

import com.github.mikekwright.sip.softphone.sip.message.SipResponse;

/** A request within a session failed with a non-2xx final response. */
public class SessionRequestException extends RuntimeException {

    private final transient SipResponse response;

    public SessionRequestException(SipResponse response) {
        super(response.statusCode() + " " + response.reasonPhrase());
        this.response = response;
    }

    public SipResponse response() {
        return response;
    }

    public int statusCode() {
        return response.statusCode();
    }
}
