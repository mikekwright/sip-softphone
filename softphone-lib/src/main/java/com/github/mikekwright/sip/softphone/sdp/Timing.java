package com.github.mikekwright.sip.softphone.sdp;

/**
 * Session timing ({@code t=<start-time> <stop-time>}), RFC 8866 §5.9.
 *
 * <p>Times are NTP timestamps in seconds. {@code t=0 0} denotes an unbounded, permanent session and is what SIP
 * user agents use (RFC 3264 §5). Repeat times ({@code r=}) are preserved as raw lines on the
 * {@link SessionDescription}.</p>
 *
 * @param start start time (0 = unbounded)
 * @param stop  stop time (0 = unbounded)
 */
public record Timing(long start, long stop) {

    /** The permanent session timing {@code t=0 0}. */
    public static final Timing PERMANENT = new Timing(0, 0);

    /** The text after {@code t=}, e.g. {@code 0 0}. */
    public String toSdpValue() {
        return start + " " + stop;
    }

    @Override
    public String toString() {
        return "t=" + toSdpValue();
    }
}
