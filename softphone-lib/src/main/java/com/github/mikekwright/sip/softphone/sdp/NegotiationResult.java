package com.github.mikekwright.sip.softphone.sdp;

import java.util.List;
import java.util.Objects;

/**
 * The result of answering an offer with {@link SdpNegotiator#createAnswer}.
 *
 * @param answer the answer to send to the offerer
 * @param media  the negotiated streams, one per {@code m=} line of the offer, in the same order
 */
public record NegotiationResult(SessionDescription answer, List<NegotiatedMedia> media) {

    public NegotiationResult {
        Objects.requireNonNull(answer, "answer");
        media = List.copyOf(media);
    }
}
