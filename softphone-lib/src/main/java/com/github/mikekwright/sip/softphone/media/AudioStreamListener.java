package com.github.mikekwright.sip.softphone.media;

/**
 * Events of an {@link AudioStream}. Callbacks run on the stream's event loop and must be fast and non-blocking.
 */
public interface AudioStreamListener {

    /**
     * A DTMF digit was received as an RFC 4733 telephone event. Each event is reported exactly once.
     *
     * @param digit          one of {@code 0-9 * # A-D}
     * @param durationMillis duration reported by the sender
     */
    default void onDtmf(char digit, int durationMillis) {
    }
}
