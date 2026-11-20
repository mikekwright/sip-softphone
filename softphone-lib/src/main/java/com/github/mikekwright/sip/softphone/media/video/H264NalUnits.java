package com.github.mikekwright.sip.softphone.media.video;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helpers for H.264 NAL units and the Annex B byte stream format (ITU-T H.264 Annex B) used by
 * {@link EncodedVideoFrame} for H.264 data. Stateless and thread-safe.
 */
public final class H264NalUnits {

    /** Coded slice of a non-IDR picture. */
    public static final int TYPE_NON_IDR = 1;
    /** Coded slice of an IDR picture (key frame). */
    public static final int TYPE_IDR = 5;
    /** Supplemental enhancement information. */
    public static final int TYPE_SEI = 6;
    /** Sequence parameter set. */
    public static final int TYPE_SPS = 7;
    /** Picture parameter set. */
    public static final int TYPE_PPS = 8;
    /** Single-time aggregation packet (RFC 6184 §5.7.1). */
    public static final int TYPE_STAP_A = 24;
    /** Fragmentation unit A (RFC 6184 §5.8). */
    public static final int TYPE_FU_A = 28;

    private static final byte[] START_CODE = {0, 0, 0, 1};

    private H264NalUnits() {
    }

    /**
     * Splits an Annex B byte stream into NAL units (without start codes). Both 3-byte ({@code 00 00 01}) and
     * 4-byte ({@code 00 00 00 01}) start codes are recognised; trailing zero bytes before a start code are
     * dropped. Data without any start code is returned as a single NAL unit.
     */
    public static List<byte[]> split(byte[] annexB) {
        List<int[]> starts = new ArrayList<>(); // {start code offset, NAL offset}
        int i = 0;
        while (i + 2 < annexB.length) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                starts.add(new int[]{i, i + 3});
                i += 3;
            } else {
                i++;
            }
        }
        List<byte[]> nals = new ArrayList<>(starts.size());
        if (starts.isEmpty()) {
            if (annexB.length > 0) {
                nals.add(annexB.clone());
            }
            return nals;
        }
        for (int k = 0; k < starts.size(); k++) {
            int begin = starts.get(k)[1];
            int end = k + 1 < starts.size() ? starts.get(k + 1)[0] : annexB.length;
            while (end > begin && annexB[end - 1] == 0) {
                end--;
            }
            if (end > begin) {
                nals.add(Arrays.copyOfRange(annexB, begin, end));
            }
        }
        return nals;
    }

    /** Joins NAL units into an Annex B byte stream with 4-byte start codes. */
    public static byte[] toAnnexB(List<byte[]> nals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            out.writeBytes(START_CODE);
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    /** The nal_unit_type (low 5 bits of the NAL header). */
    public static int type(byte[] nal) {
        return nal[0] & 0x1F;
    }

    /** Whether the Annex B stream contains an IDR slice. */
    public static boolean containsIdr(byte[] annexB) {
        for (byte[] nal : split(annexB)) {
            if (nal.length > 0 && type(nal) == TYPE_IDR) {
                return true;
            }
        }
        return false;
    }

    static void writeStartCode(ByteArrayOutputStream out) {
        out.writeBytes(START_CODE);
    }
}
