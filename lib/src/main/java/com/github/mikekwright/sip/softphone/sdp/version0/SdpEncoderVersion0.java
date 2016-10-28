package com.github.mikekwright.sip.softphone.sdp.version0;

import com.github.mikekwright.sip.softphone.sdp.SdpEncoder;
import com.github.mikekwright.sip.softphone.sdp.SessionDescription;
import com.github.mikekwright.sip.softphone.sdp.SdpEncoder;
import com.github.mikekwright.sip.softphone.sdp.SessionDescription;

public class SdpEncoderVersion0 implements SdpEncoder {
    @Override
    public String encode(SessionDescription description) {
        return description.toString();
    }
}
