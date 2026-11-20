package com.github.mikekwright.sip.softphone.media.audio;

/**
 * ITU-T G.711 companding tables. The conversion functions follow the reference implementation distributed with
 * ITU-T G.191 / the classic Sun Microsystems {@code g711.c}, which is bit exact with the G.711 tables: μ-law
 * operates on the 14 most significant bits with a bias of 33 and A-law on the 13 most significant bits, both with
 * even-bit inversion as specified (A-law XOR 0x55, μ-law complement).
 *
 * <p>Encoding uses a 64 K entry table indexed by the 16-bit sample, decoding a 256 entry table. Thread-safe.</p>
 */
final class G711 {

    private static final int SIGN_BIT = 0x80;
    private static final int QUANT_MASK = 0x0F;
    private static final int SEG_SHIFT = 4;
    private static final int SEG_MASK = 0x70;
    private static final int ULAW_BIAS = 0x84;
    private static final int ULAW_CLIP = 8159;

    private static final int[] SEG_ULAW_END = {0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF};
    private static final int[] SEG_ALAW_END = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};

    private static final byte[] LINEAR_TO_ULAW = new byte[65536];
    private static final byte[] LINEAR_TO_ALAW = new byte[65536];
    private static final short[] ULAW_TO_LINEAR = new short[256];
    private static final short[] ALAW_TO_LINEAR = new short[256];

    static {
        for (int i = 0; i < 65536; i++) {
            short pcm = (short) i;
            LINEAR_TO_ULAW[i] = (byte) computeLinearToUlaw(pcm);
            LINEAR_TO_ALAW[i] = (byte) computeLinearToAlaw(pcm);
        }
        for (int i = 0; i < 256; i++) {
            ULAW_TO_LINEAR[i] = (short) computeUlawToLinear(i);
            ALAW_TO_LINEAR[i] = (short) computeAlawToLinear(i);
        }
    }

    private G711() {
    }

    static int linearToUlaw(short pcm) {
        return LINEAR_TO_ULAW[pcm & 0xFFFF] & 0xFF;
    }

    static int linearToAlaw(short pcm) {
        return LINEAR_TO_ALAW[pcm & 0xFFFF] & 0xFF;
    }

    static short ulawToLinear(int ulaw) {
        return ULAW_TO_LINEAR[ulaw & 0xFF];
    }

    static short alawToLinear(int alaw) {
        return ALAW_TO_LINEAR[alaw & 0xFF];
    }

    private static int segment(int value, int[] table) {
        for (int i = 0; i < table.length; i++) {
            if (value <= table[i]) {
                return i;
            }
        }
        return table.length;
    }

    private static int computeLinearToUlaw(int pcm) {
        int value = pcm >> 2; // 14-bit magnitude
        int mask;
        if (value < 0) {
            value = -value;
            mask = 0x7F;
        } else {
            mask = 0xFF;
        }
        if (value > ULAW_CLIP) {
            value = ULAW_CLIP;
        }
        value += ULAW_BIAS >> 2;
        int seg = segment(value, SEG_ULAW_END);
        if (seg >= 8) {
            return 0x7F ^ mask;
        }
        int uval = (seg << 4) | ((value >> (seg + 1)) & 0xF);
        return uval ^ mask;
    }

    private static int computeUlawToLinear(int ulaw) {
        int u = ~ulaw & 0xFF;
        int t = ((u & QUANT_MASK) << 3) + ULAW_BIAS;
        t <<= (u & SEG_MASK) >> SEG_SHIFT;
        return (u & SIGN_BIT) != 0 ? ULAW_BIAS - t : t - ULAW_BIAS;
    }

    private static int computeLinearToAlaw(int pcm) {
        int value = pcm >> 3; // 13-bit magnitude
        int mask;
        if (value >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            value = -value - 1;
        }
        int seg = segment(value, SEG_ALAW_END);
        if (seg >= 8) {
            return 0x7F ^ mask;
        }
        int aval = seg << SEG_SHIFT;
        aval |= seg < 2 ? (value >> 1) & QUANT_MASK : (value >> seg) & QUANT_MASK;
        return aval ^ mask;
    }

    private static int computeAlawToLinear(int alaw) {
        int a = (alaw ^ 0x55) & 0xFF;
        int t = (a & QUANT_MASK) << 4;
        int seg = (a & SEG_MASK) >> SEG_SHIFT;
        switch (seg) {
            case 0 -> t += 8;
            case 1 -> t += 0x108;
            default -> {
                t += 0x108;
                t <<= seg - 1;
            }
        }
        return (a & SIGN_BIT) != 0 ? t : -t;
    }
}
