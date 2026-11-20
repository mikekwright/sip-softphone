package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;

/**
 * Observes and optionally modifies or drops every SIP message crossing the transport boundary. Interceptors are the
 * main hook for test automation: logging SIP ladders, asserting on headers, injecting faults (dropping packets to
 * exercise retransmission timers) or rewriting messages to emulate broken peers.
 *
 * <p>Interceptors run on the SIP core thread in registration order and must not block.</p>
 */
public interface SipMessageInterceptor {

    /**
     * Called for each received message after parsing and before the transaction layer sees it.
     *
     * @return the message to process (possibly modified), or {@code null} to drop it
     */
    default SipMessage onReceive(InboundMessage inbound) {
        return inbound.message();
    }

    /**
     * Called for each message just before it is written to the network (including retransmissions).
     *
     * @return the message to send (possibly modified), or {@code null} to drop it
     */
    default SipMessage onSend(SipMessage message, TransportAddress destination) {
        return message;
    }
}
