package com.github.mikekwright.sip.softphone.sip.transaction;

import java.time.Duration;
import java.util.Objects;

/**
 * Base timer values of RFC 3261 §17.1.1.1 and Table 4. All transaction timers (A-M) are derived from these.
 * Tests may shrink them to exercise timeout paths quickly.
 *
 * @param t1 RTT estimate, default 500 ms
 * @param t2 maximum retransmit interval for non-INVITE requests and INVITE responses, default 4 s
 * @param t4 maximum duration a message remains in the network, default 5 s
 */
public record TimerSettings(Duration t1, Duration t2, Duration t4) {

    public static final TimerSettings DEFAULT = new TimerSettings(Duration.ofMillis(500), Duration.ofSeconds(4),
            Duration.ofSeconds(5));

    public TimerSettings {
        Objects.requireNonNull(t1, "t1");
        Objects.requireNonNull(t2, "t2");
        Objects.requireNonNull(t4, "t4");
    }

    public long t1Millis() {
        return t1.toMillis();
    }

    public long t2Millis() {
        return t2.toMillis();
    }

    public long t4Millis() {
        return t4.toMillis();
    }

    /** 64*T1: Timers B, F, H, J (unreliable), L and M. */
    public long timeoutMillis() {
        return 64 * t1.toMillis();
    }
}
