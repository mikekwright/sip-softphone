package com.github.mikekwright.sip.softphone.media.video;

import java.io.ByteArrayOutputStream;

/**
 * H.264 depacketizer per RFC 6184 for packetization-modes 0 and 1. Handles:
 * <ul>
 *   <li>single NAL unit packets, types 1-23 (§5.6);</li>
 *   <li>STAP-A aggregation packets, type 24 (§5.7.1);</li>
 *   <li>FU-A fragmentation units, type 28 (§5.8), rebuilding the NAL header from the FU indicator's F/NRI bits
 *       and the FU header's type.</li>
 * </ul>
 * Interleaved-mode types (STAP-B, MTAP, FU-B) invalidate the frame; undefined types 0, 30 and 31 are ignored
 * as RFC 6184 §5.4 requires. Output frames are Annex B byte streams with 4-byte start codes. A frame is a key
 * frame if it contains an IDR slice (NAL type 5). A frame whose FU-A was not terminated by an E bit, or that
 * continues a fragment whose start was lost, is discarded.
 *
 * <p>Not thread-safe.</p>
 */
public final class H264Depacketizer extends AbstractVideoDepacketizer {

    private boolean fragmentInProgress;

    /** Creates a depacketizer for the 90 kHz RTP clock. */
    public H264Depacketizer() {
        super(H264Packetizer.ENCODING_NAME, 90_000);
    }

    @Override
    protected void startFrame() {
        fragmentInProgress = false;
    }

    @Override
    protected boolean endFrame() {
        return !fragmentInProgress;
    }

    @Override
    protected boolean depacketize(byte[] payload, boolean firstPacketOfFrame, ByteArrayOutputStream frame) {
        if (payload.length < 1) {
            return false;
        }
        int type = payload[0] & 0x1F;
        if (type >= 1 && type <= 23) {
            if (fragmentInProgress) {
                return false;
            }
            H264NalUnits.writeStartCode(frame);
            frame.write(payload, 0, payload.length);
            return true;
        }
        return switch (type) {
            case H264NalUnits.TYPE_STAP_A -> !fragmentInProgress && depacketizeStapA(payload, frame);
            case H264NalUnits.TYPE_FU_A -> depacketizeFuA(payload, frame);
            case 0, 30, 31 -> true;  // undefined: ignore
            default -> false;        // STAP-B, MTAP16/24, FU-B: not valid in modes 0/1
        };
    }

    private static boolean depacketizeStapA(byte[] payload, ByteArrayOutputStream frame) {
        int offset = 1;
        while (offset < payload.length) {
            if (offset + 2 > payload.length) {
                return false;
            }
            int size = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            offset += 2;
            if (size == 0 || offset + size > payload.length) {
                return false;
            }
            H264NalUnits.writeStartCode(frame);
            frame.write(payload, offset, size);
            offset += size;
        }
        return true;
    }

    private boolean depacketizeFuA(byte[] payload, ByteArrayOutputStream frame) {
        if (payload.length < 2) {
            return false;
        }
        int indicator = payload[0] & 0xFF;
        int header = payload[1] & 0xFF;
        boolean start = (header & 0x80) != 0;
        boolean end = (header & 0x40) != 0;
        if (start) {
            if (fragmentInProgress) {
                return false;
            }
            H264NalUnits.writeStartCode(frame);
            frame.write((indicator & 0xE0) | (header & 0x1F));
            fragmentInProgress = !end;
        } else {
            if (!fragmentInProgress) {
                return false; // start of this NAL unit was lost
            }
            if (end) {
                fragmentInProgress = false;
            }
        }
        frame.write(payload, 2, payload.length - 2);
        return true;
    }

    @Override
    protected boolean isKeyFrame(byte[] frameData) {
        return H264NalUnits.containsIdr(frameData);
    }
}
