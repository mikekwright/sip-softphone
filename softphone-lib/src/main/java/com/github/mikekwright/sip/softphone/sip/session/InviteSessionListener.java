package com.github.mikekwright.sip.softphone.sip.session;

import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;

/**
 * Callbacks from an {@link InviteSession}. All methods are invoked on the SIP core executor and must not block.
 * Session descriptions are passed as SDP text so that the signalling layer stays independent of media handling.
 */
public interface InviteSessionListener {

    /** UAC: a provisional response (100-199) arrived. */
    default void onProvisional(InviteSession session, SipResponse response) {
    }

    /**
     * The remote SDP answer to our offer arrived (in a 18x for early media, in the 2xx, or in the ACK when the
     * remote INVITE carried no offer). May be called more than once; apply idempotently.
     */
    default void onAnswer(InviteSession session, String sdp) {
    }

    /** The dialog is confirmed: the 2xx was acknowledged. */
    default void onEstablished(InviteSession session) {
    }

    /**
     * The remote party sent a new offer in a re-INVITE (RFC 3261 §14.2, RFC 3264 §8).
     *
     * @return the SDP answer, or {@code null} to reject the offer with 488 Not Acceptable Here
     */
    default String onRemoteOffer(InviteSession session, String offer) {
        return null;
    }

    /**
     * The remote party sent a re-INVITE without SDP: we must make an offer in the 2xx and receive the answer in the
     * ACK (RFC 3261 §14.2, delayed offer).
     *
     * @return the SDP offer to send
     */
    default String onOfferRequested(InviteSession session) {
        return null;
    }

    /** INFO received within the dialog (RFC 6086), e.g. {@code application/dtmf-relay}. Answered with 200. */
    default void onInfo(InviteSession session, SipRequest info) {
    }

    /** MESSAGE received within the dialog (RFC 3428). Answered with 200. */
    default void onMessage(InviteSession session, SipRequest message) {
    }

    /** The session ended. Called exactly once. */
    default void onTerminated(InviteSession session, TerminationReason reason) {
    }
}
