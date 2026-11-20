package com.github.mikekwright.sip.softphone.media.dtmf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Generates the RFC 4733 telephone-event packet sequence for DTMF digits, driven tick by tick by an audio
 * stream's packetization clock rather than by its own timer.
 *
 * <p>For every digit the sequence follows RFC 4733 §2.5.1:</p>
 * <ul>
 *   <li>all packets of an event carry the same RTP timestamp: the stream's timestamp at the tick on which the
 *       event starts (§2.5.1.1);</li>
 *   <li>the first packet has the marker bit set (§2.5.1.1);</li>
 *   <li>one update per tick (ptime) with the duration so far (§2.5.1.2);</li>
 *   <li>the final packet carries the E bit and the total duration and is sent three times in total
 *       (§2.5.1.4) on consecutive ticks;</li>
 *   <li>after an event, {@code gap} ticks pass before the next digit starts, during which the stream sends
 *       ordinary audio.</li>
 * </ul>
 * With the defaults (100 ms tone, 20 ms ptime) a digit produces 7 packets: durations 160, 320, 480, 640 and
 * three end packets with duration 800 at 8 kHz.
 *
 * <p>Usage: once per ptime the stream calls {@link #next(long)} with its current RTP timestamp; a non-null
 * result must be sent <em>instead of</em> the audio frame of that tick, using the stream's next sequence number
 * and the returned packet's timestamp. A {@code null} result means audio may be sent.</p>
 *
 * <p>Long events that would overflow the 16-bit duration field (RFC 4733 §2.5.1.3 segmentation) are not
 * supported; the constructor rejects tone durations that would need them.</p>
 *
 * <p>Thread-safety: all methods may be called from any thread; futures are completed outside the internal lock
 * on the thread calling {@link #next(long)}.</p>
 */
public final class DtmfSender {

    /** Default tone duration in milliseconds. */
    public static final int DEFAULT_TONE_MILLIS = 100;
    /** Default minimum gap between digits in milliseconds (rounded up to whole ticks). */
    public static final int DEFAULT_GAP_MILLIS = 50;
    /** Default volume: -10 dBm0. */
    public static final int DEFAULT_VOLUME = 10;
    /** Number of times the end packet is sent (RFC 4733 §2.5.1.4). */
    public static final int END_PACKET_COUNT = 3;

    private final int samplesPerTick;
    private final int toneTicks;
    private final int gapTicks;
    private final int volume;
    private final int clockRate;

    private final Deque<Pending> queue = new ArrayDeque<>();
    private Pending current;
    private long eventTimestamp;
    private int tick;
    private int gapRemaining;

    /** A sender with default tone duration, gap and volume. */
    public DtmfSender(int clockRate, int ptimeMillis) {
        this(clockRate, ptimeMillis, DEFAULT_TONE_MILLIS, DEFAULT_GAP_MILLIS, DEFAULT_VOLUME);
    }

    /**
     * @param clockRate   RTP clock rate of the telephone-event format (equal to the audio clock rate)
     * @param ptimeMillis tick interval at which {@link #next(long)} is called
     * @param toneMillis  tone duration, rounded up to whole ticks (at least one)
     * @param gapMillis   minimum pause between digits, rounded up to whole ticks
     * @param volume      0..63 (-dBm0)
     */
    public DtmfSender(int clockRate, int ptimeMillis, int toneMillis, int gapMillis, int volume) {
        if (clockRate <= 0 || ptimeMillis <= 0 || toneMillis <= 0 || gapMillis < 0) {
            throw new IllegalArgumentException("clock rate, ptime and tone duration must be positive");
        }
        if (volume < 0 || volume > 63) {
            throw new IllegalArgumentException("volume out of range: " + volume);
        }
        this.clockRate = clockRate;
        this.samplesPerTick = clockRate * ptimeMillis / 1000;
        this.toneTicks = Math.max(1, (toneMillis + ptimeMillis - 1) / ptimeMillis);
        this.gapTicks = (gapMillis + ptimeMillis - 1) / ptimeMillis;
        this.volume = volume;
        if ((long) toneTicks * samplesPerTick > 0xFFFF) {
            throw new IllegalArgumentException("tone duration " + toneMillis + " ms exceeds the 16-bit duration field");
        }
    }

    /** RTP clock rate of the generated events. */
    public int clockRate() {
        return clockRate;
    }

    /**
     * Queues digits for sending.
     *
     * @param digits characters from {@code 0-9 * # A-D}
     * @return a future completed when the last end packet of the last digit has been produced, or cancelled by
     *         {@link #cancel()}
     * @throws IllegalArgumentException if {@code digits} contains an invalid character
     */
    public CompletableFuture<Void> enqueue(String digits) {
        TelephoneEvent.validateDigits(digits);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (digits.isEmpty()) {
            completion.complete(null);
            return completion;
        }
        synchronized (this) {
            for (int i = 0; i < digits.length(); i++) {
                boolean last = i == digits.length() - 1;
                queue.add(new Pending(TelephoneEvent.eventCode(digits.charAt(i)), last ? completion : null));
            }
        }
        return completion;
    }

    /** Whether an event is being sent (audio must be suppressed until {@link #next} returns null). */
    public synchronized boolean isEventInProgress() {
        return current != null;
    }

    /** Whether nothing is in progress, queued or in an inter-digit gap. */
    public synchronized boolean isIdle() {
        return current == null && queue.isEmpty() && gapRemaining == 0;
    }

    /**
     * Advances by one tick.
     *
     * @param currentTimestamp the stream's RTP timestamp for this tick (used as event timestamp when a new event
     *                         starts)
     * @return the telephone-event packet to send in place of audio, or {@code null} to send audio
     */
    public Packet next(long currentTimestamp) {
        CompletableFuture<Void> finished = null;
        Packet packet;
        synchronized (this) {
            if (current == null) {
                if (gapRemaining > 0) {
                    gapRemaining--;
                    return null;
                }
                current = queue.poll();
                if (current == null) {
                    return null;
                }
                eventTimestamp = currentTimestamp;
                tick = 0;
            }
            tick++;
            boolean end = tick >= toneTicks;
            int duration = Math.min(tick, toneTicks) * samplesPerTick;
            packet = new Packet(new TelephoneEvent(current.eventCode(), end, volume, duration), eventTimestamp,
                    tick == 1);
            if (tick >= toneTicks + END_PACKET_COUNT - 1) {
                finished = current.completion();
                current = null;
                gapRemaining = gapTicks;
            }
        }
        if (finished != null) {
            finished.complete(null);
        }
        return packet;
    }

    /**
     * Abandons the event in progress and all queued digits; their futures are cancelled. An event in progress
     * is cut short without end packets (the receiver will report it when the next event starts or on flush).
     */
    public void cancel() {
        List<CompletableFuture<Void>> cancelled = new ArrayList<>();
        synchronized (this) {
            if (current != null && current.completion() != null) {
                cancelled.add(current.completion());
            }
            for (Pending pending : queue) {
                if (pending.completion() != null) {
                    cancelled.add(pending.completion());
                }
            }
            current = null;
            queue.clear();
            gapRemaining = 0;
        }
        cancelled.forEach(f -> f.completeExceptionally(new CancellationException("DTMF sending cancelled")));
    }

    /**
     * One telephone-event packet to send.
     *
     * @param event     the event payload
     * @param timestamp RTP timestamp (the event start)
     * @param marker    RTP marker bit (set on the first packet of an event)
     */
    public record Packet(TelephoneEvent event, long timestamp, boolean marker) {
        /** The encoded RTP payload. */
        public byte[] payload() {
            return event.encode();
        }
    }

    private record Pending(int eventCode, CompletableFuture<Void> completion) {
    }
}
