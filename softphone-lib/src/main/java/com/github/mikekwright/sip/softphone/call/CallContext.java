package com.github.mikekwright.sip.softphone.call;

import com.github.mikekwright.sip.softphone.SoftphoneConfig;
import com.github.mikekwright.sip.softphone.media.audio.AudioCodecRegistry;
import com.github.mikekwright.sip.softphone.rtp.RtpSession;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;

import java.util.concurrent.CompletableFuture;

/**
 * What a {@link Call} needs from its owning phone. Implemented by
 * {@link com.github.mikekwright.sip.softphone.Softphone}; a separate interface keeps calls testable and lets
 * alternative front ends reuse the call logic.
 */
public interface CallContext {

    SoftphoneConfig config();

    SipStack stack();

    AudioCodecRegistry audioCodecs();

    /** Binds a new RTP/RTCP port pair. */
    CompletableFuture<RtpSession> bindRtp();

    /** Address advertised in SDP {@code c=} lines. */
    String mediaHost();

    /** Runs a listener notification on the event thread. */
    void dispatch(Runnable event);

    void onCallStateChanged(Call call, CallState state);

    void onCallEnded(Call call);

    /** Creates call objects for the phone implementation (constructor is package private). */
    static Call newCall(CallContext context, CallDirection direction, CallOptions options,
                        com.github.mikekwright.sip.softphone.sip.message.NameAddress remote) {
        return new Call(context, direction, options, remote);
    }

    /** Starts an outgoing call created with {@link #newCall}. Must be called once. */
    static void startOutgoing(Call call, com.github.mikekwright.sip.softphone.sip.message.NameAddress from,
                              com.github.mikekwright.sip.softphone.sip.message.NameAddress to) {
        call.startOutgoing(from, to);
    }

    /** Binds an incoming INVITE to a call created with {@link #newCall}. Call on the SIP core thread. */
    static void attachIncoming(Call call, com.github.mikekwright.sip.softphone.sip.core.IncomingRequest request,
                               com.github.mikekwright.sip.softphone.sdp.SessionDescription offer) {
        call.attachIncoming(request, offer);
    }
}
