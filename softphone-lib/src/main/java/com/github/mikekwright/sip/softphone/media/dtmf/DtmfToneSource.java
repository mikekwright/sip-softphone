package com.github.mikekwright.sip.softphone.media.dtmf;

import com.github.mikekwright.sip.softphone.media.audio.AudioSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * An {@link AudioSource} that plays DTMF digits in-band as dual tones (ITU-T Q.23 frequencies, see
 * {@link DtmfTones}), each followed by a silent gap; silence when idle. Used as the fallback when no RFC 4733
 * telephone-event payload type was negotiated, and handy as a test signal.
 *
 * <p>Thread-safety: all methods are synchronized; digit futures are completed outside the lock on the reading
 * thread.</p>
 */
public final class DtmfToneSource implements AudioSource {

    /** Default tone duration in milliseconds. */
    public static final int DEFAULT_TONE_MILLIS = 100;
    /** Default gap after each tone in milliseconds. */
    public static final int DEFAULT_GAP_MILLIS = 60;
    /** Default peak amplitude of the dual tone relative to full scale. */
    public static final double DEFAULT_AMPLITUDE = 0.5;

    private final int toneMillis;
    private final int gapMillis;
    private final double amplitude;
    private final Deque<Pending> queue = new ArrayDeque<>();

    private Pending current;
    /** Remaining time of the current tone and gap, in microseconds. */
    private double toneRemainingMicros;
    private double gapRemainingMicros;
    private double lowPhase;
    private double highPhase;

    /** A source with default timing and level. */
    public DtmfToneSource() {
        this(DEFAULT_TONE_MILLIS, DEFAULT_GAP_MILLIS, DEFAULT_AMPLITUDE);
    }

    /**
     * @param toneMillis duration of each tone
     * @param gapMillis  silence after each tone
     * @param amplitude  peak amplitude of the dual tone (0..1); each frequency gets half
     */
    public DtmfToneSource(int toneMillis, int gapMillis, double amplitude) {
        if (toneMillis <= 0 || gapMillis < 0) {
            throw new IllegalArgumentException("invalid tone/gap duration");
        }
        if (amplitude < 0 || amplitude > 1) {
            throw new IllegalArgumentException("amplitude must be within 0..1");
        }
        this.toneMillis = toneMillis;
        this.gapMillis = gapMillis;
        this.amplitude = amplitude;
    }

    /** Creates a source with default timing and queues {@code digits}. */
    public static DtmfToneSource of(String digits) {
        DtmfToneSource source = new DtmfToneSource();
        source.enqueue(digits);
        return source;
    }

    /**
     * Queues digits to play.
     *
     * @return a future completed after the gap following the last digit
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
                queue.add(new Pending(digits.charAt(i), i == digits.length() - 1 ? completion : null));
            }
        }
        return completion;
    }

    /** Whether a digit is playing or queued. */
    public synchronized boolean isActive() {
        return current != null || !queue.isEmpty();
    }

    /** Stops playing; pending futures are cancelled. */
    public void cancel() {
        List<CompletableFuture<Void>> cancelled = new ArrayList<>();
        synchronized (this) {
            if (current != null && current.completion() != null) {
                cancelled.add(current.completion());
            }
            queue.forEach(p -> {
                if (p.completion() != null) {
                    cancelled.add(p.completion());
                }
            });
            queue.clear();
            current = null;
        }
        cancelled.forEach(f -> f.completeExceptionally(new CancellationException("DTMF tones cancelled")));
    }

    @Override
    public void read(short[] buffer, int sampleRate) {
        List<CompletableFuture<Void>> finished = new ArrayList<>(1);
        synchronized (this) {
            double microsPerSample = 1_000_000.0 / sampleRate;
            for (int i = 0; i < buffer.length; i++) {
                if (current == null) {
                    current = queue.poll();
                    if (current == null) {
                        buffer[i] = 0;
                        continue;
                    }
                    toneRemainingMicros = toneMillis * 1000.0;
                    gapRemainingMicros = gapMillis * 1000.0;
                    lowPhase = 0;
                    highPhase = 0;
                }
                if (toneRemainingMicros > 0) {
                    double[] f = DtmfTones.frequencies(current.digit());
                    double sample = Math.sin(2 * Math.PI * lowPhase) + Math.sin(2 * Math.PI * highPhase);
                    buffer[i] = (short) Math.round(sample * amplitude / 2 * Short.MAX_VALUE);
                    lowPhase = (lowPhase + f[0] / sampleRate) % 1.0;
                    highPhase = (highPhase + f[1] / sampleRate) % 1.0;
                    toneRemainingMicros -= microsPerSample;
                } else {
                    buffer[i] = 0;
                    gapRemainingMicros -= microsPerSample;
                }
                if (toneRemainingMicros <= 0 && gapRemainingMicros <= 0) {
                    if (current.completion() != null) {
                        finished.add(current.completion());
                    }
                    current = null;
                }
            }
        }
        finished.forEach(f -> f.complete(null));
    }

    private record Pending(char digit, CompletableFuture<Void> completion) {
    }
}
