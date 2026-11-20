package com.github.mikekwright.sip.softphone.call;

/**
 * Application level call state, derived from the SIP session state and media events.
 *
 * <pre>
 *  outgoing:  CALLING --> RINGING / EARLY_MEDIA --> CONNECTED --> ENDED
 *  incoming:  INCOMING (180 sent) ---------------> CONNECTED --> ENDED
 * </pre>
 * Hold is reported separately through {@link Call#isOnHold()} / {@link Call#isRemoteHold()} because a held call is
 * still connected.
 */
public enum CallState {
    /** Outgoing call: media is being prepared and the INVITE is on its way. */
    CALLING,
    /** Outgoing call: the remote party is alerting (180). */
    RINGING,
    /** Outgoing call: a provisional response carried SDP; early media flows. */
    EARLY_MEDIA,
    /** Incoming call ringing locally; answer or reject it. */
    INCOMING,
    /** The call is established. */
    CONNECTED,
    /** The call is over; see {@link Call#endReason()}. */
    ENDED;

    public boolean isActive() {
        return this != ENDED;
    }
}
