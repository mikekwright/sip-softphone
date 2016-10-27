package sip.softphone.sdp.payload;

public class SessionInformation {
    public static SessionInformation fromString(String information) {
        SessionInformation si = new SessionInformation();
        si.setValue(information);
        return si;
    }

    private String value;

    public String getValue() {
        return value;
    }
    public void setValue(String value) {
        this.value = value;
    }

    public String encode() {
        return String.format("i=%s", value);
    }

    @Override
    public String toString() {
        return encode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SessionInformation that = (SessionInformation) o;

        return value != null ? value.equals(that.value) : that.value == null;

    }

    @Override
    public int hashCode() {
        return value != null ? value.hashCode() : 0;
    }
}
