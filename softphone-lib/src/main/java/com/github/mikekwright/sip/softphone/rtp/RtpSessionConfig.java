package com.github.mikekwright.sip.softphone.rtp;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Configuration of an {@link RtpSession}.
 *
 * @param cname              the RTCP canonical name (SDES CNAME, RFC 3550 §6.5.1); by default a random
 *                           identifier as recommended by RFC 7022
 * @param rtcpIntervalMillis interval between periodic RTCP reports. RFC 3550 §6.2 recommends 5 seconds as the
 *                           minimum for the simple fixed interval used here
 * @param symmetricLatching  initial value of {@link RtpSession#setSymmetricLatching(boolean)}
 * @param maxBindAttempts    how many port pairs {@link RtpSession#bind} tries before giving up
 * @param defaultClockRate   clock rate assumed for payload types without a configured rate when computing jitter
 *                           and sender-report timestamps
 */
public record RtpSessionConfig(String cname, long rtcpIntervalMillis, boolean symmetricLatching,
                               int maxBindAttempts, int defaultClockRate) {

    /** Default RTCP report interval (RFC 3550 §6.2). */
    public static final long DEFAULT_RTCP_INTERVAL_MILLIS = 5_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    public RtpSessionConfig {
        Objects.requireNonNull(cname, "cname");
        if (cname.isEmpty() || cname.length() > 255) {
            throw new IllegalArgumentException("cname must have 1..255 characters");
        }
        if (rtcpIntervalMillis <= 0) {
            throw new IllegalArgumentException("rtcpIntervalMillis must be positive");
        }
        if (maxBindAttempts < 1) {
            throw new IllegalArgumentException("maxBindAttempts must be positive");
        }
        if (defaultClockRate <= 0) {
            throw new IllegalArgumentException("defaultClockRate must be positive");
        }
    }

    /** Defaults: random CNAME, 5 s RTCP interval, latching off, 50 bind attempts, 8 kHz default clock. */
    public static RtpSessionConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().cname(cname).rtcpIntervalMillis(rtcpIntervalMillis)
                .symmetricLatching(symmetricLatching).maxBindAttempts(maxBindAttempts)
                .defaultClockRate(defaultClockRate);
    }

    /** A random CNAME per RFC 7022 §4.1 (96 random bits, base64 encoded). */
    public static String randomCname() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Builder for {@link RtpSessionConfig}. Not thread-safe. */
    public static final class Builder {
        private String cname;
        private long rtcpIntervalMillis = DEFAULT_RTCP_INTERVAL_MILLIS;
        private boolean symmetricLatching;
        private int maxBindAttempts = 50;
        private int defaultClockRate = 8000;

        private Builder() {
        }

        public Builder cname(String cname) {
            this.cname = cname;
            return this;
        }

        public Builder rtcpIntervalMillis(long rtcpIntervalMillis) {
            this.rtcpIntervalMillis = rtcpIntervalMillis;
            return this;
        }

        public Builder symmetricLatching(boolean symmetricLatching) {
            this.symmetricLatching = symmetricLatching;
            return this;
        }

        public Builder maxBindAttempts(int maxBindAttempts) {
            this.maxBindAttempts = maxBindAttempts;
            return this;
        }

        public Builder defaultClockRate(int defaultClockRate) {
            this.defaultClockRate = defaultClockRate;
            return this;
        }

        public RtpSessionConfig build() {
            return new RtpSessionConfig(cname != null ? cname : randomCname(), rtcpIntervalMillis,
                    symmetricLatching, maxBindAttempts, defaultClockRate);
        }
    }
}
