package com.github.mikekwright.sip.softphone.media.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An {@link AudioSource} playing a RIFF/WAVE file.
 *
 * <p>Supported encodings: linear PCM ({@code WAVE_FORMAT_PCM} or {@code WAVE_FORMAT_EXTENSIBLE}) with 8-bit
 * unsigned or 16-bit signed little-endian samples, any number of channels (downmixed to mono by averaging) and
 * any sample rate (converted to the rate requested by {@link #read} with {@link Resampler}-style linear
 * interpolation, continuously across reads). The whole file is decoded into memory when the source is created,
 * so reads never touch the disk.</p>
 *
 * <p>When the end is reached the source either loops or produces silence ({@link #isFinished()} then turns
 * true).</p>
 *
 * <p>Thread-safety: {@link #read} and the state accessors are synchronized.</p>
 */
public final class WavFileSource implements AudioSource {

    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private final short[] samples;
    private final int fileSampleRate;
    private final boolean loop;
    private double position;
    private boolean finished;

    /**
     * Loads a WAV file.
     *
     * @param path the file
     * @param loop whether to restart at the beginning when the end is reached
     * @throws IOException if the file cannot be read or is not a supported WAV file
     */
    public WavFileSource(Path path, boolean loop) throws IOException {
        this(Files.readAllBytes(path), loop, path.toString());
    }

    /**
     * Decodes WAV file contents held in memory (e.g. a class path resource).
     *
     * @throws IOException if the data is not a supported WAV file
     */
    public static WavFileSource fromBytes(byte[] wavData, boolean loop) throws IOException {
        return new WavFileSource(wavData, loop, "<memory>");
    }

    private WavFileSource(byte[] data, boolean loop, String name) throws IOException {
        this.loop = loop;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || !tag(buf, 0).equals("RIFF") || !tag(buf, 8).equals("WAVE")) {
            throw new IOException(name + ": not a RIFF/WAVE file");
        }
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int offset = 12;
        short[] decoded = null;
        while (offset + 8 <= data.length) {
            String id = tag(buf, offset);
            long chunkSize = buf.getInt(offset + 4) & 0xFFFFFFFFL;
            int body = offset + 8;
            long available = data.length - body;
            if (id.equals("fmt ")) {
                if (chunkSize < 16 || available < 16) {
                    throw new IOException(name + ": truncated fmt chunk");
                }
                int format = buf.getShort(body) & 0xFFFF;
                channels = buf.getShort(body + 2) & 0xFFFF;
                sampleRate = buf.getInt(body + 4);
                bitsPerSample = buf.getShort(body + 14) & 0xFFFF;
                if (format == FORMAT_EXTENSIBLE && chunkSize >= 26 && available >= 26) {
                    format = buf.getShort(body + 24) & 0xFFFF; // first two bytes of the sub-format GUID
                }
                if (format != FORMAT_PCM) {
                    throw new IOException(name + ": unsupported WAV encoding " + format + " (only PCM)");
                }
                if (bitsPerSample != 8 && bitsPerSample != 16) {
                    throw new IOException(name + ": unsupported bits per sample " + bitsPerSample);
                }
                if (channels < 1 || sampleRate <= 0) {
                    throw new IOException(name + ": invalid channel count or sample rate");
                }
            } else if (id.equals("data")) {
                if (channels == 0) {
                    throw new IOException(name + ": data chunk before fmt chunk");
                }
                // Streaming writers may leave the size at 0 or 0xFFFFFFFF: then use the rest of the file.
                long size = (chunkSize == 0 || chunkSize > available) ? available : chunkSize;
                decoded = decode(buf, body, (int) size, channels, bitsPerSample);
                break;
            }
            offset = (int) Math.min(Integer.MAX_VALUE, body + chunkSize + (chunkSize & 1));
        }
        if (decoded == null) {
            throw new IOException(name + ": no data chunk");
        }
        this.samples = decoded;
        this.fileSampleRate = sampleRate;
    }

    private static String tag(ByteBuffer buf, int offset) {
        byte[] bytes = new byte[4];
        buf.get(offset, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static short[] decode(ByteBuffer buf, int offset, int size, int channels, int bitsPerSample) {
        int bytesPerSample = bitsPerSample / 8;
        int frames = size / (bytesPerSample * channels);
        short[] mono = new short[frames];
        int pos = offset;
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                if (bytesPerSample == 2) {
                    sum += buf.getShort(pos);
                } else {
                    sum += ((buf.get(pos) & 0xFF) - 128) << 8;
                }
                pos += bytesPerSample;
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    /** Sample rate of the file. */
    public int fileSampleRate() {
        return fileSampleRate;
    }

    /** Number of (mono) samples in the file. */
    public int lengthSamples() {
        return samples.length;
    }

    /** Whether a non-looping source has played the whole file. */
    public synchronized boolean isFinished() {
        return finished;
    }

    /** Restarts playback at the beginning. */
    public synchronized void rewind() {
        position = 0;
        finished = false;
    }

    @Override
    public synchronized void read(short[] buffer, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sample rate must be positive");
        }
        int length = samples.length;
        double step = (double) fileSampleRate / sampleRate;
        for (int i = 0; i < buffer.length; i++) {
            if (finished || length == 0) {
                finished = true;
                buffer[i] = 0;
                continue;
            }
            int index = (int) position;
            double fraction = position - index;
            int nextIndex = index + 1;
            short next;
            if (nextIndex < length) {
                next = samples[nextIndex];
            } else {
                next = loop ? samples[0] : samples[index];
            }
            buffer[i] = (short) Math.round(samples[index] * (1 - fraction) + next * fraction);
            position += step;
            if (position >= length) {
                if (loop) {
                    position -= length;
                } else {
                    finished = true;
                }
            }
        }
    }
}
