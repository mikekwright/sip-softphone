package com.github.mikekwright.sip.softphone.sip.transaction;

import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Common state of client and server transactions. All methods run on the SIP core executor, so transactions need
 * no locking. Timers are scheduled on the same executor.
 */
public abstract class Transaction {

    private static final Logger log = LoggerFactory.getLogger(Transaction.class);

    protected final TransactionLayer layer;
    protected final String key;
    protected final SipRequest request;
    protected final boolean reliable;
    private final Map<String, ScheduledFuture<?>> timers = new HashMap<>();
    private TransactionState state;

    protected Transaction(TransactionLayer layer, String key, SipRequest request, boolean reliable,
                          TransactionState initial) {
        this.layer = layer;
        this.key = key;
        this.request = request;
        this.reliable = reliable;
        this.state = initial;
    }

    /** Transaction identifier as used for matching (RFC 3261 §17.1.3, §17.2.3). */
    public String key() {
        return key;
    }

    /** The request that created the transaction. Do not modify. */
    public SipRequest request() {
        return request;
    }

    public SipMethod method() {
        return request.method();
    }

    public TransactionState state() {
        return state;
    }

    public boolean isTerminated() {
        return state == TransactionState.TERMINATED;
    }

    /** True when the transport is reliable (TCP/TLS): retransmission timers are not used. */
    public boolean isReliable() {
        return reliable;
    }

    protected void transition(TransactionState next) {
        if (state == next) {
            return;
        }
        log.debug("{} {} {} -> {}", getClass().getSimpleName(), key, state, next);
        state = next;
        if (next == TransactionState.TERMINATED) {
            timers.values().forEach(t -> t.cancel(false));
            timers.clear();
            layer.remove(this);
            onTerminated();
        }
    }

    /** Called once when the transaction reaches {@link TransactionState#TERMINATED}. */
    protected void onTerminated() {
    }

    protected void startTimer(String name, long delayMillis, Runnable action) {
        cancelTimer(name);
        timers.put(name, layer.executor().schedule(() -> {
            timers.remove(name);
            if (!isTerminated()) {
                action.run();
            }
        }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS));
    }

    protected void cancelTimer(String name) {
        ScheduledFuture<?> t = timers.remove(name);
        if (t != null) {
            t.cancel(false);
        }
    }

    protected TimerSettings timers() {
        return layer.timerSettings();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + key + ", " + state + "]";
    }
}
