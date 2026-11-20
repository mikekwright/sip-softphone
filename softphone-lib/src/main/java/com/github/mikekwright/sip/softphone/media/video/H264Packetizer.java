package com.github.mikekwright.sip.softphone.media.video;

import java.util.ArrayList;
import java.util.List;

/**
 * H.264 packetizer per RFC 6184 in packetization-mode 1 (non-interleaved, §6.3).
 *
 * <p>The Annex B frame is split into NAL units; each NAL unit that fits into {@code maxPayloadSize} is sent as a
 * single NAL unit packet (§5.6), larger ones are fragmented into FU-A packets (§5.8):</p>
 *
 * <pre>
 * FU indicator: |F|NRI| Type=28 |    FU header: |S|E|R| Type  |
 * </pre>
 *
 * where the indicator takes F and NRI from the original NAL header and the FU header carries the original type,
 * with S set on the first and E on the last fragment. No aggregation (STAP-A) is performed when sending.
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class H264Packetizer implements VideoPacketizer {

    /** The RTP encoding name. */
    public static final String ENCODING_NAME = "H264";

    @Override
    public String encodingName() {
        return ENCODING_NAME;
    }

    @Override
    public List<byte[]> packetize(EncodedVideoFrame frame, int maxPayloadSize) {
        if (maxPayloadSize < 3) {
            throw new IllegalArgumentException("maxPayloadSize too small: " + maxPayloadSize);
        }
        List<byte[]> payloads = new ArrayList<>();
        for (byte[] nal : H264NalUnits.split(frame.data())) {
            if (nal.length <= maxPayloadSize) {
                payloads.add(nal);
                continue;
            }
            int indicator = (nal[0] & 0xE0) | H264NalUnits.TYPE_FU_A;
            int type = nal[0] & 0x1F;
            int chunk = maxPayloadSize - 2;
            for (int offset = 1; offset < nal.length; offset += chunk) {
                int length = Math.min(chunk, nal.length - offset);
                boolean start = offset == 1;
                boolean end = offset + length == nal.length;
                byte[] payload = new byte[2 + length];
                payload[0] = (byte) indicator;
                payload[1] = (byte) ((start ? 0x80 : 0) | (end ? 0x40 : 0) | type);
                System.arraycopy(nal, offset, payload, 2, length);
                payloads.add(payload);
            }
        }
        if (payloads.isEmpty()) {
            throw new IllegalArgumentException("H.264 frame contains no NAL units");
        }
        return payloads;
    }
}
