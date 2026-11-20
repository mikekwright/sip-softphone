package com.github.mikekwright.sip.softphone.media.audio;

/**
 * Uncompressed 16-bit linear PCM in network byte order (L16), RFC 3551 §4.5.11, mono. Payload type 11 is
 * statically assigned for 44100 Hz mono; any other rate uses a dynamic payload type.
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class L16Codec implements AudioCodec {

    /** The encoding name used in SDP. */
    public static final String ENCODING_NAME = "L16";

    private final int sampleRate;

    /** @param sampleRate the sample (and RTP clock) rate in Hz */
    public L16Codec(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sample rate must be positive");
        }
        this.sampleRate = sampleRate;
    }

    @Override
    public String encodingName() {
        return ENCODING_NAME;
    }

    @Override
    public int clockRate() {
        return sampleRate;
    }

    @Override
    public int defaultPayloadType() {
        return sampleRate == 44_100 ? 11 : -1;
    }

    @Override
    public byte[] encode(short[] pcm) {
        byte[] out = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            out[2 * i] = (byte) (pcm[i] >> 8);
            out[2 * i + 1] = (byte) pcm[i];
        }
        return out;
    }

    @Override
    public short[] decode(byte[] payload) {
        short[] out = new short[payload.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((payload[2 * i] << 8) | (payload[2 * i + 1] & 0xFF));
        }
        return out;
    }
}
