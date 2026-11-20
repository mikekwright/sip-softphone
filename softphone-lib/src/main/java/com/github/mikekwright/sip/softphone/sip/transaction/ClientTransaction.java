package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;

/**
 * Base of the client transaction state machines (RFC 3261 §17.1).
 */
public abstract sealed class ClientTransaction extends Transaction
        permits InviteClientTransaction, NonInviteClientTransaction {

    protected final TransportAddress destination;
    protected final ClientTransactionListener listener;
    private SipResponse lastResponse;

    protected ClientTransaction(TransactionLayer layer, String key, SipRequest request, TransportAddress destination,
                                ClientTransactionListener listener, TransactionState initial) {
        super(layer, key, request, destination.transport().isReliable(), initial);
        this.destination = destination;
        this.listener = listener;
    }

    /** Where the request is sent. */
    public TransportAddress destination() {
        return destination;
    }

    /** The most recent response delivered to the TU, or {@code null}. */
    public SipResponse lastResponse() {
        return lastResponse;
    }

    /** Sends the request and starts the timers. */
    abstract void start();

    /** Feeds a response matched to this transaction. */
    abstract void receiveResponse(SipResponse response);

    protected void sendRequest() {
        layer.transport().send(request, destination).whenComplete((v, err) -> {
            if (err != null) {
                layer.executor().execute(() -> transportError(err));
            }
        });
    }

    protected void deliver(SipResponse response) {
        lastResponse = response;
        listener.onResponse(this, response);
    }

    protected void transportError(Throwable err) {
        if (!isTerminated()) {
            listener.onTransportError(this, err);
            transition(TransactionState.TERMINATED);
        }
    }

    protected void timeout() {
        listener.onTimeout(this);
        transition(TransactionState.TERMINATED);
    }

    @Override
    protected void onTerminated() {
        listener.onTerminated(this);
    }
}
