package com.github.mikekwright.sip.softphone.sip.core;

/**
 * Handles requests of one method that arrive outside of any dialog (e.g. OPTIONS, MESSAGE, INVITE). Register with
 * {@link SipStack#registerHandler}. Invoked on the SIP core executor; handlers must respond (possibly later) and
 * must not block.
 */
@FunctionalInterface
public interface RequestHandler {

    void handle(IncomingRequest request);
}
