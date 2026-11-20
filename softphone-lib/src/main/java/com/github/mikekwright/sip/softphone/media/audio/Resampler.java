package com.github.mikekwright.sip.softphone.media.audio;

/**
 * Sample rate conversion by linear interpolation. Cheap and adequate for telephony-band speech and test tones;
 * it performs no anti-alias filtering, so downsampling content above the new Nyquist frequency aliases.
 * Stateless and thread-safe.
 */
public final class Resampler {

    private Resampler() {
    }

    /**
     * Converts {@code input} from {@code fromRate} to {@code toRate}. The output has
     * {@code round(input.length * toRate / fromRate)} samples; returns {@code input} itself when the rates match.
     */
    public static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0) {
            throw new IllegalArgumentException("sample rates must be positive");
        }
        if (fromRate == toRate || input.length == 0) {
            return input;
        }
        int outLength = (int) Math.round((double) input.length * toRate / fromRate);
        short[] out = new short[outLength];
        double step = (double) fromRate / toRate;
        int last = input.length - 1;
        for (int i = 0; i < outLength; i++) {
            double position = i * step;
            int index = (int) position;
            if (index >= last) {
                out[i] = input[last];
                continue;
            }
            double fraction = position - index;
            out[i] = (short) Math.round(input[index] * (1 - fraction) + input[index + 1] * fraction);
        }
        return out;
    }
}
