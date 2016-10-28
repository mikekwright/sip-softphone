package com.github.mikekwright.sip.softphone.sdp;

public interface SdpDecoder {
    SessionDescription decode(String content);
}
