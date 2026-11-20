package com.github.mikekwright.sip.softphone.media.dtmf;

/** Callback for detected DTMF digits. */
@FunctionalInterface
public interface DtmfListener {

    /**
     * A DTMF digit was received.
     *
     * @param digit          one of {@code 0-9 * # A-D}
     * @param durationMillis duration of the tone as reported by the sender (or observed so far, if the event's
     *                       end was never received)
     */
    void onDigit(char digit, int durationMillis);
}
