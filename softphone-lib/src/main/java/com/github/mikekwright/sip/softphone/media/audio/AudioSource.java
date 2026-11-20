package com.github.mikekwright.sip.softphone.media.audio;

/**
 * A pull-model source of outbound audio: 16-bit signed, mono, linear PCM.
 *
 * <p>The media clock of an audio stream calls {@link #read} once per packetization interval (ptime, typically
 * every 20 ms) on the network event loop, so implementations must return quickly and never block; pre-load or
 * buffer data on another thread if it is slow to produce. A source is used by one stream at a time but may be
 * swapped at runtime, so implementations that can be shared or reconfigured from other threads must document
 * their own thread-safety.</p>
 */
public interface AudioSource {

    /**
     * Fills {@code buffer} completely with the next {@code buffer.length} samples at {@code sampleRate} Hz.
     * Sources that have run out of data fill with silence (zeros).
     */
    void read(short[] buffer, int sampleRate);

    /** Releases resources held by this source. Called when the owning stream stops. */
    default void close() {
    }
}
