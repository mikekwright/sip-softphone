package com.github.mikekwright.sip.softphone.sip.transaction;

/**
 * States of the four RFC 3261 §17 transaction state machines, including the {@code Accepted} state added by
 * RFC 6026 for INVITE transactions that received / sent a 2xx response.
 */
public enum TransactionState {
    /** INVITE client: request sent, waiting for a response (Timers A and B running). */
    CALLING,
    /** Non-INVITE client and server: request sent / received, no provisional response yet. */
    TRYING,
    /** A provisional response was received / sent. */
    PROCEEDING,
    /** A non-2xx final response was received / sent; absorbing retransmissions. */
    COMPLETED,
    /** INVITE server: ACK received for a non-2xx final response (Timer I running). */
    CONFIRMED,
    /** INVITE client / server: a 2xx was received / sent (RFC 6026, Timers M / L). */
    ACCEPTED,
    /** Final state; the transaction has been removed from the transaction layer. */
    TERMINATED
}
