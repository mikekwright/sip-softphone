package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;

/**
 * The non-INVITE client transaction (RFC 3261 §17.1.2, Figure 6).
 *
 * <pre>
 *                     |Request from TU
 *                     |send request
 * Timer E             V
 * send request  +-----------+
 *     +---------|  Trying   |-------------+
 *     |         +-----------+             |
 *     +-------->  |  |                    |
 *                 |  |200-699             |
 *                 |  |resp. to TU         |
 *  1xx            |  +---------------+    |
 *  resp. to TU    |                  |    | Timer F
 *     +-----------+                  |    | or Transport Err.
 *     |                              |    | inform TU
 * Timer E  +-----------+             |    |
 * send req |Proceeding |             |    |
 *     +----|           |----+        |    |
 *     +--->+-----------+    |200-699 |    |
 *          1xx  |  |        |resp to TU   |
 *               |  |        V        V    |
 *               |  |     +-----------+    |
 *               |  +---->| Completed |    |
 *                        +-----------+    |
 *                          | Timer K      |
 *                          V              |
 *                    +-----------+        |
 *                    | Terminated|&lt;-------+
 *                    +-----------+
 * </pre>
 */
public final class NonInviteClientTransaction extends ClientTransaction {

    private long timerEInterval;

    NonInviteClientTransaction(TransactionLayer layer, String key, SipRequest request, TransportAddress destination,
                               ClientTransactionListener listener) {
        super(layer, key, request, destination, listener, TransactionState.TRYING);
    }

    @Override
    void start() {
        sendRequest();
        if (!reliable) {
            timerEInterval = timers().t1Millis();
            startTimer("E", timerEInterval, this::timerE);
        }
        startTimer("F", timers().timeoutMillis(), this::timeout);
    }

    private void timerE() {
        TransactionState s = state();
        if (s == TransactionState.TRYING || s == TransactionState.PROCEEDING) {
            sendRequest();
            timerEInterval = s == TransactionState.TRYING
                    ? Math.min(2 * timerEInterval, timers().t2Millis())
                    : timers().t2Millis();
            startTimer("E", timerEInterval, this::timerE);
        }
    }

    @Override
    void receiveResponse(SipResponse response) {
        TransactionState s = state();
        if (s != TransactionState.TRYING && s != TransactionState.PROCEEDING) {
            return; // Completed: absorb retransmitted final responses
        }
        if (response.isProvisional()) {
            transition(TransactionState.PROCEEDING);
            deliver(response);
            return;
        }
        cancelTimer("E");
        cancelTimer("F");
        transition(TransactionState.COMPLETED);
        startTimer("K", reliable ? 0 : timers().t4Millis(), () -> transition(TransactionState.TERMINATED));
        deliver(response);
    }
}
