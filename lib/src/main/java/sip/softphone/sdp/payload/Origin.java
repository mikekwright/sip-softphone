package sip.softphone.sdp.payload;

public class Origin {
    private String username;
    private String sessionId;
    private Integer sessionVersion;
    private String netType;
    private String addrType;
    private String unicastAddress;

    public Integer getSessionVersion() {
        return sessionVersion;
    }

    public void setSessionVersion(Integer sessionVersion) {
        this.sessionVersion = sessionVersion;
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

    public String getUnicastAddress() {
        return unicastAddress;
    }

    public void setUnicastAddress(String unicastAddress) {
        this.unicastAddress = unicastAddress;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String encode() {
        return String.format("o=%s %s %s %s %s %s", username, sessionId, sessionVersion, netType, addrType, unicastAddress);
    }

    @Override
    public String toString() {
        return encode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Origin origin = (Origin) o;

        if (sessionId != null ? !sessionId.equals(origin.sessionId) : origin.sessionId != null) return false;
        return sessionVersion != null ? sessionVersion.equals(origin.sessionVersion) : origin.sessionVersion == null;

    }

    @Override
    public int hashCode() {
        int result = sessionId != null ? sessionId.hashCode() : 0;
        result = 31 * result + (sessionVersion != null ? sessionVersion.hashCode() : 0);
        return result;
    }
}
