package com.github.mikekwright.sip.softphone.terminal;

import com.github.mikekwright.sip.softphone.media.audio.AudioSink;
import com.github.mikekwright.sip.softphone.media.audio.AudioSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Microphone and speaker through {@code javax.sound.sampled}. The media clock calls {@link #read} and
 * {@link #write} on a network event loop every 20 ms, so device I/O (which blocks) runs on dedicated threads with
 * small buffers in between: capture fills a ring buffer, playback drains a queue.
 */
final class JavaSoundAudio {

    private JavaSoundAudio() {
    }

    /** True when the system reports at least one capture and one playback line for 16-bit mono PCM. */
    static boolean available() {
        try {
            AudioFormat f = format(8000);
            return AudioSystem.isLineSupported(new javax.sound.sampled.DataLine.Info(TargetDataLine.class, f))
                    && AudioSystem.isLineSupported(new javax.sound.sampled.DataLine.Info(SourceDataLine.class, f));
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    static AudioFormat format(int rate) {
        return new AudioFormat(rate, 16, 1, true, false);
    }

    /** Captures from the default microphone. */
    static final class Microphone implements AudioSource {
        private final short[] ring = new short[16000];
        private int writePos;
        private int readPos;
        private int available;
        private int rate;
        private volatile TargetDataLine line;
        private volatile boolean closed;

        @Override
        public void read(short[] buffer, int sampleRate) {
            if (line == null && !closed) {
                open(sampleRate);
            }
            synchronized (ring) {
                int n = Math.min(buffer.length, available);
                for (int i = 0; i < n; i++) {
                    buffer[i] = ring[readPos];
                    readPos = (readPos + 1) % ring.length;
                }
                available -= n;
                for (int i = n; i < buffer.length; i++) {
                    buffer[i] = 0;
                }
            }
        }

        private synchronized void open(int sampleRate) {
            if (line != null) {
                return;
            }
            try {
                rate = sampleRate;
                TargetDataLine l = AudioSystem.getTargetDataLine(format(rate));
                l.open(format(rate), rate / 5);
                l.start();
                line = l;
                Thread t = new Thread(this::capture, "mic-capture");
                t.setDaemon(true);
                t.start();
            } catch (LineUnavailableException | IllegalArgumentException e) {
                closed = true;
                System.err.println("Microphone unavailable: " + e.getMessage() + " (sending silence)");
            }
        }

        private void capture() {
            byte[] bytes = new byte[rate / 50 * 2];
            while (!closed) {
                int n = line.read(bytes, 0, bytes.length);
                synchronized (ring) {
                    for (int i = 0; i + 1 < n; i += 2) {
                        ring[writePos] = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
                        writePos = (writePos + 1) % ring.length;
                        if (available < ring.length) {
                            available++;
                        } else {
                            readPos = (readPos + 1) % ring.length; // overflow: drop oldest
                        }
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            TargetDataLine l = line;
            if (l != null) {
                l.close();
            }
        }
    }

    /** Plays to the default speaker. */
    static final class Speaker implements AudioSink {
        private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(25);
        private volatile SourceDataLine line;
        private volatile boolean closed;

        @Override
        public void write(short[] samples, int sampleRate) {
            if (line == null && !closed) {
                open(sampleRate);
            }
            if (!queue.offer(samples.clone())) {
                queue.poll(); // too much latency: drop the oldest chunk
                queue.offer(samples.clone());
            }
        }

        private synchronized void open(int sampleRate) {
            if (line != null) {
                return;
            }
            try {
                SourceDataLine l = AudioSystem.getSourceDataLine(format(sampleRate));
                l.open(format(sampleRate), sampleRate / 5);
                l.start();
                line = l;
                Thread t = new Thread(this::play, "speaker-playback");
                t.setDaemon(true);
                t.start();
            } catch (LineUnavailableException | IllegalArgumentException e) {
                closed = true;
                System.err.println("Speaker unavailable: " + e.getMessage() + " (discarding audio)");
            }
        }

        private void play() {
            while (!closed) {
                try {
                    short[] s = queue.take();
                    byte[] b = new byte[s.length * 2];
                    for (int i = 0; i < s.length; i++) {
                        b[2 * i] = (byte) s[i];
                        b[2 * i + 1] = (byte) (s[i] >> 8);
                    }
                    line.write(b, 0, b.length);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            SourceDataLine l = line;
            if (l != null) {
                l.close();
            }
        }
    }
}
