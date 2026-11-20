package com.github.mikekwright.sip.softphone.sip.auth;

import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Server side digest authentication (RFC 3261 §22.1, §22.4): issues challenges and verifies credentials. Useful to
 * build test registrars and to authenticate requests arriving at the softphone.
 */
public final class DigestServer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String realm;
    private final DigestAlgorithm algorithm;
    private final Function<String, String> passwords;
    private final Set<String> issuedNonces = ConcurrentHashMap.newKeySet();

    /**
     * @param realm     protection realm advertised in challenges
     * @param algorithm digest algorithm to require
     * @param passwords maps a username to its password, returning {@code null} for unknown users
     */
    public DigestServer(String realm, DigestAlgorithm algorithm, Function<String, String> passwords) {
        this.realm = realm;
        this.algorithm = algorithm;
        this.passwords = passwords;
    }

    public String realm() {
        return realm;
    }

    /** Adds a {@code WWW-Authenticate} (401) or {@code Proxy-Authenticate} (407) challenge to the response. */
    public SipResponse challenge(SipResponse response, boolean stale) {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        String nonce = HexFormat.of().formatHex(b);
        issuedNonces.add(nonce);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("realm", realm);
        p.put("nonce", nonce);
        p.put("algorithm", algorithm.token());
        p.put("qop", "auth");
        if (stale) {
            p.put("stale", "true");
        }
        String header = response.statusCode() == 407 ? HeaderNames.PROXY_AUTHENTICATE : HeaderNames.WWW_AUTHENTICATE;
        response.headers().add(header, new AuthHeader("Digest", p).format(false));
        return response;
    }

    /** Result of verifying a request's credentials. */
    public enum Result { OK, MISSING, STALE, FAILED }

    /** Verifies the {@code Authorization} (or {@code Proxy-Authorization}) header of a request. */
    public Result verify(SipRequest request, boolean proxy) {
        String header = request.header(proxy ? HeaderNames.PROXY_AUTHORIZATION : HeaderNames.AUTHORIZATION);
        if (header == null) {
            return Result.MISSING;
        }
        AuthHeader auth = AuthHeader.parse(header);
        if (!auth.isDigest() || !realm.equals(auth.get("realm"))) {
            return Result.FAILED;
        }
        if (!issuedNonces.contains(auth.get("nonce"))) {
            return Result.STALE;
        }
        String password = passwords.apply(auth.get("username"));
        DigestAlgorithm alg = DigestAlgorithm.fromToken(auth.get("algorithm"));
        if (password == null || alg == null) {
            return Result.FAILED;
        }
        String expected = DigestAuthenticator.computeResponse(alg, auth.get("username"), realm, password,
                auth.get("nonce"), auth.get("cnonce"), auth.get("nc"), auth.get("qop"), request.method().name(),
                auth.get("uri"), request.body());
        return expected.equalsIgnoreCase(auth.get("response")) ? Result.OK : Result.FAILED;
    }
}
