package com.github.mikekwright.sip.softphone.sip.transport;

import java.util.Objects;

/**
 * A local address on which the stack listens for one transport.
 *
 * @param transport transport protocol
 * @param host      local bind address (e.g. {@code 0.0.0.0} or {@code 127.0.0.1})
 * @param port      local port, 0 for an ephemeral port
 */
public record ListenPoint(TransportType transport, String host, int port) {

    public ListenPoint {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port " + port);
        }
    }
}
