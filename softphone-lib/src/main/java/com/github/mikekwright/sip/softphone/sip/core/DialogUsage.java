package com.github.mikekwright.sip.softphone.sip.core;

import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;

/**
 * Receives requests that belong to an existing dialog (RFC 3261 §12.2.2), e.g. BYE, re-INVITE, INFO and the ACK
 * for a 2xx. A dialog usage (such as an INVITE session) registers itself with {@link SipStack#registerDialog}.
 * Invoked on the SIP core executor.
 */
public interface DialogUsage {

    /** A request (other than ACK) within the dialog; the CSeq has already been validated. */
    void onRequest(IncomingRequest request);

    /** The ACK for a 2xx response sent by this usage. */
    void onAck(InboundMessage ack);

    /**
     * A response that no longer matches a client transaction but belongs to this dialog, typically a retransmitted
     * 2xx to INVITE that must be acknowledged again (RFC 3261 §13.2.2.4).
     */
    default void onStrayResponse(InboundMessage response) {
    }
}
