package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;

/**
 * The layer above the transaction layer (RFC 3261 §8, the "TU"): the UA core. Receives new requests and the
 * messages that do not belong to any transaction. All callbacks run on the SIP core executor.
 */
public interface TransactionUser {

    /** A new request (other than ACK) created a server transaction. The TU must eventually send a final response. */
    void onRequest(ServerTransaction transaction, SipRequest request);

    /** An ACK that matched no INVITE server transaction, i.e. the ACK for a 2xx response (RFC 3261 §13.3.1.4). */
    void onAck(InboundMessage ack);

    /** A response that matched no client transaction, e.g. a 2xx retransmission after Timer M (RFC 6026 §8.4). */
    void onStrayResponse(InboundMessage response);
}
