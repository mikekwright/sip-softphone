package com.github.mikekwright.sip.softphone.sdp;

import com.github.mikekwright.sip.softphone.sdp.payload.*;

import java.util.ArrayList;
import java.util.List;

public class SessionDescription {
    private static final String NEW_LINE = "\r\n";
    public static final String RFC = "RFC4566";
    public static final String RFC_URL = "https://tools.ietf.org/html/rfc4566";

    private Origin origin;
    private SessionName sessionName;
    private List<SessionInformation> sessionInformationList;
    private List<ConnectionInformation> connectionInformationList;
    private List<SdpAttribute> sessionAttributeList;
    private List<SdpAttribute> mediaAttributeList;
    private Timing timing;

    public SessionDescription() {
        origin = new Origin();
        sessionName = new SessionName();
        sessionInformationList = new ArrayList<>();
        connectionInformationList = new ArrayList<>();
        sessionAttributeList = new ArrayList<>();
        mediaAttributeList = new ArrayList<>();
        timing = new Timing();
    }


    public Origin getOrigin() {
        return origin;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    public SessionName getSessionName() {
        return sessionName;
    }

    public void setSessionName(SessionName sessionName) {
        this.sessionName = sessionName;
    }

    public List<SessionInformation> getSessionInformationList() {
        return sessionInformationList;
    }

    public void setSessionInformationList(List<SessionInformation> sessionInformationList) {
        this.sessionInformationList = sessionInformationList;
    }

    public List<ConnectionInformation> getConnectionInformationList() {
        return connectionInformationList;
    }

    public void setConnectionInformationList(List<ConnectionInformation> connectionInformationList) {
        this.connectionInformationList = connectionInformationList;
    }

    public List<SdpAttribute> getSessionAttributeList() {
        return sessionAttributeList;
    }

    public void setSessionAttributeList(List<SdpAttribute> sessionAttributeList) {
        this.sessionAttributeList = sessionAttributeList;
    }

    public Timing getTiming() {
        return timing;
    }

    public void setTiming(Timing timing) {
        this.timing = timing;
    }

    public String encode() {
        StringBuilder builder = new StringBuilder();
        builder.append("v=0").append(NEW_LINE);
        builder.append(origin).append(NEW_LINE);
        builder.append(sessionName).append(NEW_LINE);
        for (ConnectionInformation c : connectionInformationList) {
            builder.append(c).append(NEW_LINE);
        }
        for (SdpAttribute a : sessionAttributeList) {
            builder.append(a).append(NEW_LINE);
        }
        // Add the media values
        for (SdpAttribute a : mediaAttributeList) {
            builder.append(a).append(NEW_LINE);
        }

        return builder.toString();
    }

    @Override
    public String toString() {
        return encode();
    }

    public List<SdpAttribute> getMediaAttributeList() {
        return mediaAttributeList;
    }

    public void setMediaAttributeList(List<SdpAttribute> mediaAttributeList) {
        this.mediaAttributeList = mediaAttributeList;
    }
}
