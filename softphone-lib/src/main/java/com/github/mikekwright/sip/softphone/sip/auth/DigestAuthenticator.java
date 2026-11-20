package com.github.mikekwright.sip.softphone.sip.auth;

import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Client side HTTP Digest authentication for SIP (RFC 3261 §22, RFC 2617, RFC 7616, RFC 8760).
 *
 * <p>Given a 401/407 response and the request that triggered it, {@link #authorize} adds the matching
 * {@code Authorization} / {@code Proxy-Authorization} headers to a new copy of the request. Supports
 * {@code qop=auth} and {@code qop=auth-int}, the legacy no-qop mode, all {@link DigestAlgorithm algorithms}, and
 * keeps nonce counts per nonce so that cached challenges can be reused for subsequent requests.</p>
 *
 * <p>Not thread-safe; used from the SIP core executor.</p>
 */
public final class DigestAuthenticator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredentialsProvider credentials;
    private final Map<String, Integer> nonceCounts = new HashMap<>();

    public DigestAuthenticator(CredentialsProvider credentials) {
        this.credentials = credentials;
    }

    /**
     * Adds authorization headers answering every supported challenge in {@code challenge} to {@code request}.
     * Existing authorization headers for the same realms are replaced.
     *
     * @return {@code true} if at least one challenge was answered; {@code false} if no credentials or no supported
     * challenge was found (the caller should then give up)
     */
    public boolean authorize(SipRequest request, SipResponse challenge) {
        boolean proxy = challenge.statusCode() == 407;
        String challengeHeader = proxy ? HeaderNames.PROXY_AUTHENTICATE : HeaderNames.WWW_AUTHENTICATE;
        String authHeader = proxy ? HeaderNames.PROXY_AUTHORIZATION : HeaderNames.AUTHORIZATION;
        // Prefer the strongest algorithm per realm (RFC 8760 §2.4: process in order of preference)
        Map<String, AuthHeader> byRealm = new LinkedHashMap<>();
        for (String value : challenge.headers().all(challengeHeader)) {
            AuthHeader h;
            try {
                h = AuthHeader.parse(value);
            } catch (RuntimeException e) {
                continue;
            }
            DigestAlgorithm alg = DigestAlgorithm.fromToken(h.get("algorithm"));
            if (!h.isDigest() || alg == null) {
                continue;
            }
            String realm = h.get("realm") == null ? "" : h.get("realm");
            AuthHeader current = byRealm.get(realm);
            if (current == null || strength(alg) > strength(DigestAlgorithm.fromToken(current.get("algorithm")))) {
                byRealm.put(realm, h);
            }
        }
        List<String> answers = new ArrayList<>();
        for (Map.Entry<String, AuthHeader> e : byRealm.entrySet()) {
            Optional<Credentials> creds = credentials.credentialsFor(e.getKey());
            creds.ifPresent(c -> answers.add(respond(request, e.getValue(), c).format(true)));
        }
        if (answers.isEmpty()) {
            return false;
        }
        request.headers().remove(authHeader);
        answers.forEach(a -> request.headers().add(authHeader, a));
        return true;
    }

    private static int strength(DigestAlgorithm a) {
        return switch (a) {
            case MD5, MD5_SESS -> 1;
            case SHA_256, SHA_256_SESS -> 2;
            case SHA_512_256, SHA_512_256_SESS -> 3;
        };
    }

    /** Computes the credentials for one challenge (RFC 7616 §3.4). */
    public AuthHeader respond(SipRequest request, AuthHeader challenge, Credentials creds) {
        DigestAlgorithm alg = DigestAlgorithm.fromToken(challenge.get("algorithm"));
        String realm = challenge.get("realm") == null ? "" : challenge.get("realm");
        String nonce = challenge.get("nonce") == null ? "" : challenge.get("nonce");
        String opaque = challenge.get("opaque");
        String uri = request.requestUri().toString();
        String qop = selectQop(challenge.get("qop"), request);
        String cnonce = null;
        String nc = null;
        if (qop != null || alg.isSession()) {
            byte[] b = new byte[8];
            RANDOM.nextBytes(b);
            cnonce = HexFormat.of().formatHex(b);
        }
        if (qop != null) {
            int count = nonceCounts.merge(nonce, 1, Integer::sum);
            nc = String.format(Locale.ROOT, "%08x", count);
        }
        String response = computeResponse(alg, creds.username(), realm, creds.password(), nonce, cnonce, nc, qop,
                request.method().name(), uri, request.body());

        Map<String, String> p = new LinkedHashMap<>();
        p.put("username", creds.username());
        p.put("realm", realm);
        p.put("nonce", nonce);
        p.put("uri", uri);
        p.put("response", response);
        p.put("algorithm", alg.token());
        if (cnonce != null) {
            p.put("cnonce", cnonce);
        }
        if (opaque != null) {
            p.put("opaque", opaque);
        }
        if (qop != null) {
            p.put("qop", qop);
            p.put("nc", nc);
        }
        return new AuthHeader("Digest", p);
    }

    private static String selectQop(String offered, SipRequest request) {
        if (offered == null) {
            return null;
        }
        List<String> options = new ArrayList<>();
        for (String o : offered.split(",")) {
            options.add(o.trim().toLowerCase(Locale.ROOT));
        }
        if (options.contains("auth")) {
            return "auth";
        }
        if (options.contains("auth-int")) {
            return "auth-int";
        }
        return null;
    }

    /**
     * The digest {@code response} value (RFC 2617 §3.2.2.1, RFC 7616 §3.4.1 - §3.4.3). Shared with
     * {@link DigestServer}.
     */
    public static String computeResponse(DigestAlgorithm alg, String username, String realm, String password,
                                         String nonce, String cnonce, String nc, String qop, String method,
                                         String uri, byte[] body) {
        String ha1 = alg.hash(username + ":" + realm + ":" + password);
        if (alg.isSession()) {
            ha1 = alg.hash(ha1 + ":" + nonce + ":" + cnonce);
        }
        String a2 = "auth-int".equals(qop)
                ? method + ":" + uri + ":" + alg.hash(body == null ? new byte[0] : body)
                : method + ":" + uri;
        String ha2 = alg.hash(a2);
        if (qop == null) {
            return alg.hash(ha1 + ":" + nonce + ":" + ha2);
        }
        return alg.hash(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
    }
}
