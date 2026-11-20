package com.github.mikekwright.sip.softphone.sip.flow;

/** Lifecycle of a {@link Registration}. */
public enum RegistrationState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    UNREGISTERING,
    FAILED
}
