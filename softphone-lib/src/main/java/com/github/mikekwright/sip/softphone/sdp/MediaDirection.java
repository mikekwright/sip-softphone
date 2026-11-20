package com.github.mikekwright.sip.softphone.sdp;

/**
 * Media stream direction attributes ({@code a=sendrecv}, {@code a=sendonly}, {@code a=recvonly},
 * {@code a=inactive}), RFC 8866 §6.7.
 *
 * <p>A direction is always expressed from the point of view of the party that wrote the SDP. When no direction
 * attribute is present at media or session level the default is {@link #SENDRECV} (RFC 8866 §6.7.1). Directions
 * can be viewed as a pair of flags ({@link #canSend()}, {@link #canReceive()}), which is how the offer/answer rules
 * of RFC 3264 §6.1 are implemented: see {@link #reverse()} and {@link #intersect(MediaDirection)}.</p>
 */
public enum MediaDirection {
    /** Send and receive (RFC 8866 §6.7.1). */
    SENDRECV("sendrecv", true, true),
    /** Send only (RFC 8866 §6.7.3). */
    SENDONLY("sendonly", true, false),
    /** Receive only (RFC 8866 §6.7.2). */
    RECVONLY("recvonly", false, true),
    /** Neither send nor receive media; RTCP is still exchanged (RFC 8866 §6.7.4). */
    INACTIVE("inactive", false, false);

    private final String sdpName;
    private final boolean send;
    private final boolean receive;

    MediaDirection(String sdpName, boolean send, boolean receive) {
        this.sdpName = sdpName;
        this.send = send;
        this.receive = receive;
    }

    /** The attribute name used in SDP, e.g. {@code sendonly}. */
    public String sdpName() {
        return sdpName;
    }

    /** Whether the party that declared this direction is willing to send media. */
    public boolean canSend() {
        return send;
    }

    /** Whether the party that declared this direction is willing to receive media. */
    public boolean canReceive() {
        return receive;
    }

    /**
     * The same direction seen from the other party: {@code SENDONLY} and {@code RECVONLY} swap, {@code SENDRECV}
     * and {@code INACTIVE} are unchanged. An answerer starts from the reverse of the offered direction
     * (RFC 3264 §6.1).
     */
    public MediaDirection reverse() {
        return of(receive, send);
    }

    /**
     * The direction allowed by both this direction and {@code other} (logical AND of the send and receive flags).
     * For example the answer direction is {@code offerDirection.reverse().intersect(localPreference)}
     * (RFC 3264 §6.1).
     *
     * @param other the other constraint, typically the local preference
     * @return the most permissive direction satisfying both
     */
    public MediaDirection intersect(MediaDirection other) {
        return of(send && other.send, receive && other.receive);
    }

    /** Builds the direction corresponding to the given send/receive flags. */
    public static MediaDirection of(boolean send, boolean receive) {
        if (send) {
            return receive ? SENDRECV : SENDONLY;
        }
        return receive ? RECVONLY : INACTIVE;
    }

    /**
     * Resolves an attribute name such as {@code recvonly} (case-insensitive).
     *
     * @return the direction or {@code null} if {@code name} is not a direction attribute
     */
    public static MediaDirection fromSdpName(String name) {
        if (name == null) {
            return null;
        }
        for (MediaDirection direction : values()) {
            if (direction.sdpName.equalsIgnoreCase(name)) {
                return direction;
            }
        }
        return null;
    }
}
