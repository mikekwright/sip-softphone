package com.github.mikekwright.sip.softphone.media;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * Transport neutral description of one negotiated media stream. The call layer derives it from the SDP
 * offer/answer exchange and hands it to the media layer, which keeps the media layer independent of SDP.
 *
 * <p>Payload type numbers follow RTP rules: we <em>send</em> using the numbers the remote party declared in its
 * SDP and we <em>receive</em> using the numbers we declared in ours.</p>
 *
 * @param type                     audio or video
 * @param remoteRtp                where to send RTP; {@code null} or an unresolved/any address means "do not send"
 * @param remoteRtcp               where to send RTCP; {@code null} means RTP port + 1 (or the RTP address when muxed)
 * @param rtcpMux                  whether RTP and RTCP share a port (RFC 5761)
 * @param sendFormat               the codec used for sending (payload type from the remote SDP)
 * @param receiveFormats           all codecs we accept (payload types from the local SDP)
 * @param sendTelephoneEventPt     RFC 4733 payload type for sending DTMF, or {@code null} if not negotiated
 * @param receiveTelephoneEventPt  RFC 4733 payload type we receive DTMF on, or {@code null}
 * @param send                     whether we may send media (direction sendrecv/sendonly)
 * @param receive                  whether we expect to receive media (direction sendrecv/recvonly)
 * @param ptimeMillis              packetization time for audio, typically 20
 */
public record MediaStreamSpec(MediaType type,
                              InetSocketAddress remoteRtp,
                              InetSocketAddress remoteRtcp,
                              boolean rtcpMux,
                              MediaFormat sendFormat,
                              List<MediaFormat> receiveFormats,
                              Integer sendTelephoneEventPt,
                              Integer receiveTelephoneEventPt,
                              boolean send,
                              boolean receive,
                              int ptimeMillis) {

    public MediaStreamSpec {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sendFormat, "sendFormat");
        receiveFormats = receiveFormats == null ? List.of() : List.copyOf(receiveFormats);
        if (ptimeMillis <= 0) {
            ptimeMillis = 20;
        }
    }

    /** True when the remote RTP address is usable as a send target (not null, resolved, not 0.0.0.0, port &gt; 0). */
    public boolean hasRemoteTarget() {
        return remoteRtp != null && !remoteRtp.isUnresolved() && remoteRtp.getPort() > 0
                && !remoteRtp.getAddress().isAnyLocalAddress();
    }
}
