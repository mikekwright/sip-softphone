package com.github.mikekwright.sip.softphone.rtp;

/** Helpers for unsigned 32-bit values carried in {@code long}s. */
final class Uint32 {

    static final long MASK = 0xFFFFFFFFL;

    private Uint32() {
    }

    static void check(long value, String name) {
        if (value < 0 || value > MASK) {
            throw new IllegalArgumentException(name + " out of uint32 range: " + value);
        }
    }
}
