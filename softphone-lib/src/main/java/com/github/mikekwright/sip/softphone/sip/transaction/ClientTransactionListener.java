package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipResponse;

/**
 * Events a client transaction delivers to its transaction user. Invoked on the SIP core executor.
 */
public interface ClientTransactionListener {

    /**
     * A response matched the transaction. Provisional responses and the first final response are always delivered;
     * for INVITE, retransmitted 2xx responses are delivered too (RFC 6026 Accepted state) so that the TU can resend
     * the ACK.
     */
    void onResponse(ClientTransaction transaction, SipResponse response);

    /** Timer B or F fired: no final response arrived (treated as a 408 by RFC 3261 §8.1.3.1). */
    default void onTimeout(ClientTransaction transaction) {
    }

    /** The request could not be sent (RFC 3261 §8.1.3.1 treats this as a 503). */
    default void onTransportError(ClientTransaction transaction, Throwable cause) {
    }

    default void onTerminated(ClientTransaction transaction) {
    }
}
