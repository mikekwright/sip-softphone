package sip.softphone.sdp.version0;

import sip.softphone.sdp.SdpEncoder;
import sip.softphone.sdp.SessionDescription;

public class SdpEncoderVersion0 implements SdpEncoder {
    @Override
    public String encode(SessionDescription description) {
        return description.toString();
    }
}
