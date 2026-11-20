package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;

/**
 * The INVITE server transaction (RFC 3261 §17.2.1, Figure 7, as updated by RFC 6026 Figure 5).
 *
 * <pre>
 *                                |INVITE
 *                                |pass INV to TU
 *             INVITE             V send 100 Trying
 *             send response+------------+
 *                 +--------|            |--------+ 101-199 from TU
 *                 |        |  Proceeding|        | send response
 *                 +------->|            |&lt;-------+
 *                          |            |          Transport Err.
 *                          |            |          Inform TU
 *                          |            |--------------->+
 *                          +------------+                |
 *             300-699 from TU |     |2xx from TU         |
 *             send response   |     |send response       |
 *              +--------------+     +------------+       |
 *              |                                 |       |
 * INVITE       V          Timer G fires          |       |
 * send resp+-----------+  send response          |       |
 *   +------|           |------+                  |       |
 *   |      | Completed |      |                  |       |
 *   +----->|           |&lt;-----+                  |       |
 *          +-----------+                         |       |
 *            |  ACK  |      Timer H fires        V       |
 *            |       +----------------->+   +--------+   |   2xx from TU
 *            |         or Transport Err.|   |Accepted|---+-> send response
 *            V         Inform TU        |   +--------+   |
 *         +-----------+                 |        |Timer L|
 *         | Confirmed |                 |        V       |
 *         +-----------+                 |   +-----------+|
 *               |Timer I                +-->| Terminated |&lt;+
 *               +-------------------------->+-----------+
 * </pre>
 *
 * <p>Retransmission of 2xx responses until the ACK arrives is the responsibility of the UAS core
 * (RFC 3261 §13.3.1.4), not of this transaction.</p>
 */
public final class InviteServerTransaction extends ServerTransaction {

    private long timerGInterval;
    private Runnable noAckHandler = () -> {
    };
    private Runnable cancelHandler;

    InviteServerTransaction(TransactionLayer layer, String key, InboundMessage origin) {
        super(layer, key, origin, TransactionState.PROCEEDING);
    }

    /** Invoked if Timer H fires: a non-2xx final response was never acknowledged. */
    public void onNoAck(Runnable handler) {
        this.noAckHandler = handler;
    }

    /**
     * Registers the action the UAS core takes when a CANCEL for this INVITE arrives (RFC 3261 §9.2), typically
     * responding 487 and tearing down the pending session.
     */
    public void onCancel(Runnable handler) {
        this.cancelHandler = handler;
    }

    /** The registered CANCEL handler or {@code null}. */
    public Runnable cancelHandler() {
        return cancelHandler;
    }

    @Override
    public void sendResponse(SipResponse response) {
        int code = response.statusCode();
        switch (state()) {
            case PROCEEDING -> {
                lastResponse = response;
                transmit(response);
                if (code >= 200 && code < 300) {
                    transition(TransactionState.ACCEPTED);
                    startTimer("L", timers().timeoutMillis(), () -> transition(TransactionState.TERMINATED));
                } else if (code >= 300) {
                    transition(TransactionState.COMPLETED);
                    if (!reliable) {
                        timerGInterval = timers().t1Millis();
                        startTimer("G", timerGInterval, this::timerG);
                    }
                    startTimer("H", timers().timeoutMillis(), () -> {
                        transition(TransactionState.TERMINATED);
                        noAckHandler.run();
                    });
                }
            }
            case ACCEPTED -> {
                if (code >= 200 && code < 300) {
                    lastResponse = response;
                    transmit(response); // 2xx retransmission driven by the UAS core
                } else {
                    throw new IllegalStateException("only 2xx retransmissions are allowed in Accepted state");
                }
            }
            default -> throw new IllegalStateException("cannot send " + code + " in state " + state());
        }
    }

    private void timerG() {
        if (state() == TransactionState.COMPLETED) {
            transmit(lastResponse);
            timerGInterval = Math.min(2 * timerGInterval, timers().t2Millis());
            startTimer("G", timerGInterval, this::timerG);
        }
    }

    @Override
    void receiveRetransmission(InboundMessage request) {
        if ((state() == TransactionState.PROCEEDING || state() == TransactionState.COMPLETED)
                && lastResponse != null) {
            transmit(lastResponse);
        }
        // Accepted / Confirmed: absorb
    }

    /** An ACK matched this transaction (only possible for non-2xx final responses, RFC 3261 §17.2.3). */
    void receiveAck() {
        if (state() == TransactionState.COMPLETED) {
            cancelTimer("G");
            cancelTimer("H");
            transition(TransactionState.CONFIRMED);
            startTimer("I", reliable ? 0 : timers().t4Millis(), () -> transition(TransactionState.TERMINATED));
        }
    }
}
