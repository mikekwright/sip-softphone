package com.github.mikekwright.sip.softphone.media.audio;

import java.util.Arrays;

/** An {@link AudioSource} producing digital silence. Stateless and thread-safe. */
public final class SilenceSource implements AudioSource {

    /** Shared instance. */
    public static final SilenceSource INSTANCE = new SilenceSource();

    @Override
    public void read(short[] buffer, int sampleRate) {
        Arrays.fill(buffer, (short) 0);
    }
}
