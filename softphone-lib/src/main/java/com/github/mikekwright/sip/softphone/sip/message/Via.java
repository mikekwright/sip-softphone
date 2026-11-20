package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Locale;
import java.util.Objects;

/**
 * A single {@code Via} header value (RFC 3261 §20.42, §18):
 * <pre>SIP/2.0/UDP pc33.atlanta.com:5060;branch=z9hG4bK776asdhds;rport;received=192.0.2.1</pre>
 *
 * <p>The {@code branch} parameter identifies the transaction (RFC 3261 §8.1.1.7, §17); branches created by
 * RFC 3261 compliant elements begin with the magic cookie {@value #MAGIC_COOKIE}. {@code received} and
 * {@code rport} (RFC 3581) record where a request actually came from so responses can be routed back.</p>
 */
public final class Via {

    /** RFC 3261 §8.1.1.7 magic cookie prefix of branch identifiers. */
    public static final String MAGIC_COOKIE = "z9hG4bK";

    private final String protocol;
    private final String transport;
    private final String host;
    private final int port;
    private final Parameters parameters;

    public Via(String transport, String host, int port, Parameters parameters) {
        this("SIP/2.0", transport, host, port, parameters);
    }

    private Via(String protocol, String transport, String host, int port, Parameters parameters) {
        this.protocol = protocol;
        this.transport = Objects.requireNonNull(transport, "transport").toUpperCase(Locale.ROOT);
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.parameters = parameters == null ? new Parameters() : new Parameters(parameters);
    }

    public static Via parse(String text) {
        String s = text.trim();
        // sent-protocol = protocol-name SLASH protocol-version SLASH transport, LWS allowed around slashes
        int idx = 0;
        String[] proto = new String[3];
        for (int part = 0; part < 3; part++) {
            while (idx < s.length() && Character.isWhitespace(s.charAt(idx))) {
                idx++;
            }
            int start = idx;
            while (idx < s.length() && s.charAt(idx) != '/' && !(part == 2 && Character.isWhitespace(s.charAt(idx)))) {
                idx++;
            }
            proto[part] = s.substring(start, idx).trim();
            if (part < 2) {
                if (idx >= s.length()) {
                    throw new SipParseException("malformed Via sent-protocol: " + text);
                }
                idx++; // skip '/'
            }
        }
        String sentBy = s.substring(idx).trim();
        int semi = SipSyntax.indexOfUnquoted(sentBy, ';', 0);
        Parameters params = semi < 0 ? new Parameters() : Parameters.parse(sentBy.substring(semi));
        String hostport = (semi < 0 ? sentBy : sentBy.substring(0, semi)).trim();
        // drop comments, e.g. "host:5060 (comment)"
        int paren = hostport.indexOf('(');
        if (paren >= 0) {
            hostport = hostport.substring(0, paren).trim();
        }
        String host;
        int port = -1;
        if (hostport.startsWith("[")) {
            int close = hostport.indexOf(']');
            if (close < 0) {
                throw new SipParseException("malformed IPv6 sent-by in Via: " + text);
            }
            host = hostport.substring(0, close + 1);
            String rest = hostport.substring(close + 1).trim();
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1), text);
            }
        } else {
            int colon = hostport.indexOf(':');
            if (colon >= 0) {
                host = hostport.substring(0, colon).trim();
                port = parsePort(hostport.substring(colon + 1), text);
            } else {
                host = hostport;
            }
        }
        if (host.isEmpty() || proto[2].isEmpty()) {
            throw new SipParseException("malformed Via: " + text);
        }
        return new Via(proto[0] + "/" + proto[1], proto[2], host, port, params);
    }

    private static int parsePort(String s, String text) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new SipParseException("invalid port in Via: " + text, e);
        }
    }

    /** Transport token in upper case, e.g. {@code UDP}, {@code TCP}, {@code TLS}. */
    public String transport() {
        return transport;
    }

    public String host() {
        return host;
    }

    /** Explicit sent-by port or -1. */
    public int port() {
        return port;
    }

    /** sent-by port, defaulting to 5060 (5061 for TLS) as per RFC 3261 §18.2.2. */
    public int portOrDefault() {
        return port >= 0 ? port : (transport.equals("TLS") ? 5061 : 5060);
    }

    /** {@code host[:port]} exactly as it should be compared for transaction matching (RFC 3261 §17.2.3). */
    public String sentBy() {
        return port >= 0 ? host + ":" + port : host;
    }

    public String branch() {
        return parameters.get("branch");
    }

    /** True when the branch carries the RFC 3261 magic cookie. */
    public boolean hasRfc3261Branch() {
        String b = branch();
        return b != null && b.startsWith(MAGIC_COOKIE);
    }

    public String received() {
        return parameters.get("received");
    }

    public boolean hasRport() {
        return parameters.has("rport");
    }

    /** The filled-in {@code rport} value (RFC 3581) or -1 when absent or empty. */
    public int rport() {
        String v = parameters.get("rport");
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String parameter(String name) {
        return parameters.get(name);
    }

    public Parameters parameters() {
        return new Parameters(parameters);
    }

    public Via withParameter(String name, String value) {
        return new Via(protocol, transport, host, port, new Parameters(parameters).set(name, value));
    }

    public Via withBranch(String branch) {
        return withParameter("branch", branch);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(protocol).append('/').append(transport).append(' ').append(host);
        if (port >= 0) {
            sb.append(':').append(port);
        }
        return sb.append(parameters).toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Via v && v.toString().equals(toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }
}
