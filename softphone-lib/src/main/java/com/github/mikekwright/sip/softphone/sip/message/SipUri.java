package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Locale;
import java.util.Objects;

/**
 * A SIP or SIPS URI (RFC 3261 §19.1):
 * <pre>sip:user:password@host:port;uri-parameters?headers</pre>
 *
 * <p>Other URI schemes (for example {@code tel:}, RFC 3966) are accepted and kept opaque so that headers carrying
 * them can still be parsed; for those {@link #isSip()} returns {@code false} and only {@link #scheme()},
 * {@link #user()} and {@link #toString()} are meaningful.</p>
 *
 * <p>Instances are immutable; use the {@code with*} methods to derive modified copies.</p>
 */
public final class SipUri {

    private final String scheme;
    private final String user;
    private final String password;
    private final String host;
    private final int port;
    private final Parameters parameters;
    private final String headers;
    private final String opaque;

    private SipUri(String scheme, String user, String password, String host, int port, Parameters parameters,
                   String headers, String opaque) {
        this.scheme = scheme;
        this.user = user;
        this.password = password;
        this.host = host;
        this.port = port;
        this.parameters = parameters;
        this.headers = headers;
        this.opaque = opaque;
    }

    /** Creates {@code sip:user@host} (user may be {@code null}); port -1 means "not present". */
    public static SipUri sip(String user, String host, int port) {
        return new SipUri("sip", user, null, Objects.requireNonNull(host, "host"), port, new Parameters(), null, null);
    }

    public static SipUri sip(String user, String host) {
        return sip(user, host, -1);
    }

    /** Parses a URI; throws {@link SipParseException} on malformed input. */
    public static SipUri parse(String text) {
        Objects.requireNonNull(text, "text");
        String s = text.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) {
            throw new SipParseException("URI has no scheme: " + text);
        }
        String scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = s.substring(colon + 1);
        if (!scheme.equals("sip") && !scheme.equals("sips")) {
            int semi = rest.indexOf(';');
            String user = semi < 0 ? rest : rest.substring(0, semi);
            return new SipUri(scheme, user, null, null, -1, new Parameters(), null, rest);
        }
        String headers = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            headers = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String user = null;
        String password = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int pc = userinfo.indexOf(':');
            if (pc >= 0) {
                user = userinfo.substring(0, pc);
                password = userinfo.substring(pc + 1);
            } else {
                user = userinfo;
            }
            if (user.isEmpty()) {
                throw new SipParseException("empty user part in URI: " + text);
            }
        }
        int semi = rest.indexOf(';');
        String hostport = semi < 0 ? rest : rest.substring(0, semi);
        Parameters params = semi < 0 ? new Parameters() : Parameters.parse(rest.substring(semi));
        String host;
        int port = -1;
        if (hostport.startsWith("[")) {
            int close = hostport.indexOf(']');
            if (close < 0) {
                throw new SipParseException("unterminated IPv6 reference in URI: " + text);
            }
            host = hostport.substring(0, close + 1);
            String after = hostport.substring(close + 1);
            if (after.startsWith(":")) {
                port = parsePort(after.substring(1), text);
            } else if (!after.isEmpty()) {
                throw new SipParseException("unexpected text after IPv6 host in URI: " + text);
            }
        } else {
            int pc = hostport.lastIndexOf(':');
            if (pc >= 0) {
                host = hostport.substring(0, pc);
                port = parsePort(hostport.substring(pc + 1), text);
            } else {
                host = hostport;
            }
        }
        if (host.isEmpty()) {
            throw new SipParseException("URI has no host: " + text);
        }
        return new SipUri(scheme, user, password, host, port, params, headers, null);
    }

    private static int parsePort(String p, String text) {
        try {
            int port = Integer.parseInt(p.trim());
            if (port < 0 || port > 65535) {
                throw new SipParseException("port out of range in URI: " + text);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new SipParseException("invalid port in URI: " + text, e);
        }
    }

    public String scheme() {
        return scheme;
    }

    /** True for {@code sip:} and {@code sips:} URIs. */
    public boolean isSip() {
        return opaque == null;
    }

    public boolean isSecure() {
        return scheme.equals("sips");
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** Host name, IPv4 address or bracketed IPv6 reference ({@code [::1]}). */
    public String host() {
        return host;
    }

    /** Host without IPv6 brackets, suitable for name resolution. */
    public String hostAddress() {
        return host != null && host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    }

    /** Explicit port, or -1 when the URI does not carry one. */
    public int port() {
        return port;
    }

    public boolean hasPort() {
        return port >= 0;
    }

    /** Explicit port or the default for the scheme/transport (5060, or 5061 for sips / TLS). */
    public int portOrDefault() {
        if (port >= 0) {
            return port;
        }
        return isSecure() || "tls".equalsIgnoreCase(parameter("transport")) ? 5061 : 5060;
    }

    /** A defensive copy of the URI parameters. */
    public Parameters parameters() {
        return new Parameters(parameters);
    }

    public String parameter(String name) {
        return parameters.get(name);
    }

    public boolean hasParameter(String name) {
        return parameters.has(name);
    }

    /** The {@code transport} URI parameter (lower case) or {@code null}. */
    public String transport() {
        String t = parameters.get("transport");
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    /** True if the URI carries the {@code lr} parameter, marking a loose router (RFC 3261 §16.12). */
    public boolean isLooseRouter() {
        return parameters.has("lr");
    }

    public String headers() {
        return headers;
    }

    public SipUri withUser(String newUser) {
        return new SipUri(scheme, newUser, password, host, port, parameters, headers, opaque);
    }

    public SipUri withHost(String newHost) {
        return new SipUri(scheme, user, password, newHost, port, parameters, headers, opaque);
    }

    public SipUri withPort(int newPort) {
        return new SipUri(scheme, user, password, host, newPort, parameters, headers, opaque);
    }

    public SipUri withScheme(String newScheme) {
        return new SipUri(newScheme, user, password, host, port, parameters, headers, opaque);
    }

    public SipUri withParameter(String name, String value) {
        Parameters p = new Parameters(parameters).set(name, value);
        return new SipUri(scheme, user, password, host, port, p, headers, opaque);
    }

    public SipUri withoutParameter(String name) {
        Parameters p = new Parameters(parameters).remove(name);
        return new SipUri(scheme, user, password, host, port, p, headers, opaque);
    }

    /** Returns a copy without URI parameters and headers, e.g. for use as an address-of-record. */
    public SipUri withoutParameters() {
        return new SipUri(scheme, user, password, host, port, new Parameters(), null, opaque);
    }

    @Override
    public String toString() {
        if (opaque != null) {
            return scheme + ":" + opaque;
        }
        StringBuilder sb = new StringBuilder(scheme).append(':');
        if (user != null) {
            sb.append(user);
            if (password != null) {
                sb.append(':').append(password);
            }
            sb.append('@');
        }
        sb.append(host);
        if (port >= 0) {
            sb.append(':').append(port);
        }
        sb.append(parameters);
        if (headers != null) {
            sb.append('?').append(headers);
        }
        return sb.toString();
    }

    /**
     * URI comparison following the main rules of RFC 3261 §19.1.4: scheme, user (case-sensitive), host
     * (case-insensitive), port and the parameters present in both.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SipUri other)) {
            return false;
        }
        if (opaque != null || other.opaque != null) {
            return Objects.equals(opaque, other.opaque) && scheme.equals(other.scheme);
        }
        return scheme.equals(other.scheme)
                && Objects.equals(user, other.user)
                && host.equalsIgnoreCase(other.host)
                && port == other.port
                && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, user, host == null ? null : host.toLowerCase(Locale.ROOT), port);
    }
}
