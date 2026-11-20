package com.github.mikekwright.sip.softphone.media.dtmf;

import com.github.mikekwright.sip.softphone.rtp.RtpPacket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Turns a stream of RFC 4733 telephone-event packets into DTMF digit notifications, reporting each event exactly
 * once.
 *
 * <p>Events are identified by their RTP timestamp (RFC 4733 §2.5.2.1: all packets of one event share the
 * timestamp of its start). A digit is reported:</p>
 * <ul>
 *   <li>when the first packet with the E bit arrives for the event (the redundant end packets of §2.5.1.4 and any
 *       later retransmissions are ignored), or</li>
 *   <li>when a packet of a <em>new</em> event arrives while the previous one never ended (its end packets were
 *       lost), with the longest duration seen, or</li>
 *   <li>on {@link #flush()} for an event that is still open.</li>
 * </ul>
 * Late packets of recently completed events are recognised and ignored. The marker bit is not relied upon since
 * the first packet may be lost. Non-DTMF events (codes above 15) are tracked but not reported.
 *
 * <p>Thread-safety: not thread-safe; confine an instance to one thread (the audio stream uses its event loop).</p>
 */
public final class DtmfReceiver {

    private static final int RECENT_EVENTS = 16;

    private final int clockRate;
    private final DtmfListener listener;
    private final Deque<Long> recentTimestamps = new ArrayDeque<>();

    private boolean active;
    private long currentTimestamp;
    private int currentEvent;
    private int currentDuration;
    private boolean currentReported;

    /**
     * @param clockRate RTP clock rate of the telephone-event format, for converting durations to milliseconds
     * @param listener  receives each digit once
     */
    public DtmfReceiver(int clockRate, DtmfListener listener) {
        if (clockRate <= 0) {
            throw new IllegalArgumentException("clock rate must be positive");
        }
        this.clockRate = clockRate;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Processes a telephone-event RTP packet. */
    public void receive(RtpPacket packet) {
        receive(packet.payload(), packet.timestamp(), packet.marker());
    }

    /**
     * Processes a telephone-event payload.
     *
     * @param payload   RTP payload (malformed payloads are ignored)
     * @param timestamp RTP timestamp of the packet
     * @param marker    RTP marker bit (informational only)
     */
    public void receive(byte[] payload, long timestamp, boolean marker) {
        TelephoneEvent event;
        try {
            event = TelephoneEvent.decode(payload);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (active && timestamp == currentTimestamp) {
            currentDuration = Math.max(currentDuration, event.duration());
            if (event.end() && !currentReported) {
                report();
            }
            return;
        }
        if (recentTimestamps.contains(timestamp)) {
            return; // retransmission of an event we already finished with
        }
        if (active) {
            if (!currentReported) {
                report(); // the previous event's end packets were all lost
            }
            remember(currentTimestamp);
        }
        active = true;
        currentTimestamp = timestamp;
        currentEvent = event.event();
        currentDuration = event.duration();
        currentReported = false;
        if (event.end()) {
            report();
        }
    }

    /** Reports the open event, if any and not yet reported (e.g. when the stream stops). */
    public void flush() {
        if (active && !currentReported) {
            report();
        }
    }

    /** Forgets all state without reporting. */
    public void reset() {
        active = false;
        currentReported = false;
        recentTimestamps.clear();
    }

    private void report() {
        currentReported = true;
        if (currentEvent < 16) {
            listener.onDigit(TelephoneEvent.digit(currentEvent), (int) (currentDuration * 1000L / clockRate));
        }
    }

    private void remember(long timestamp) {
        if (recentTimestamps.size() == RECENT_EVENTS) {
            recentTimestamps.removeFirst();
        }
        recentTimestamps.addLast(timestamp);
    }
}
