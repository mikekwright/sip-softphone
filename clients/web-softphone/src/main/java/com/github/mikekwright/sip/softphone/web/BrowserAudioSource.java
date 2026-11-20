package com.github.mikekwright.sip.softphone.web;

import com.github.mikekwright.sip.softphone.media.audio.AudioSource;

/**
 * Audio captured by the browser (PCM chunks over the WebSocket) buffered for the RTP media clock. The browser
 * produces at its own pace; this jitter buffer absorbs the difference and drops the oldest samples when more than
 * {@code maxLatencyMillis} accumulate.
 */
final class BrowserAudioSource implements AudioSource {

    private final short[] ring;
    private int readPos;
    private int writePos;
    private int available;
    private volatile int rate = 8000;

    BrowserAudioSource(int maxSamples) {
        this.ring = new short[maxSamples];
    }

    void setRate(int rate) {
        this.rate = rate;
    }

    int rate() {
        return rate;
    }

    /** Appends little-endian PCM16 samples from a WebSocket frame. */
    synchronized void push(byte[] pcmLe, int offset) {
        for (int i = offset; i + 1 < pcmLe.length; i += 2) {
            ring[writePos] = (short) ((pcmLe[i] & 0xff) | (pcmLe[i + 1] << 8));
            writePos = (writePos + 1) % ring.length;
            if (available < ring.length) {
                available++;
            } else {
                readPos = (readPos + 1) % ring.length;
            }
        }
    }

    @Override
    public synchronized void read(short[] buffer, int sampleRate) {
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

    synchronized void clear() {
        readPos = writePos = available = 0;
    }
}
