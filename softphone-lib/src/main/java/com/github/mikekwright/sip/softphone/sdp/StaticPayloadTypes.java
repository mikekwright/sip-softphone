package com.github.mikekwright.sip.softphone.sdp;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The statically assigned RTP payload types of the RTP/AVP profile, RFC 3551 §6 (Tables 4 and 5).
 *
 * <p>For these numbers an {@code a=rtpmap} attribute is optional (RFC 8866 §6.6), so a description containing
 * {@code m=audio 49170 RTP/AVP 0} implies {@code PCMU/8000}. Payload types 96-127 are dynamic and must always be
 * bound with {@code a=rtpmap}.</p>
 */
public final class StaticPayloadTypes {

    /** First dynamic payload type number (RFC 3551 §6). */
    public static final int DYNAMIC_MIN = 96;
    /** Last dynamic payload type number (RFC 3551 §6). */
    public static final int DYNAMIC_MAX = 127;

    private static final Map<Integer, RtpMap> TABLE;

    static {
        Map<Integer, RtpMap> table = new TreeMap<>();
        // Table 4: audio encodings
        put(table, 0, "PCMU", 8000, 1);
        put(table, 3, "GSM", 8000, 1);
        put(table, 4, "G723", 8000, 1);
        put(table, 5, "DVI4", 8000, 1);
        put(table, 6, "DVI4", 16000, 1);
        put(table, 7, "LPC", 8000, 1);
        put(table, 8, "PCMA", 8000, 1);
        put(table, 9, "G722", 8000, 1);
        put(table, 10, "L16", 44100, 2);
        put(table, 11, "L16", 44100, 1);
        put(table, 12, "QCELP", 8000, 1);
        put(table, 13, "CN", 8000, 1);
        put(table, 14, "MPA", 90000, 1);
        put(table, 15, "G728", 8000, 1);
        put(table, 16, "DVI4", 11025, 1);
        put(table, 17, "DVI4", 22050, 1);
        put(table, 18, "G729", 8000, 1);
        // Table 5: video encodings
        put(table, 25, "CelB", 90000, 1);
        put(table, 26, "JPEG", 90000, 1);
        put(table, 28, "nv", 90000, 1);
        put(table, 31, "H261", 90000, 1);
        put(table, 32, "MPV", 90000, 1);
        put(table, 33, "MP2T", 90000, 1);
        put(table, 34, "H263", 90000, 1);
        TABLE = Collections.unmodifiableMap(table);
    }

    private StaticPayloadTypes() {
    }

    private static void put(Map<Integer, RtpMap> table, int pt, String name, int clockRate, int channels) {
        table.put(pt, new RtpMap(pt, name, clockRate, channels));
    }

    /** The static mapping for a payload type, if it is one of the RFC 3551 assignments. */
    public static Optional<RtpMap> lookup(int payloadType) {
        return Optional.ofNullable(TABLE.get(payloadType));
    }

    /** Whether the payload type has a static RFC 3551 assignment. */
    public static boolean isStatic(int payloadType) {
        return TABLE.containsKey(payloadType);
    }

    /** Whether the payload type lies in the dynamic range 96-127. */
    public static boolean isDynamic(int payloadType) {
        return payloadType >= DYNAMIC_MIN && payloadType <= DYNAMIC_MAX;
    }

    /** All static assignments, keyed by payload type in ascending order. */
    public static Map<Integer, RtpMap> all() {
        return TABLE;
    }
}
