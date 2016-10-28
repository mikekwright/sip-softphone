package sip.softphone.sdp.payload;

public class SessionName {
    public static SessionName fromString(String name) {
        SessionName n = new SessionName();
        n.setValue(name);
        return n;
    }

    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String encode() {
        return String.format("s=%s", value);
    }

    @Override
    public String toString() {
        return encode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SessionName that = (SessionName) o;

        return value != null ? value.equals(that.value) : that.value == null;

    }

    @Override
    public int hashCode() {
        return value != null ? value.hashCode() : 0;
    }
}
