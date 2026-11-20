package com.github.mikekwright.sip.softphone.sip.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digest algorithms for SIP authentication: MD5 (RFC 2617, RFC 3261 §22.4) and SHA-256 / SHA-512-256 (RFC 7616,
 * adopted for SIP by RFC 8760), each with its {@code -sess} variant.
 */
public enum DigestAlgorithm {
    MD5("MD5", "MD5", false),
    MD5_SESS("MD5-sess", "MD5", true),
    SHA_256("SHA-256", "SHA-256", false),
    SHA_256_SESS("SHA-256-sess", "SHA-256", true),
    SHA_512_256("SHA-512-256", "SHA-512/256", false),
    SHA_512_256_SESS("SHA-512-256-sess", "SHA-512/256", true);

    private final String token;
    private final String jcaName;
    private final boolean session;

    DigestAlgorithm(String token, String jcaName, boolean session) {
        this.token = token;
        this.jcaName = jcaName;
        this.session = session;
    }

    /** The value of the {@code algorithm} parameter. */
    public String token() {
        return token;
    }

    public boolean isSession() {
        return session;
    }

    /** Hex encoded hash of the UTF-8 bytes of {@code data} (the H() function of RFC 7616 §3.4.1). */
    public String hash(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance(jcaName);
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks " + jcaName, e);
        }
    }

    public String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(jcaName).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks " + jcaName, e);
        }
    }

    /** Resolves the {@code algorithm} parameter; absent means MD5 (RFC 2617 §3.2.1). Returns null if unsupported. */
    public static DigestAlgorithm fromToken(String token) {
        if (token == null || token.isBlank()) {
            return MD5;
        }
        for (DigestAlgorithm a : values()) {
            if (a.token.equalsIgnoreCase(token.trim())) {
                return a;
            }
        }
        return null;
    }
}
