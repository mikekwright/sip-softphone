package com.github.mikekwright.sip.softphone.media.video;

import java.util.ArrayList;
import java.util.List;

/**
 * VP8 packetizer per RFC 7741 §4. Each payload starts with the minimal one-octet payload descriptor
 * (RFC 7741 §4.2):
 *
 * <pre>
 *       0 1 2 3 4 5 6 7
 *      +-+-+-+-+-+-+-+-+
 *      |X|R|N|S|R| PID | (REQUIRED)
 *      +-+-+-+-+-+-+-+-+
 * </pre>
 *
 * with X=0 (no extensions), N=0, PID=0 (the whole frame is treated as partition 0) and S=1 only on the first
 * packet of the frame. The frame is split into equally sized chunks of at most {@code maxPayloadSize - 1} bytes.
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class Vp8Packetizer implements VideoPacketizer {

    /** The RTP encoding name. */
    public static final String ENCODING_NAME = "VP8";

    @Override
    public String encodingName() {
        return ENCODING_NAME;
    }

    @Override
    public List<byte[]> packetize(EncodedVideoFrame frame, int maxPayloadSize) {
        if (maxPayloadSize < 2) {
            throw new IllegalArgumentException("maxPayloadSize too small: " + maxPayloadSize);
        }
        byte[] data = frame.data();
        int chunk = maxPayloadSize - 1;
        int count = Math.max(1, (data.length + chunk - 1) / chunk);
        List<byte[]> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int offset = i * chunk;
            int length = Math.min(chunk, data.length - offset);
            byte[] payload = new byte[1 + length];
            payload[0] = (byte) (i == 0 ? 0x10 : 0x00);
            System.arraycopy(data, offset, payload, 1, length);
            payloads.add(payload);
        }
        return payloads;
    }
}
