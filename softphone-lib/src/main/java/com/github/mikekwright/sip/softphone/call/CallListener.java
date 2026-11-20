package com.github.mikekwright.sip.softphone.call;

import com.github.mikekwright.sip.softphone.sip.session.TerminationReason;

/**
 * Call events. Invoked on the softphone's event thread (never on the SIP core or a network thread), in order;
 * implementations may call back into the {@link Call} API but should not block for long.
 */
public interface CallListener {

    default void onStateChanged(Call call, CallState state) {
    }

    /** Media was (re)negotiated: codecs, direction or remote address changed. */
    default void onMediaChanged(Call call) {
    }

    /** Local or remote hold state changed. */
    default void onHoldChanged(Call call, boolean localHold, boolean remoteHold) {
    }

    /** A DTMF digit arrived (RFC 4733 telephone-event or SIP INFO {@code application/dtmf-relay}). */
    default void onDtmf(Call call, char digit, int durationMillis) {
    }

    /** A SIP MESSAGE or INFO with a text body arrived within the call's dialog. */
    default void onMessage(Call call, String contentType, String body) {
    }

    /** The remote party asked for a video key frame (RTCP PLI / FIR). */
    default void onKeyFrameRequested(Call call) {
    }

    default void onEnded(Call call, TerminationReason reason) {
    }
}
