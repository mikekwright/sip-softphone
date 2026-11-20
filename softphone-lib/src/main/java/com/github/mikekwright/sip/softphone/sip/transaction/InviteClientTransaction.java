package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.CSeq;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;

/**
 * The INVITE client transaction (RFC 3261 §17.1.1, Figure 5, as updated by RFC 6026 Figure 3).
 *
 * <pre>
 *                     |INVITE from TU
 *   Timer A fires     |INVITE sent           Timer B fires
 *   Reset A,          V                      or Transport Err.
 *   INVITE sent +-----------+                inform TU
 *     +---------|  Calling  |----------------------------+
 *     |         +-----------+ 2xx                        |
 *     +-------->     | |      2xx to TU                  |
 *                    | |1xx                              |
 *   300-699  +-------+ +----------+                      |
 *   ACK sent |                    V                      |
 *   resp. to TU          +------------+  2xx to TU       |
 *            |   1xx     | Proceeding |------------+     |
 *            |  to TU +--|            |            |     |
 *            |        +->+------------+            V     |
 *            |                | 300-699     +-----------+ 2xx to TU
 *            |                | ACK sent    |  Accepted |&lt;-+
 *            |                V resp to TU  +-----------+
 *            +---------->+-----------+            | Timer M
 *          300-699       | Completed |            |
 *          ACK sent      +-----------+            |
 *                          | Timer D              |
 *                          V                      V
 *                    +----------------------------------+
 *                    |            Terminated            |
 *                    +----------------------------------+
 * </pre>
 */
public final class InviteClientTransaction extends ClientTransaction {

    private long timerAInterval;
    private SipRequest ack;

    InviteClientTransaction(TransactionLayer layer, String key, SipRequest request, TransportAddress destination,
                            ClientTransactionListener listener) {
        super(layer, key, request, destination, listener, TransactionState.CALLING);
    }

    @Override
    void start() {
        sendRequest();
        if (!reliable) {
            timerAInterval = timers().t1Millis();
            startTimer("A", timerAInterval, this::timerA);
        }
        startTimer("B", timers().timeoutMillis(), this::timeout);
    }

    private void timerA() {
        if (state() == TransactionState.CALLING) {
            sendRequest();
            timerAInterval *= 2;
            startTimer("A", timerAInterval, this::timerA);
        }
    }

    @Override
    void receiveResponse(SipResponse response) {
        int code = response.statusCode();
        switch (state()) {
            case CALLING, PROCEEDING -> {
                if (code < 200) {
                    cancelTimer("A");
                    cancelTimer("B");
                    transition(TransactionState.PROCEEDING);
                    deliver(response);
                } else if (code < 300) {
                    // RFC 6026: the TU acknowledges 2xx itself; stay around to pass on 2xx retransmissions
                    cancelTimer("A");
                    cancelTimer("B");
                    transition(TransactionState.ACCEPTED);
                    startTimer("M", timers().timeoutMillis(), () -> transition(TransactionState.TERMINATED));
                    deliver(response);
                } else {
                    cancelTimer("A");
                    cancelTimer("B");
                    sendAck(response);
                    transition(TransactionState.COMPLETED);
                    startTimer("D", reliable ? 0 : timers().timeoutMillis(),
                            () -> transition(TransactionState.TERMINATED));
                    deliver(response);
                }
            }
            case ACCEPTED -> {
                if (code >= 200 && code < 300) {
                    deliver(response); // retransmission of 2xx, the TU re-sends its ACK
                }
            }
            case COMPLETED -> {
                if (code >= 300) {
                    layer.transport().send(ack, destination); // retransmission: re-send ACK, absorb
                }
            }
            default -> {
            }
        }
    }

    /** Builds and sends the ACK for a non-2xx final response (RFC 3261 §17.1.1.3). */
    private void sendAck(SipResponse response) {
        SipRequest a = new SipRequest(SipMethod.ACK, request.requestUri());
        a.headers().add(HeaderNames.VIA, request.headers().get(HeaderNames.VIA));
        a.headers().copyFrom(request.headers(), HeaderNames.ROUTE);
        a.headers().add(HeaderNames.MAX_FORWARDS, "70");
        a.headers().add(HeaderNames.FROM, request.header(HeaderNames.FROM));
        a.headers().add(HeaderNames.TO, response.header(HeaderNames.TO));
        a.headers().add(HeaderNames.CALL_ID, request.callId());
        a.headers().add(HeaderNames.CSEQ, new CSeq(request.cseq().number(), SipMethod.ACK).toString());
        a.setRawBody(new byte[0]);
        this.ack = a;
        layer.transport().send(a, destination);
    }
}
