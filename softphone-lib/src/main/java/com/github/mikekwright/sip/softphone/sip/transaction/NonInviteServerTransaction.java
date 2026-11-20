package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;

/**
 * The non-INVITE server transaction (RFC 3261 §17.2.2, Figure 8).
 *
 * <pre>
 *                   |Request received
 *                   |pass to TU
 *                   V
 *             +-----------+
 *             |  Trying   |-------------+
 *             +-----------+             |
 *                |  |                   |
 *                |  |200-699 from TU    |
 *                |  |send response      |
 *  1xx from TU   |  +---------------+   |
 *  send response |                  |   |
 *                V                  |   |
 * Request   +-----------+           |   |
 * send resp |Proceeding |           |   |
 *    +------|           |---+       |   |
 *    +----->+-----------+   |200-699|   |
 *     1xx from TU |         |from TU|   |
 *                 V         V       V   |
 *         Request  +-----------+        |
 *         send resp|           |        |
 *            +-----| Completed |        |
 *            +---->+-----------+        |
 *                    | Timer J fires    |
 *                    V                  |
 *             +-----------+             |
 *             | Terminated|&lt;------------+
 *             +-----------+
 * </pre>
 */
public final class NonInviteServerTransaction extends ServerTransaction {

    NonInviteServerTransaction(TransactionLayer layer, String key, InboundMessage origin) {
        super(layer, key, origin, TransactionState.TRYING);
    }

    @Override
    public void sendResponse(SipResponse response) {
        TransactionState s = state();
        if (s != TransactionState.TRYING && s != TransactionState.PROCEEDING) {
            throw new IllegalStateException("cannot send " + response.statusCode() + " in state " + s);
        }
        lastResponse = response;
        transmit(response);
        if (response.isProvisional()) {
            transition(TransactionState.PROCEEDING);
        } else {
            transition(TransactionState.COMPLETED);
            startTimer("J", reliable ? 0 : timers().timeoutMillis(), () -> transition(TransactionState.TERMINATED));
        }
    }

    @Override
    void receiveRetransmission(InboundMessage request) {
        if (state() != TransactionState.TRYING && lastResponse != null) {
            transmit(lastResponse);
        }
    }
}
