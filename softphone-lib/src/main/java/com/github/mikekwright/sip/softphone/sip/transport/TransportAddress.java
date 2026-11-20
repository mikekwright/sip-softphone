package com.github.mikekwright.sip.softphone.sip.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * A resolved next hop: the transport protocol and the socket address to send to (RFC 3263 output).
 */
public record TransportAddress(TransportType transport, InetSocketAddress address) {

    public TransportAddress {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(address, "address");
    }

    public static TransportAddress of(TransportType transport, String host, int port) {
        return new TransportAddress(transport, new InetSocketAddress(host, port));
    }

    @Override
    public String toString() {
        return transport + ":" + address.getHostString() + ":" + address.getPort();
    }
}
