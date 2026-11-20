package com.github.mikekwright.sip.softphone.sip.dialog;

import java.util.Objects;

/**
 * Dialog identifier (RFC 3261 §12): Call-ID plus local and remote tags, always from the local UA's perspective.
 */
public record DialogId(String callId, String localTag, String remoteTag) {

    public DialogId {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(localTag, "localTag");
    }

    @Override
    public String toString() {
        return callId + ";local=" + localTag + ";remote=" + remoteTag;
    }
}
