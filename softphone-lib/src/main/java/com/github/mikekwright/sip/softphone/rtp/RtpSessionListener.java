package com.github.mikekwright.sip.softphone.rtp;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Receives inbound packets of an {@link RtpSession}.
 *
 * <p>Callbacks run on the session's event loop ({@link RtpSession#eventLoop()}) and must therefore be fast and
 * non-blocking; offload heavy work to another executor. Exceptions thrown by a callback are logged and
 * swallowed. Packets are fully decoded copies: no reference counted buffers are passed to listeners.</p>
 */
public interface RtpSessionListener {

    /** An RTP data packet arrived from {@code sender}. */
    default void onRtp(RtpPacket packet, InetSocketAddress sender) {
    }

    /**
     * A compound RTCP packet arrived from {@code sender}, either on the RTCP port or multiplexed on the RTP port
     * (RFC 5761). The session has already updated its statistics from any sender reports.
     */
    default void onRtcp(List<RtcpPacket> packets, InetSocketAddress sender) {
    }
}
