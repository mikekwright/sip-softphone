package com.github.mikekwright.sip.softphone.sip.session;

/**
 * Why an {@link InviteSession} ended.
 *
 * @param cause      category of the termination
 * @param statusCode the SIP status code involved (final response to the INVITE, 200 for BYE...), 0 if none
 * @param reason     human readable detail
 */
public record TerminationReason(Cause cause, int statusCode, String reason) {

    public enum Cause {
        /** We sent BYE. */
        LOCAL_BYE,
        /** The remote party sent BYE. */
        REMOTE_BYE,
        /** We cancelled our outgoing INVITE (CANCEL / 487). */
        LOCAL_CANCEL,
        /** The caller cancelled an incoming INVITE before we answered. */
        REMOTE_CANCEL,
        /** We rejected an incoming INVITE with a non-2xx final response. */
        LOCAL_REJECT,
        /** The remote party rejected our INVITE with a non-2xx final response (busy, declined, not found...). */
        REMOTE_REJECT,
        /** No response (Timer B) or no ACK for our 2xx (RFC 3261 §13.3.1.4). */
        TIMEOUT,
        /** The request could not be sent. */
        TRANSPORT_ERROR
    }

    public static TerminationReason of(Cause cause, int status, String reason) {
        return new TerminationReason(cause, status, reason);
    }

    public boolean isFailure() {
        return cause == Cause.REMOTE_REJECT || cause == Cause.TIMEOUT || cause == Cause.TRANSPORT_ERROR;
    }
}
