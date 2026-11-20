package com.github.mikekwright.sip.softphone.media.audio;

/**
 * Single-frequency energy measurement with the Goertzel algorithm, primarily for automated tests that need to
 * assert which tone arrived (e.g. "the far end hears 1 kHz") and for in-band DTMF detection.
 *
 * <p>Powers are normalised so that a full-scale sine at exactly the analysed frequency yields 1.0 and a sine of
 * peak amplitude {@code a} (relative to full scale) yields about {@code a²}. Windowing is rectangular, so for
 * meaningful results analyse at least a few dozen milliseconds of signal.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class ToneDetector {

    private ToneDetector() {
    }

    /**
     * Normalised power of {@code frequency} in {@code samples}: the squared amplitude estimate
     * {@code (2·|X(f)|/N)²} of the Goertzel DFT bin, with samples scaled to [-1, 1].
     */
    public static double power(short[] samples, int sampleRate, double frequency) {
        if (samples.length == 0) {
            return 0;
        }
        double coefficient = 2 * Math.cos(2 * Math.PI * frequency / sampleRate);
        double s1 = 0;
        double s2 = 0;
        for (short sample : samples) {
            double s0 = sample / 32768.0 + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double magnitudeSquared = s1 * s1 + s2 * s2 - coefficient * s1 * s2;
        double amplitude = 2 * Math.sqrt(Math.max(0, magnitudeSquared)) / samples.length;
        return amplitude * amplitude;
    }

    /** Mean square of the samples scaled to [-1, 1] (the signal's total power). */
    public static double meanSquare(short[] samples) {
        if (samples.length == 0) {
            return 0;
        }
        double sum = 0;
        for (short sample : samples) {
            double v = sample / 32768.0;
            sum += v * v;
        }
        return sum / samples.length;
    }

    /**
     * Fraction (0..~1) of the signal's total power concentrated at {@code frequency}: about 1.0 for a pure tone
     * at that frequency, 0.5 for each component of an equal-level dual tone, near 0 for unrelated content.
     */
    public static double relativePower(short[] samples, int sampleRate, double frequency) {
        double total = meanSquare(samples);
        if (total == 0) {
            return 0;
        }
        // A sine of amplitude a has mean square a²/2, and power() returns a².
        return power(samples, sampleRate, frequency) / (2 * total);
    }

    /**
     * Returns the candidate frequency with the highest {@link #power}.
     *
     * @throws IllegalArgumentException if no candidates are given
     */
    public static double dominantFrequency(short[] samples, int sampleRate, double... candidates) {
        if (candidates.length == 0) {
            throw new IllegalArgumentException("no candidate frequencies");
        }
        double best = candidates[0];
        double bestPower = -1;
        for (double candidate : candidates) {
            double p = power(samples, sampleRate, candidate);
            if (p > bestPower) {
                bestPower = p;
                best = candidate;
            }
        }
        return best;
    }
}
