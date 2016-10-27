package sip.softphone.sdp;

public interface SdpEncoder {
    String encode(SessionDescription description);
}
