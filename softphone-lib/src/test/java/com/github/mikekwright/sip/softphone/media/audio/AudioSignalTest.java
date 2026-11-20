package com.github.mikekwright.sip.softphone.media.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Tests for sources, sinks and signal helpers that do not touch the file system. */
class AudioSignalTest {

    @Test
    void toneSourceProducesRequestedFrequencyAndAmplitude() {
        short[] samples = new short[8000];
        new ToneSource(1000, 0.5).read(samples, 8000);
        assertThat(ToneDetector.relativePower(samples, 8000, 1000)).isGreaterThan(0.99);
        assertThat(ToneDetector.power(samples, 8000, 1000)).isCloseTo(0.25, within(0.01));
        assertThat(ToneDetector.relativePower(samples, 8000, 1500)).isLessThan(0.01);
        int peak = 0;
        for (short s : samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertThat(peak).isBetween(16_000, 16_384);
    }

    @Test
    void toneSourceKeepsPhaseAcrossReads() {
        ToneSource chunked = new ToneSource(440, 0.8);
        ToneSource whole = new ToneSource(440, 0.8);
        short[] a = new short[80];
        short[] b = new short[80];
        chunked.read(a, 8000);
        chunked.read(b, 8000);
        short[] expected = new short[160];
        whole.read(expected, 8000);
        short[] joined = Arrays.copyOf(a, 160);
        System.arraycopy(b, 0, joined, 80, 80);
        assertThat(joined).containsExactly(expected);
    }

    @Test
    void mixedToneSourceSplitsEnergy() {
        short[] samples = new short[8000];
        ToneSource.mixed(0.6, 697, 1209).read(samples, 8000);
        assertThat(ToneDetector.relativePower(samples, 8000, 697)).isCloseTo(0.5, within(0.05));
        assertThat(ToneDetector.relativePower(samples, 8000, 1209)).isCloseTo(0.5, within(0.05));
        assertThat(ToneDetector.dominantFrequency(samples, 8000, 500, 697, 941)).isEqualTo(697);
        assertThatThrownBy(() -> new ToneSource(1000, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void silenceAndDetectorEdgeCases() {
        short[] samples = {1, 2, 3};
        SilenceSource.INSTANCE.read(samples, 8000);
        assertThat(samples).containsOnly((short) 0);
        assertThat(ToneDetector.power(samples, 8000, 1000)).isZero();
        assertThat(ToneDetector.relativePower(samples, 8000, 1000)).isZero();
        assertThat(ToneDetector.power(new short[0], 8000, 1000)).isZero();
    }

    @Test
    void resamplerPreservesFrequency() {
        short[] tone = new short[1600];
        new ToneSource(1000, 0.5).read(tone, 8000);
        short[] up = Resampler.resample(tone, 8000, 16_000);
        assertThat(up).hasSize(3200);
        assertThat(ToneDetector.relativePower(up, 16_000, 1000)).isGreaterThan(0.95);
        short[] down = Resampler.resample(up, 16_000, 8000);
        assertThat(down).hasSize(1600);
        assertThat(ToneDetector.relativePower(down, 8000, 1000)).isGreaterThan(0.95);
        assertThat(Resampler.resample(tone, 8000, 8000)).isSameAs(tone);
        assertThat(Resampler.resample(new short[]{0, 100}, 8000, 16_000)).containsExactly(0, 50, 100, 100);
    }

    @Test
    void recordingSinkRecordsResamplesAndClears() throws Exception {
        RecordingSink sink = new RecordingSink();
        assertThat(sink.sampleRate()).isZero();
        sink.write(new short[]{1, 2, 3}, 8000);
        sink.write(new short[]{4, 4, 4, 4}, 16_000); // resampled to 2 samples at 8 kHz
        assertThat(sink.sampleRate()).isEqualTo(8000);
        assertThat(sink.samples()).containsExactly(1, 2, 3, 4, 4);
        assertThat(sink.lastSamples(2)).containsExactly(4, 4);
        assertThat(sink.sampleCount()).isEqualTo(5);
        assertThat(sink.awaitSamples(5, Duration.ZERO)).isTrue();
        assertThat(sink.awaitSamples(6, Duration.ofMillis(10))).isFalse();

        sink.clear();
        assertThat(sink.sampleCount()).isZero();
        short[] fullScale = new short[100];
        Arrays.fill(fullScale, Short.MAX_VALUE);
        sink.write(fullScale, 16_000);
        assertThat(sink.sampleRate()).isEqualTo(16_000);
        assertThat(sink.rms()).isCloseTo(1.0, within(0.001));
        assertThat(sink.duration()).isEqualTo(Duration.ofMillis(6)); // 100 samples at 16 kHz, truncated
    }

    @Test
    void recordingSinkHonoursLimitAndWakesWaiters() throws Exception {
        RecordingSink sink = new RecordingSink(10);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.write(new short[16], 8000);
        });
        writer.start();
        assertThat(sink.awaitSamples(10, Duration.ofSeconds(5))).isTrue();
        writer.join();
        assertThat(sink.sampleCount()).isEqualTo(10);
    }
}
