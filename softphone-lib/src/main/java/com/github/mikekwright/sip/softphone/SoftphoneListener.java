package com.github.mikekwright.sip.softphone;

import com.github.mikekwright.sip.softphone.call.Call;
import com.github.mikekwright.sip.softphone.call.CallState;
import com.github.mikekwright.sip.softphone.sip.flow.RegistrationState;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;

/**
 * Phone level events. Invoked on the softphone's event thread, in order.
 */
public interface SoftphoneListener {

    /**
     * A new incoming call is ringing (180 Ringing has been sent). Call {@link Call#answer()} or {@link Call#reject()}
     * now or later; unanswered calls keep ringing until the caller gives up.
     */
    default void onIncomingCall(Call call) {
    }

    /** Any call changed state (convenient for UIs that track all calls). */
    default void onCallStateChanged(Call call, CallState state) {
    }

    default void onRegistrationStateChanged(RegistrationState state, int statusCode) {
    }

    /** An out-of-dialog SIP MESSAGE (RFC 3428) arrived. It has already been answered with 200. */
    default void onMessage(NameAddress from, String contentType, String body) {
    }
}
