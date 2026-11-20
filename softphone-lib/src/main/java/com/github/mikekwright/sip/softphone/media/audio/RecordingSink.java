package com.github.mikekwright.sip.softphone.media.audio;

import java.time.Duration;
import java.util.Arrays;

/**
 * An {@link AudioSink} that records everything written to it in memory, for tests and simple capture use cases.
 *
 * <p>The first write fixes the recording's sample rate; later writes at a different rate are resampled to it.
 * Recording stops silently once {@code maxSamples} samples are held.</p>
 *
 * <p>Thread-safety: all methods are synchronized; {@link #awaitSamples} lets a test thread wait for audio
 * delivered by the network event loop.</p>
 */
public final class RecordingSink implements AudioSink {

    private final int maxSamples;
    private short[] buffer = new short[8_000];
    private int size;
    private int sampleRate;

    /** A recorder without size limit (bounded only by memory and {@link Integer#MAX_VALUE}). */
    public RecordingSink() {
        this(Integer.MAX_VALUE - 8);
    }

    /** A recorder keeping at most {@code maxSamples} samples. */
    public RecordingSink(int maxSamples) {
        if (maxSamples <= 0) {
            throw new IllegalArgumentException("maxSamples must be positive");
        }
        this.maxSamples = maxSamples;
    }

    @Override
    public synchronized void write(short[] samples, int sampleRate) {
        if (this.sampleRate == 0) {
            this.sampleRate = sampleRate;
        }
        short[] data = Resampler.resample(samples, sampleRate, this.sampleRate);
        int count = Math.min(data.length, maxSamples - size);
        if (count <= 0) {
            return;
        }
        if (size + count > buffer.length) {
            int capacity = (int) Math.min(maxSamples, Math.max((long) buffer.length * 2, (long) size + count));
            buffer = Arrays.copyOf(buffer, capacity);
        }
        System.arraycopy(data, 0, buffer, size, count);
        size += count;
        notifyAll();
    }

    /** A copy of all recorded samples. */
    public synchronized short[] samples() {
        return Arrays.copyOf(buffer, size);
    }

    /** A copy of the last {@code count} recorded samples (fewer if not that many were recorded). */
    public synchronized short[] lastSamples(int count) {
        int n = Math.min(count, size);
        return Arrays.copyOfRange(buffer, size - n, size);
    }

    /** Number of samples recorded. */
    public synchronized int sampleCount() {
        return size;
    }

    /** Sample rate of the recording, or 0 if nothing was written yet. */
    public synchronized int sampleRate() {
        return sampleRate;
    }

    /** Recorded duration. */
    public synchronized Duration duration() {
        return sampleRate == 0 ? Duration.ZERO : Duration.ofMillis(size * 1000L / sampleRate);
    }

    /** Root mean square level of the recording relative to full scale (0..1). */
    public synchronized double rms() {
        return Math.sqrt(ToneDetector.meanSquare(Arrays.copyOf(buffer, size)));
    }

    /** Discards the recording; the next write fixes the sample rate anew. */
    public synchronized void clear() {
        size = 0;
        sampleRate = 0;
        buffer = new short[8_000];
    }

    /**
     * Waits until at least {@code count} samples are recorded.
     *
     * @return whether the count was reached before the timeout
     */
    public synchronized boolean awaitSamples(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (size < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            wait(Math.max(1, remaining / 1_000_000L));
        }
        return true;
    }
}
