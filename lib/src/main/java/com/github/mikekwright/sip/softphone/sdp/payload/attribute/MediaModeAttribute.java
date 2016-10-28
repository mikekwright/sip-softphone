package com.github.mikekwright.sip.softphone.sdp.payload.attribute;

import com.github.mikekwright.sip.softphone.sdp.payload.SdpAttribute;

public class MediaModeAttribute implements SdpAttribute {
    public enum Mode {
        SEND_RECV("sendrecv"),
        RECV_ONLY("recvonly"),
        SEND_ONLY("sendonly"),
        INACTIVE("inactive");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private static final Mode DEFAULT_MODE = Mode.SEND_RECV;

    private Mode mode;

    public MediaModeAttribute() {
        mode = DEFAULT_MODE;
    }

    @Override
    public String encode() {
        return String.format("a=%s", mode);
    }

    @Override
    public String toString() {
        return encode();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }
}
