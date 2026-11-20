package com.github.mikekwright.sip.softphone.media.video;

import java.io.ByteArrayOutputStream;

/**
 * VP8 depacketizer per RFC 7741 §4. Parses and skips the payload descriptor including the optional extension
 * octets (RFC 7741 §4.2):
 *
 * <pre>
 *       0 1 2 3 4 5 6 7
 *      +-+-+-+-+-+-+-+-+
 *      |X|R|N|S|R| PID | (REQUIRED)
 *      +-+-+-+-+-+-+-+-+
 * X:   |I|L|T|K| RSV   | (OPTIONAL)
 *      +-+-+-+-+-+-+-+-+
 * I:   |M| PictureID   | (OPTIONAL, 7 or 15 bits depending on M)
 *      +-+-+-+-+-+-+-+-+
 * L:   |   TL0PICIDX   | (OPTIONAL)
 *      +-+-+-+-+-+-+-+-+
 * T/K: |TID|Y| KEYIDX  | (OPTIONAL)
 *      +-+-+-+-+-+-+-+-+
 * </pre>
 *
 * A frame must begin with a packet having S=1 and PID=0, otherwise its start was lost and it is discarded. Key
 * frames are recognised from the VP8 payload header (RFC 7741 §4.3): the P bit (bit 0 of the first octet of the
 * frame) is 0 for key frames.
 *
 * <p>Not thread-safe.</p>
 */
public final class Vp8Depacketizer extends AbstractVideoDepacketizer {

    /** Creates a depacketizer for the 90 kHz RTP clock. */
    public Vp8Depacketizer() {
        super(Vp8Packetizer.ENCODING_NAME, 90_000);
    }

    @Override
    protected boolean depacketize(byte[] payload, boolean firstPacketOfFrame, ByteArrayOutputStream frame) {
        int offset = descriptorLength(payload);
        if (offset < 0) {
            return false;
        }
        boolean start = (payload[0] & 0x10) != 0;
        int partition = payload[0] & 0x07;
        if (firstPacketOfFrame && !(start && partition == 0)) {
            return false;
        }
        frame.write(payload, offset, payload.length - offset);
        return true;
    }

    /** Length of the payload descriptor, or -1 if the payload is too short. */
    static int descriptorLength(byte[] payload) {
        if (payload.length < 1) {
            return -1;
        }
        int offset = 1;
        if ((payload[0] & 0x80) != 0) {
            if (payload.length < 2) {
                return -1;
            }
            int extension = payload[1] & 0xFF;
            offset = 2;
            if ((extension & 0x80) != 0) { // I: PictureID present
                if (payload.length <= offset) {
                    return -1;
                }
                offset += (payload[offset] & 0x80) != 0 ? 2 : 1;
            }
            if ((extension & 0x40) != 0) { // L: TL0PICIDX present
                offset++;
            }
            if ((extension & 0x30) != 0) { // T or K: TID/Y/KEYIDX octet present
                offset++;
            }
        }
        return offset <= payload.length ? offset : -1;
    }

    @Override
    protected boolean isKeyFrame(byte[] frameData) {
        return frameData.length > 0 && (frameData[0] & 0x01) == 0;
    }
}
