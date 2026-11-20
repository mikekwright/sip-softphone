package com.github.mikekwright.sip.softphone.sip.transport;

/**
 * A host (name or literal address) and port as advertised in {@code Via} sent-by and {@code Contact} URIs.
 */
public record HostPort(String host, int port) {

    /** Host formatted for use in a SIP URI or Via, bracketing IPv6 literals. */
    public String uriHost() {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    @Override
    public String toString() {
        return uriHost() + ":" + port;
    }
}
