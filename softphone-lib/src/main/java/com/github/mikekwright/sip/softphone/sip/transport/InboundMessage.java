package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;

/**
 * A SIP message received from the network together with its transport context.
 *
 * @param message   the parsed message
 * @param transport transport it arrived on
 * @param remote    the packet source (used for {@code received}/{@code rport}, RFC 3261 §18.2.1, RFC 3581)
 * @param local     the local socket address it was received on
 * @param channel   the Netty channel (for stream transports this is the connection to reuse for responses)
 */
public record InboundMessage(SipMessage message, TransportType transport, InetSocketAddress remote,
                             InetSocketAddress local, Channel channel) {

    public boolean isRequest() {
        return message instanceof SipRequest;
    }

    public SipRequest request() {
        return (SipRequest) message;
    }

    public SipResponse response() {
        return (SipResponse) message;
    }

    /** The source as a transport address, e.g. to send a response or a new request back over the same flow. */
    public TransportAddress source() {
        return new TransportAddress(transport, remote);
    }
}
