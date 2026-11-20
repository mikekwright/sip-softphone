package com.github.mikekwright.sip.softphone.sip.session;

/** Signalling state of an {@link InviteSession}. */
public enum SessionState {
    /** Created, INVITE not yet sent (UAC) or not yet answered at all (UAS). */
    INITIAL,
    /** UAC: INVITE sent, no provisional response with a tag yet. */
    CALLING,
    /** UAS: INVITE received, waiting for the application to ring / answer. */
    INCOMING,
    /** Early dialog: 1xx with To tag sent (UAS) or received (UAC). */
    EARLY,
    /** UAS: 2xx sent, waiting for the ACK. */
    CONNECTING,
    /** Dialog confirmed: the call is established. */
    CONFIRMED,
    /** BYE / CANCEL sent, waiting for completion. */
    TERMINATING,
    TERMINATED
}
