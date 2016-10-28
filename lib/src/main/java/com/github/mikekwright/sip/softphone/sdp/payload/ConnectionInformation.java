package com.github.mikekwright.sip.softphone.sdp.payload;

/**

RFC Details:
 The "c=" field contains connection data.

 A session description MUST contain either at least one "c=" field in
 each media description or a single "c=" field at the session level.
 It MAY contain a single session-level "c=" field and additional "c="
 field(s) per media description, in which case the per-media values
 override the session-level settings for the respective media.

 The first sub-field ("<nettype>") is the network type, which is a
 text string giving the type of network.  Initially, "IN" is defined
 to have the meaning "Internet", but other values MAY be registered in
 the future (see Section 8).

 The second sub-field ("<addrtype>") is the address type.  This allows
 SDP to be used for sessions that are not IP based.  This memo only
 defines IP4 and IP6, but other values MAY be registered in the future
 (see Section 8).

 The third sub-field ("<connection-address>") is the connection
 address.  OPTIONAL sub-fields MAY be added after the connection
 address depending on the value of the <addrtype> field.

 When the <addrtype> is IP4 and IP6, the connection address is defined
 as follows:

 o  If the session is multicast, the connection address will be an IP
 multicast group address.  If the session is not multicast, then
 the connection address contains the unicast IP address of the
 expected data source or data relay or data sink as determined by
 additional attribute fields.  It is not expected that unicast
 addresses will be given in a session description that is
 communicated by a multicast announcement, though this is not
 prohibited.

 o  Sessions using an IPv4 multicast connection address MUST also have
 a time to live (TTL) value present in addition to the multicast
 address.  The TTL and the address together define the scope with
 which multicast packets sent in this conference will be sent.  TTL
 values MUST be in the range 0-255.  Although the TTL MUST be
 specified, its use to scope multicast traffic is deprecated;

 */
public class ConnectionInformation {
    private static final String DEFAULT_NET_TYPE = "IN";
    private static final String DEFAULT_ADDR_TYPE = "IP4";

    private String netType;
    private String addrType;
    private String connectionAddress;
    private Integer ttl;

    public ConnectionInformation(String connectionAddress) {
        this.connectionAddress = connectionAddress;
        ttl = null;
        netType = DEFAULT_NET_TYPE;
        addrType = DEFAULT_ADDR_TYPE;
    }

    public String getNetType() {
        return netType;
    }

    public void setNetType(String netType) {
        this.netType = netType;
    }

    public String getAddrType() {
        return addrType;
    }

    public void setAddrType(String addrType) {
        this.addrType = addrType;
    }

    public String getConnectionAddress() {
        return connectionAddress;
    }

    public void setConnectionAddress(String connectionAddress) {
        this.connectionAddress = connectionAddress;
    }

    public String encode() {
        String address = ttl == null ? connectionAddress : String.format("%s/%s", connectionAddress, ttl);
        return String.format("c=%s %s %s", netType, addrType, address);
    }

    @Override
    public String toString() {
        return encode();
    }

    public Integer getTtl() {
        return ttl;
    }

    public void setTtl(Integer ttl) {
        this.ttl = ttl;
    }
}
