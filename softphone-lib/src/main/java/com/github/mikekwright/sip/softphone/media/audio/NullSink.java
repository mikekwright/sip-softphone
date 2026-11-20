package com.github.mikekwright.sip.softphone.media.audio;

/** An {@link AudioSink} that discards everything. Stateless and thread-safe. */
public final class NullSink implements AudioSink {

    /** Shared instance. */
    public static final NullSink INSTANCE = new NullSink();

    @Override
    public void write(short[] samples, int sampleRate) {
        // discard
    }
}
