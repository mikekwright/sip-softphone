package com.github.mikekwright.sip.softphone.sip.auth;

import com.github.mikekwright.sip.softphone.sip.message.SipParseException;
import com.github.mikekwright.sip.softphone.sip.message.SipSyntax;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A parsed {@code WWW-Authenticate}, {@code Proxy-Authenticate}, {@code Authorization} or
 * {@code Proxy-Authorization} value (RFC 3261 §20.7, §20.27, §20.28, §20.44, §25.1): an auth scheme followed by
 * comma separated {@code name=value} parameters, e.g.
 * <pre>Digest realm="atlanta.com", nonce="84a4cc6f", qop="auth", algorithm=MD5</pre>
 *
 * @param scheme     the auth scheme, e.g. {@code Digest}
 * @param parameters parameter values with quotes removed; keys are lower-case
 */
public record AuthHeader(String scheme, Map<String, String> parameters) {

    /** Parameters that are written without quotes (RFC 7616 §3.4: algorithm, nc, qop in responses, stale). */
    private static final Set<String> UNQUOTED = Set.of("algorithm", "nc", "qop", "stale");

    public static AuthHeader parse(String value) {
        String s = value.trim();
        int sp = s.indexOf(' ');
        if (sp <= 0) {
            throw new SipParseException("malformed authentication header: " + value);
        }
        String scheme = s.substring(0, sp);
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : SipSyntax.splitList(s.substring(sp + 1))) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                    SipSyntax.unquote(part.substring(eq + 1).trim()));
        }
        return new AuthHeader(scheme, params);
    }

    public String get(String name) {
        return parameters.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isDigest() {
        return scheme.equalsIgnoreCase("Digest");
    }

    /** Formats the header value, quoting parameters as required by RFC 7616. */
    public String format(boolean response) {
        StringBuilder sb = new StringBuilder(scheme).append(' ');
        boolean first = true;
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            boolean unquoted = UNQUOTED.contains(e.getKey())
                    && !(e.getKey().equals("qop") && !response);
            sb.append(e.getKey()).append('=').append(unquoted ? e.getValue() : SipSyntax.quote(e.getValue()));
        }
        return sb.toString();
    }
}
