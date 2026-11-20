package com.github.mikekwright.sip.softphone.sdp;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of the offer/answer exchange for one {@code m=} line, from the local party's point of view.
 *
 * @param index             position of the {@code m=} line in the offer and answer (RFC 3264 §6)
 * @param mediaType         media type, e.g. {@code audio}
 * @param rejected          whether the stream was rejected or disabled (port 0); if so the remaining fields only
 *                          carry what was declared and no media must flow
 * @param remoteAddress     where to send RTP: the peer's media-level {@code c=} address, else its session-level one
 * @param remotePort        the peer's RTP port
 * @param remoteRtcpAddress where to send RTCP: the {@code a=rtcp} address (RFC 3605), else {@code remoteAddress}
 * @param remoteRtcpPort    the {@code a=rtcp} port, else {@code remotePort} when muxed, else {@code remotePort + 1}
 *                          (RFC 3550 §11)
 * @param rtcpMux           whether both sides agreed on RTP/RTCP multiplexing (RFC 5761)
 * @param formats           agreed formats other than {@code telephone-event}, in priority order; the first is the
 *                          one to send with
 * @param telephoneEvent    the agreed RFC 4733 {@code telephone-event} format, or {@code null}
 * @param direction         what we may do: {@code SENDRECV}, {@code SENDONLY}, {@code RECVONLY} or {@code INACTIVE};
 *                          never allows sending to an unspecified ({@code 0.0.0.0}) remote address
 */
public record NegotiatedMedia(int index, String mediaType, boolean rejected, String remoteAddress, int remotePort,
                              String remoteRtcpAddress, int remoteRtcpPort, boolean rtcpMux,
                              List<NegotiatedFormat> formats, NegotiatedFormat telephoneEvent,
                              MediaDirection direction) {

    public NegotiatedMedia {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(direction, "direction");
        formats = formats == null ? List.of() : List.copyOf(formats);
    }

    /** The format to send with (the first agreed format), or {@code null} if the stream was rejected. */
    public NegotiatedFormat primaryFormat() {
        return formats.isEmpty() ? null : formats.getFirst();
    }

    /** Whether we may send media on this stream. */
    public boolean canSend() {
        return !rejected && direction.canSend();
    }

    /** Whether we should expect to receive media on this stream. */
    public boolean canReceive() {
        return !rejected && direction.canReceive();
    }
}
