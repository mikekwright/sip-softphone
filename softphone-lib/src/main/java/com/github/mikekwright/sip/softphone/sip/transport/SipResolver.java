package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.message.SipUri;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Locates SIP servers for a URI (RFC 3263): selects the transport and resolves host/port candidates in order of
 * preference. Implementations may be replaced, e.g. to use a test DNS or static routing tables.
 */
@FunctionalInterface
public interface SipResolver {

    /**
     * Resolves the next hop for a URI.
     *
     * @param uri              target URI (Request-URI, first Route or outbound proxy)
     * @param defaultTransport transport to use when the URI does not determine one
     * @return candidate destinations, best first; never empty on success
     */
    CompletableFuture<List<TransportAddress>> resolve(SipUri uri, TransportType defaultTransport);
}
