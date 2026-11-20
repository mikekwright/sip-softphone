package com.github.mikekwright.sip.softphone.rtp;

/**
 * Conversions for the 64-bit NTP timestamp format used in RTCP sender reports (RFC 3550 §4): seconds since
 * 1900-01-01 in the upper 32 bits and the binary fraction of a second in the lower 32 bits.
 */
public final class NtpTime {

    /** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
    public static final long EPOCH_OFFSET_SECONDS = 2_208_988_800L;

    private NtpTime() {
    }

    /** Converts milliseconds since the Unix epoch to a 64-bit NTP timestamp. */
    public static long fromEpochMillis(long epochMillis) {
        long seconds = Math.floorDiv(epochMillis, 1000L) + EPOCH_OFFSET_SECONDS;
        long millis = Math.floorMod(epochMillis, 1000L);
        long fraction = (millis << 32) / 1000L;
        return (seconds << 32) | fraction;
    }

    /** Converts a 64-bit NTP timestamp to milliseconds since the Unix epoch (rounded to nearest). */
    public static long toEpochMillis(long ntp) {
        long seconds = ntp >>> 32;
        long fraction = ntp & Uint32.MASK;
        return (seconds - EPOCH_OFFSET_SECONDS) * 1000L + ((fraction * 1000L + (1L << 31)) >>> 32);
    }

    /** The current time as NTP timestamp. */
    public static long now() {
        return fromEpochMillis(System.currentTimeMillis());
    }

    /** The middle 32 bits of an NTP timestamp ("compact" NTP), as used by the LSR field (RFC 3550 §6.4.1). */
    public static long middle32(long ntp) {
        return (ntp >>> 16) & Uint32.MASK;
    }
}
