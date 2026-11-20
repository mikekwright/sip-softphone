package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * One listening transport (a UDP socket, or a TCP/TLS server plus its outgoing connections).
 * Implementations are thread-safe.
 */
public interface SipTransport {

    TransportType type();

    /** The bound local address (with the actual port when an ephemeral port was requested). */
    InetSocketAddress localAddress();

    /** Sends a message to the given remote address, opening a connection first for stream transports. */
    CompletableFuture<Void> send(SipMessage message, InetSocketAddress remote);

    /**
     * Sends a CRLF keep-alive (RFC 5626 §3.5.1: a double CRLF "ping") to {@code remote}, keeping NAT bindings and
     * connections towards that peer alive.
     */
    CompletableFuture<Void> sendKeepAlive(InetSocketAddress remote);

    /** Closes the socket and all connections. */
    CompletableFuture<Void> close();

    /** Receives messages decoded by a transport. Invoked on Netty I/O threads. */
    @FunctionalInterface
    interface Listener {
        void onMessage(InboundMessage message);
    }
}
