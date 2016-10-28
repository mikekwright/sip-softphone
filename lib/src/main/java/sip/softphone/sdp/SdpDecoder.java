package sip.softphone.sdp;

public interface SdpDecoder {
    SessionDescription decode(String content);
}
