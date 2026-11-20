package com.github.mikekwright.sip.softphone.sdp;

import java.util.Objects;

/**
 * The originator of a session and its identifier
 * ({@code o=<username> <sess-id> <sess-version> <nettype> <addrtype> <unicast-address>}), RFC 8866 §5.2.
 *
 * <p>{@code sess-id} is kept as a string because RFC 8866 only requires it to be a numeric string and real-world
 * values can exceed a {@code long}. RFC 3264 §8 requires the {@code sess-version} to be incremented on every new
 * offer or answer that changes the session, see {@link #withSessionVersion(long)}.</p>
 *
 * @param username       login of the originating user or {@code -}
 * @param sessionId      numeric session identifier
 * @param sessionVersion version of this session description
 * @param netType        network type, {@code IN}
 * @param addrType       address type, {@code IP4} or {@code IP6}
 * @param unicastAddress address of the originating machine
 */
public record Origin(String username, String sessionId, long sessionVersion, String netType, String addrType,
                     String unicastAddress) {

    public Origin {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(netType, "netType");
        Objects.requireNonNull(addrType, "addrType");
        Objects.requireNonNull(unicastAddress, "unicastAddress");
    }

    /**
     * Creates an {@code IN} origin, choosing {@code IP4} or {@code IP6} from the address syntax.
     */
    public static Origin of(String username, long sessionId, long sessionVersion, String unicastAddress) {
        return new Origin(username, Long.toString(sessionId), sessionVersion, Connection.NET_TYPE_IN,
                Connection.addressTypeOf(unicastAddress), unicastAddress);
    }

    /** Returns a copy with a different session version (RFC 3264 §8). */
    public Origin withSessionVersion(long version) {
        return new Origin(username, sessionId, version, netType, addrType, unicastAddress);
    }

    /** The text after {@code o=}. */
    public String toSdpValue() {
        return String.join(" ", username, sessionId, Long.toString(sessionVersion), netType, addrType,
                unicastAddress);
    }

    @Override
    public String toString() {
        return "o=" + toSdpValue();
    }
}
