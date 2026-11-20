package com.github.mikekwright.sip.softphone.media.dtmf;

import com.github.mikekwright.sip.softphone.media.audio.ToneDetector;

import java.util.Optional;

/**
 * The DTMF frequency matrix (ITU-T Q.23) and a simple in-band detector built on {@link ToneDetector}.
 *
 * <pre>
 *           1209  1336  1477  1633 Hz
 *    697     1     2     3     A
 *    770     4     5     6     B
 *    852     7     8     9     C
 *    941     *     0     #     D
 * </pre>
 *
 * Stateless and thread-safe.
 */
public final class DtmfTones {

    /** Row (low group) frequencies in Hz. */
    private static final double[] LOW = {697, 770, 852, 941};
    /** Column (high group) frequencies in Hz. */
    private static final double[] HIGH = {1209, 1336, 1477, 1633};
    private static final String[] KEYPAD = {"123A", "456B", "789C", "*0#D"};

    private DtmfTones() {
    }

    /** The low group frequencies (697, 770, 852, 941 Hz). */
    public static double[] lowFrequencies() {
        return LOW.clone();
    }

    /** The high group frequencies (1209, 1336, 1477, 1633 Hz). */
    public static double[] highFrequencies() {
        return HIGH.clone();
    }

    /**
     * The {low, high} frequency pair of a digit.
     *
     * @throws IllegalArgumentException if {@code digit} is not a DTMF digit
     */
    public static double[] frequencies(char digit) {
        char d = Character.toUpperCase(digit);
        for (int row = 0; row < KEYPAD.length; row++) {
            int col = KEYPAD[row].indexOf(d);
            if (col >= 0) {
                return new double[]{LOW[row], HIGH[col]};
            }
        }
        throw new IllegalArgumentException("not a DTMF digit: '" + digit + "'");
    }

    /**
     * Detects a single DTMF digit in a block of samples (ideally 40 ms or more of one steady tone): the strongest
     * low and high group frequencies must each carry at least 25% of the block's energy.
     *
     * @return the digit, or empty if no DTMF tone dominates the block
     */
    public static Optional<Character> detect(short[] samples, int sampleRate) {
        double low = ToneDetector.dominantFrequency(samples, sampleRate, LOW);
        double high = ToneDetector.dominantFrequency(samples, sampleRate, HIGH);
        if (ToneDetector.relativePower(samples, sampleRate, low) < 0.25
                || ToneDetector.relativePower(samples, sampleRate, high) < 0.25) {
            return Optional.empty();
        }
        int row = indexOf(LOW, low);
        int col = indexOf(HIGH, high);
        return Optional.of(KEYPAD[row].charAt(col));
    }

    private static int indexOf(double[] values, double value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        throw new IllegalStateException();
    }
}
