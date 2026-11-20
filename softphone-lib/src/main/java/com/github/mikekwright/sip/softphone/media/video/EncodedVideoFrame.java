package com.github.mikekwright.sip.softphone.media.video;

import java.util.Arrays;
import java.util.Objects;

/**
 * One encoded (compressed) video frame as produced by an encoder or reassembled by a {@link VideoDepacketizer}.
 * The library does not encode or decode video; applications plug in their codec and exchange these frames.
 *
 * <p>Frame data formats: for {@code VP8} the raw VP8 frame (RFC 6386 bitstream, starting with the frame tag);
 * for {@code H264} an Annex B byte stream (NAL units separated by 3- or 4-byte start codes).</p>
 *
 * <p>The data array is not copied for performance reasons; treat it as immutable after construction.</p>
 *
 * @param data            encoded frame data
 * @param keyFrame        whether the frame is a key frame / decoder refresh point (VP8 key frame, H.264 IDR)
 * @param timestampMicros presentation timestamp in microseconds. On the send side it drives the RTP timestamp;
 *                        on the receive side it is relative to the first received frame
 * @param codec           RTP encoding name, e.g. {@code VP8} or {@code H264}
 */
public record EncodedVideoFrame(byte[] data, boolean keyFrame, long timestampMicros, String codec) {

    public EncodedVideoFrame {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(codec, "codec");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EncodedVideoFrame f && f.keyFrame == keyFrame && f.timestampMicros == timestampMicros
                && f.codec.equals(codec) && Arrays.equals(f.data, data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyFrame, timestampMicros, codec) * 31 + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "EncodedVideoFrame[" + codec + ", " + data.length + "B" + (keyFrame ? ", key" : "")
                + ", t=" + timestampMicros + "us]";
    }
}
