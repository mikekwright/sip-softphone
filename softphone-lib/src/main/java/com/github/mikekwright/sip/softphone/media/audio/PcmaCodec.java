package com.github.mikekwright.sip.softphone.media.audio;

/**
 * G.711 A-law (PCMA), ITU-T Recommendation G.711, RTP payload format per RFC 3551 §4.5.14: static payload type 8,
 * 8000 Hz, one octet per sample. Table driven and bit exact with the ITU-T reference implementation
 * (e.g. linear 0 encodes to {@code 0xD5}).
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class PcmaCodec implements AudioCodec {

    /** The encoding name used in SDP. */
    public static final String ENCODING_NAME = "PCMA";
    /** The static payload type (RFC 3551 §6). */
    public static final int PAYLOAD_TYPE = 8;

    @Override
    public String encodingName() {
        return ENCODING_NAME;
    }

    @Override
    public int clockRate() {
        return 8000;
    }

    @Override
    public int defaultPayloadType() {
        return PAYLOAD_TYPE;
    }

    @Override
    public byte[] encode(short[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = (byte) G711.linearToAlaw(pcm[i]);
        }
        return out;
    }

    @Override
    public short[] decode(byte[] payload) {
        short[] out = new short[payload.length];
        for (int i = 0; i < payload.length; i++) {
            out[i] = G711.alawToLinear(payload[i]);
        }
        return out;
    }

    /** Encodes a single sample. */
    public static int encodeSample(short pcm) {
        return G711.linearToAlaw(pcm);
    }

    /** Decodes a single A-law octet (0..255). */
    public static short decodeSample(int alaw) {
        return G711.alawToLinear(alaw);
    }
}
