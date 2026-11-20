package com.github.mikekwright.sip.softphone.sip.dialog;

/** Dialog states (RFC 3261 §12): created EARLY by a provisional response with a tag, CONFIRMED by a 2xx. */
public enum DialogState {
    EARLY,
    CONFIRMED,
    TERMINATED
}
