package com.github.mikekwright.sip.softphone.sdp;

import java.util.Objects;

/**
 * Connection data ({@code c=<nettype> <addrtype> <connection-address>}), RFC 8866 §5.7.
 *
 * <p>The connection address is kept exactly as written, including the optional multicast suffixes
 * ({@code /<ttl>} and {@code /<number of addresses>}); use {@link #host()} for the bare address.</p>
 *
 * @param netType  network type, {@code IN}
 * @param addrType address type, {@code IP4} or {@code IP6}
 * @param address  connection address, possibly with {@code /ttl[/count]} suffix
 */
public record Connection(String netType, String addrType, String address) {

    /** The Internet network type (RFC 8866 §5.7). */
    public static final String NET_TYPE_IN = "IN";
    /** IPv4 address type. */
    public static final String IP4 = "IP4";
    /** IPv6 address type. */
    public static final String IP6 = "IP6";

    public Connection {
        Objects.requireNonNull(netType, "netType");
        Objects.requireNonNull(addrType, "addrType");
        Objects.requireNonNull(address, "address");
    }

    /**
     * Creates an {@code IN} connection for a unicast address, choosing {@code IP6} when the address contains a
     * colon and {@code IP4} otherwise.
     */
    public static Connection ip(String address) {
        return new Connection(NET_TYPE_IN, addressTypeOf(address), address);
    }

    static String addressTypeOf(String address) {
        return address.indexOf(':') >= 0 ? IP6 : IP4;
    }

    /** The address without any {@code /ttl} or {@code /number-of-addresses} suffix. */
    public String host() {
        int slash = address.indexOf('/');
        return slash < 0 ? address : address.substring(0, slash);
    }

    /**
     * Whether this is the unspecified address {@code 0.0.0.0} (or {@code ::}). RFC 3264 §8.4: an agent receiving
     * it must not send RTP or RTCP to the peer; it is the RFC 2543 style of putting a call on hold.
     */
    public boolean isUnspecified() {
        return isUnspecified(host());
    }

    static boolean isUnspecified(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host) || "0:0:0:0:0:0:0:0".equals(host);
    }

    /** The text after {@code c=}. */
    public String toSdpValue() {
        return netType + ' ' + addrType + ' ' + address;
    }

    @Override
    public String toString() {
        return "c=" + toSdpValue();
    }
}
