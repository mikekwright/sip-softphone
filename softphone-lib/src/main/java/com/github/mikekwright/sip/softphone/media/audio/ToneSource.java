package com.github.mikekwright.sip.softphone.media.audio;

import java.util.Arrays;

/**
 * An {@link AudioSource} generating a continuous sine tone, or the sum of several tones (e.g. the dual tones of
 * DTMF, ITU-T Q.23, or call progress tones such as 350+440 Hz dial tone).
 *
 * <p>Phase is kept between reads (and across sample rate changes), so consecutive frames join without clicks.
 * The amplitude is the peak of the summed signal relative to full scale; with several frequencies each component
 * gets {@code amplitude / n}, so the sum never clips.</p>
 *
 * <p>Thread-safety: {@link #read} is synchronized; instances may be shared but each reader advances the
 * common phase.</p>
 */
public final class ToneSource implements AudioSource {

    private final double[] frequencies;
    private final double amplitude;
    private final double[] phases;

    /**
     * A single sine tone.
     *
     * @param frequencyHz frequency in Hz (must be positive)
     * @param amplitude   peak amplitude relative to full scale, 0..1
     */
    public ToneSource(double frequencyHz, double amplitude) {
        this(amplitude, new double[]{frequencyHz});
    }

    private ToneSource(double amplitude, double[] frequencies) {
        if (amplitude < 0 || amplitude > 1) {
            throw new IllegalArgumentException("amplitude must be within 0..1: " + amplitude);
        }
        if (frequencies.length == 0) {
            throw new IllegalArgumentException("at least one frequency required");
        }
        for (double f : frequencies) {
            if (!(f > 0)) {
                throw new IllegalArgumentException("frequency must be positive: " + f);
            }
        }
        this.amplitude = amplitude;
        this.frequencies = frequencies.clone();
        this.phases = new double[frequencies.length];
    }

    /**
     * A mix of tones of equal level.
     *
     * @param amplitude   peak amplitude of the sum relative to full scale, 0..1
     * @param frequencies component frequencies in Hz
     */
    public static ToneSource mixed(double amplitude, double... frequencies) {
        return new ToneSource(amplitude, frequencies);
    }

    /** The component frequencies in Hz. */
    public double[] frequencies() {
        return frequencies.clone();
    }

    /** The peak amplitude relative to full scale. */
    public double amplitude() {
        return amplitude;
    }

    @Override
    public synchronized void read(short[] buffer, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sample rate must be positive");
        }
        double componentAmplitude = amplitude / frequencies.length * Short.MAX_VALUE;
        for (int i = 0; i < buffer.length; i++) {
            double sample = 0;
            for (int f = 0; f < frequencies.length; f++) {
                sample += Math.sin(2 * Math.PI * phases[f]);
                phases[f] += frequencies[f] / sampleRate;
                if (phases[f] >= 1.0) {
                    phases[f] -= Math.floor(phases[f]);
                }
            }
            buffer[i] = (short) Math.round(sample * componentAmplitude);
        }
    }

    @Override
    public String toString() {
        return "ToneSource" + Arrays.toString(frequencies) + "@" + amplitude;
    }
}
