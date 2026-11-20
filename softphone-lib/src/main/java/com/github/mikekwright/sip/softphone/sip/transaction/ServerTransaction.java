package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;

/**
 * Base of the server transaction state machines (RFC 3261 §17.2). The transaction user answers the request with
 * {@link #sendResponse(SipResponse)}; the transaction takes care of retransmissions and absorbing duplicates.
 */
public abstract sealed class ServerTransaction extends Transaction
        permits InviteServerTransaction, NonInviteServerTransaction {

    protected final InboundMessage origin;
    protected SipResponse lastResponse;

    protected ServerTransaction(TransactionLayer layer, String key, InboundMessage origin, TransactionState initial) {
        super(layer, key, (SipRequest) origin.message(), origin.transport().isReliable(), initial);
        this.origin = origin;
    }

    /** The received request with its transport context (source address, connection). */
    public InboundMessage origin() {
        return origin;
    }

    public SipResponse lastResponse() {
        return lastResponse;
    }

    /** True once a final response has been sent. */
    public boolean hasFinalResponse() {
        return lastResponse != null && lastResponse.isFinal();
    }

    /**
     * Sends a response created by the TU. Must be called on the SIP core executor.
     *
     * @throws IllegalStateException if the state machine does not allow the response (e.g. a second final response)
     */
    public abstract void sendResponse(SipResponse response);

    /** A retransmission of the original request arrived. */
    abstract void receiveRetransmission(InboundMessage request);

    protected void transmit(SipResponse response) {
        layer.transport().sendResponse(response, origin);
    }
}
