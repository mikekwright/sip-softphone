package sip.softphone;

public interface SipStackDetails {
    String DEFAULT_PROVIDER_PROTOCOL = "udp";

    String stackName();
    String ipAddress();
    Integer port();

    default String protocol() {
        return DEFAULT_PROVIDER_PROTOCOL;
    }
}
