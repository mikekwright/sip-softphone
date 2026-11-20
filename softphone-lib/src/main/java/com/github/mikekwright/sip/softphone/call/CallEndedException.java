package com.github.mikekwright.sip.softphone.call;

import com.github.mikekwright.sip.softphone.sip.session.TerminationReason;

/** Completes call state futures exceptionally when the call ends before reaching the awaited state. */
public class CallEndedException extends RuntimeException {

    private final transient TerminationReason reason;

    public CallEndedException(TerminationReason reason) {
        super("call ended: " + reason);
        this.reason = reason;
    }

    public TerminationReason reason() {
        return reason;
    }
}
