package com.github.mikekwright.sip.softphone.impl;

public class ViaDetails {
    private static final Integer DEFAULT_PORT = 5060;
    private static final String DEFAULT_TRANSPORT = "udp";

    private String ipAddress;
    private Integer port;
    private String protocol;
    private String branch;

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String transport) {
        this.protocol = transport;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public static ViaDetails createVia(String ipAddress) {
        return createViaWithPort(ipAddress, DEFAULT_PORT);
    }

    public static ViaDetails createViaWithPort(String ipAddress, Integer port) {
        return createViaWithPortAndProtocol(ipAddress, port, DEFAULT_TRANSPORT);
    }

    public static ViaDetails createViaWithPortAndProtocol(String ipAddress, Integer port, String protocol) {
        ViaDetails details = new ViaDetails();
        details.setIpAddress(ipAddress);
        details.setPort(port);
        details.setProtocol(protocol);
        return details;
    }
}
