package com.github.mikekwright.sip.softphone.terminal;

import com.github.mikekwright.sip.softphone.media.audio.AudioSink;
import com.github.mikekwright.sip.softphone.media.audio.AudioSource;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sends received audio back to the caller (an "echo test" endpoint), handy as an automated test target.
 */
final class EchoAudio implements AudioSource, AudioSink {

    private final Deque<Short> buffer = new ArrayDeque<>();

    @Override
    public synchronized void write(short[] samples, int sampleRate) {
        for (short s : samples) {
            if (buffer.size() < sampleRate) {
                buffer.addLast(s);
            }
        }
    }

    @Override
    public synchronized void read(short[] out, int sampleRate) {
        for (int i = 0; i < out.length; i++) {
            Short s = buffer.pollFirst();
            out[i] = s == null ? 0 : s;
        }
    }

    @Override
    public void close() {
        // nothing to release
    }
}
