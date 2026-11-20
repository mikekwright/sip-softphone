package com.github.mikekwright.sip.softphone.media.dtmf;

/**
 * The RTP payload for named telephone events, RFC 4733 §2.3:
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     event     |E|R| volume    |          duration             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * DTMF digits use event codes 0-15 (RFC 4733 §3.2): {@code 0-9} map to 0-9, {@code *} to 10, {@code #} to 11 and
 * {@code A-D} to 12-15.
 *
 * @param event    event code (0..255)
 * @param end      the E bit: the event has ended
 * @param volume   power level of the tone in dBm0 with the sign dropped (0..63; larger means quieter)
 * @param duration duration of the event so far, in RTP timestamp units (0..65535)
 */
public record TelephoneEvent(int event, boolean end, int volume, int duration) {

    /** Encoded size of one event. */
    public static final int SIZE = 4;
    /** The encoding name used in SDP ({@code a=rtpmap:101 telephone-event/8000}). */
    public static final String ENCODING_NAME = "telephone-event";

    private static final String DIGITS = "0123456789*#ABCD";

    public TelephoneEvent {
        if (event < 0 || event > 255) {
            throw new IllegalArgumentException("event out of range: " + event);
        }
        if (volume < 0 || volume > 63) {
            throw new IllegalArgumentException("volume out of range: " + volume);
        }
        if (duration < 0 || duration > 0xFFFF) {
            throw new IllegalArgumentException("duration out of range: " + duration);
        }
    }

    /**
     * Decodes the first event of a payload. Redundant events appended by some senders are ignored.
     *
     * @throws IllegalArgumentException if the payload is shorter than {@value #SIZE} bytes
     */
    public static TelephoneEvent decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new IllegalArgumentException("telephone-event payload too short: " + payload.length);
        }
        return new TelephoneEvent(payload[0] & 0xFF, (payload[1] & 0x80) != 0, payload[1] & 0x3F,
                ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF));
    }

    /** Encodes this event as a 4-byte RTP payload (the R bit is zero). */
    public byte[] encode() {
        return new byte[]{(byte) event, (byte) ((end ? 0x80 : 0) | volume), (byte) (duration >> 8), (byte) duration};
    }

    /** Whether the event code denotes a DTMF digit (0..15). */
    public boolean isDtmf() {
        return event < DIGITS.length();
    }

    /** The DTMF digit of this event. @throws IllegalArgumentException if not a DTMF event */
    public char digit() {
        return digit(event);
    }

    /** The duration converted to milliseconds at {@code clockRate}. */
    public int durationMillis(int clockRate) {
        return (int) (duration * 1000L / clockRate);
    }

    /**
     * Event code of a DTMF digit ({@code 0-9 * # A-D}, letters case-insensitive).
     *
     * @throws IllegalArgumentException for any other character
     */
    public static int eventCode(char digit) {
        int code = DIGITS.indexOf(Character.toUpperCase(digit));
        if (code < 0) {
            throw new IllegalArgumentException("not a DTMF digit: '" + digit + "'");
        }
        return code;
    }

    /**
     * DTMF digit of an event code 0..15.
     *
     * @throws IllegalArgumentException for other codes
     */
    public static char digit(int code) {
        if (code < 0 || code >= DIGITS.length()) {
            throw new IllegalArgumentException("not a DTMF event code: " + code);
        }
        return DIGITS.charAt(code);
    }

    /** Whether {@code c} is a DTMF digit. */
    public static boolean isDtmfDigit(char c) {
        return DIGITS.indexOf(Character.toUpperCase(c)) >= 0;
    }

    /**
     * Validates a digit string.
     *
     * @throws IllegalArgumentException naming the first invalid character
     */
    public static void validateDigits(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            eventCode(digits.charAt(i));
        }
    }
}
