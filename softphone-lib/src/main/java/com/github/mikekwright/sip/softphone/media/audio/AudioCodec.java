package com.github.mikekwright.sip.softphone.media.audio;

/**
 * An audio encoder/decoder pair for one RTP payload format (RFC 3551 §4.5 lists the classic ones).
 *
 * <p>The PCM side is always 16-bit signed mono at {@link #sampleRate()}. The RTP side advances the timestamp at
 * {@link #clockRate()}, which usually equals the sample rate (a notable exception is G.722, whose RTP clock rate
 * is 8000 Hz for 16 kHz audio, RFC 3551 §4.5.2).</p>
 *
 * <p>Thread-safety: implementations may keep state (e.g. predictive codecs); a codec instance must therefore be
 * used for one direction of one stream only, which is how the media streams use them — create new instances via
 * {@link AudioCodecRegistry#create}. Stateless implementations such as {@link PcmuCodec} are thread-safe.</p>
 */
public interface AudioCodec {

    /** The RTP encoding name as used in SDP {@code a=rtpmap}, e.g. {@code PCMU}. */
    String encodingName();

    /** The RTP clock rate in Hz. */
    int clockRate();

    /** The PCM sample rate in Hz consumed by {@link #encode} and produced by {@link #decode}. */
    default int sampleRate() {
        return clockRate();
    }

    /** Number of channels of the encoded format (the PCM side is always mono). */
    default int channels() {
        return 1;
    }

    /** The static payload type assigned by RFC 3551 §6, or -1 if the format uses a dynamic payload type. */
    int defaultPayloadType();

    /** Encodes one frame of PCM samples into an RTP payload. */
    byte[] encode(short[] pcm);

    /** Decodes an RTP payload into PCM samples. */
    short[] decode(byte[] payload);

    /** Number of PCM samples in a frame of {@code ptimeMillis} milliseconds. */
    default int samplesPerFrame(int ptimeMillis) {
        return sampleRate() * ptimeMillis / 1000;
    }

    /** RTP timestamp increment for a frame of {@code ptimeMillis} milliseconds. */
    default int timestampIncrement(int ptimeMillis) {
        return clockRate() * ptimeMillis / 1000;
    }
}
