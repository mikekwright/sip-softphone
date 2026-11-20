package com.github.mikekwright.sip.softphone.media.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An {@link AudioSink} writing a 16-bit mono PCM WAV file.
 *
 * <p>The sample rate is fixed by the constructor or, if not given, by the first write; audio at other rates is
 * resampled. Samples are buffered and the RIFF and data chunk sizes in the header are fixed up by
 * {@link #close()}, which must be called to obtain a valid file (an audio stream closes its sink when stopped).</p>
 *
 * <p>Thread-safety: all methods are synchronized. Write failures surface as {@link UncheckedIOException}.</p>
 */
public final class WavFileSink implements AudioSink {

    private static final int HEADER_SIZE = 44;

    private final Path path;
    private final OutputStream out;
    private int sampleRate;
    private long dataBytes;
    private boolean closed;

    /** Creates (or truncates) {@code path}; the sample rate is taken from the first write. */
    public WavFileSink(Path path) throws IOException {
        this(path, 0);
    }

    /**
     * Creates (or truncates) {@code path} with a fixed sample rate.
     *
     * @param sampleRate the file's sample rate in Hz, or 0 to use the rate of the first write
     */
    public WavFileSink(Path path, int sampleRate) throws IOException {
        if (sampleRate < 0) {
            throw new IllegalArgumentException("sample rate must not be negative");
        }
        this.path = path;
        this.sampleRate = sampleRate;
        this.out = new BufferedOutputStream(Files.newOutputStream(path), 32 * 1024);
        out.write(new byte[HEADER_SIZE]); // placeholder, rewritten on close
    }

    /** The output file. */
    public Path path() {
        return path;
    }

    /** Number of samples written so far. */
    public synchronized long samplesWritten() {
        return dataBytes / 2;
    }

    @Override
    public synchronized void write(short[] samples, int sampleRate) {
        if (closed) {
            return;
        }
        if (this.sampleRate == 0) {
            this.sampleRate = sampleRate;
        }
        short[] data = Resampler.resample(samples, sampleRate, this.sampleRate);
        ByteBuffer bytes = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : data) {
            bytes.putShort(s);
        }
        try {
            out.write(bytes.array());
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + path, e);
        }
        dataBytes += bytes.capacity();
    }

    /** Flushes buffered samples and writes the final header. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                file.seek(0);
                file.write(header(sampleRate == 0 ? 8000 : sampleRate, dataBytes));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("finalizing " + path, e);
        }
    }

    /** Builds a canonical 44-byte header for 16-bit mono PCM. */
    static byte[] header(int sampleRate, long dataBytes) {
        long data = Math.min(dataBytes, 0xFFFFFFFFL - 36);
        ByteBuffer h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        h.putInt((int) (36 + data));
        h.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        h.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        h.putInt(16);
        h.putShort((short) 1);          // PCM
        h.putShort((short) 1);          // mono
        h.putInt(sampleRate);
        h.putInt(sampleRate * 2);       // byte rate
        h.putShort((short) 2);          // block align
        h.putShort((short) 16);         // bits per sample
        h.put("data".getBytes(StandardCharsets.US_ASCII));
        h.putInt((int) data);
        return h.array();
    }
}
