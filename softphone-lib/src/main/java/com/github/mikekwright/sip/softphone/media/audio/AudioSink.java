package com.github.mikekwright.sip.softphone.media.audio;

/**
 * A consumer of inbound audio: 16-bit signed, mono, linear PCM, delivered one decoded RTP packet at a time.
 *
 * <p>{@link #write} is called on the network event loop and must be fast and non-blocking; hand data over to
 * another thread for slow processing (e.g. speech recognition, disk I/O on slow media).</p>
 */
public interface AudioSink {

    /**
     * Consumes decoded samples.
     *
     * @param samples    decoded samples; the array is owned by the sink after the call (not reused by the caller)
     * @param sampleRate sample rate of {@code samples} in Hz (the decoding codec's sample rate)
     */
    void write(short[] samples, int sampleRate);

    /** Releases resources held by this sink (e.g. finalizes a file). Called when the owning stream stops. */
    default void close() {
    }
}
