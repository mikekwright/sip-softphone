package com.github.mikekwright.sip.softphone.media.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WavFileTest {

    @TempDir
    Path dir;

    /** Builds a canonical PCM WAV file. */
    private static byte[] wav(int sampleRate, int channels, int bits, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(sampleRate).putInt(sampleRate * channels * bits / 8)
                .putShort((short) (channels * bits / 8)).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
        return b.array();
    }

    @Test
    void sinkWritesValidHeaderAndSourceReadsItBack() throws IOException {
        Path file = dir.resolve("tone.wav");
        short[] tone = new short[8000];
        new ToneSource(1000, 0.5).read(tone, 8000);
        WavFileSink sink = new WavFileSink(file);
        sink.write(java.util.Arrays.copyOfRange(tone, 0, 3000), 8000);
        sink.write(java.util.Arrays.copyOfRange(tone, 3000, 8000), 8000);
        assertThat(sink.samplesWritten()).isEqualTo(8000);
        sink.close();
        sink.close(); // idempotent
        sink.write(new short[10], 8000); // ignored after close

        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(bytes).hasSize(44 + 16_000);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(header.getInt(4)).isEqualTo(36 + 16_000);
        assertThat(header.getShort(22)).isEqualTo((short) 1);
        assertThat(header.getInt(24)).isEqualTo(8000);
        assertThat(header.getInt(40)).isEqualTo(16_000);

        WavFileSource source = new WavFileSource(file, false);
        assertThat(source.fileSampleRate()).isEqualTo(8000);
        assertThat(source.lengthSamples()).isEqualTo(8000);
        short[] read = new short[8000];
        source.read(read, 8000);
        assertThat(read).containsExactly(tone);
        assertThat(source.isFinished()).isTrue();
        short[] after = {5, 5};
        source.read(after, 8000);
        assertThat(after).containsOnly((short) 0);

        source.rewind();
        short[] upsampled = new short[16_000];
        source.read(upsampled, 16_000);
        assertThat(ToneDetector.relativePower(upsampled, 16_000, 1000)).isGreaterThan(0.95);
    }

    @Test
    void sinkResamplesToFixedRate() throws IOException {
        Path file = dir.resolve("fixed.wav");
        WavFileSink sink = new WavFileSink(file, 16_000);
        sink.write(new short[160], 8000);
        sink.close();
        assertThat(new WavFileSource(file, false).lengthSamples()).isEqualTo(320);
    }

    @Test
    void sourceDownmixesStereoAndLoops() throws IOException {
        ByteBuffer data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 100).putShort((short) 300).putShort((short) -100).putShort((short) -300);
        WavFileSource source = WavFileSource.fromBytes(wav(8000, 2, 16, data.array()), true);
        assertThat(source.lengthSamples()).isEqualTo(2);
        short[] out = new short[5];
        source.read(out, 8000);
        assertThat(out).containsExactly(200, -200, 200, -200, 200);
        assertThat(source.isFinished()).isFalse();
    }

    @Test
    void sourceReadsEightBitAndSkipsUnknownChunks() throws IOException {
        byte[] plain = wav(8000, 1, 8, new byte[]{(byte) 128, (byte) 255, 0});
        // insert a LIST chunk with odd size (padded) between fmt and data
        byte[] list = {'L', 'I', 'S', 'T', 3, 0, 0, 0, 1, 2, 3, 0};
        byte[] file = new byte[plain.length + list.length];
        System.arraycopy(plain, 0, file, 0, 36);
        System.arraycopy(list, 0, file, 36, list.length);
        System.arraycopy(plain, 36, file, 36 + list.length, plain.length - 36);
        WavFileSource source = WavFileSource.fromBytes(file, false);
        short[] out = new short[3];
        source.read(out, 8000);
        assertThat(out).containsExactly(0, 127 << 8, -128 << 8);
    }

    @Test
    void rejectsUnsupportedFiles() {
        assertThatThrownBy(() -> WavFileSource.fromBytes("not a wav file at all".getBytes(StandardCharsets.US_ASCII),
                false)).isInstanceOf(IOException.class);
        byte[] float32 = wav(8000, 1, 32, new byte[8]);
        float32[20] = 3; // WAVE_FORMAT_IEEE_FLOAT
        assertThatThrownBy(() -> WavFileSource.fromBytes(float32, false)).isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported");
        byte[] noData = java.util.Arrays.copyOf(wav(8000, 1, 16, new byte[0]), 36);
        assertThatThrownBy(() -> WavFileSource.fromBytes(noData, false)).isInstanceOf(IOException.class)
                .hasMessageContaining("no data");
    }
}
