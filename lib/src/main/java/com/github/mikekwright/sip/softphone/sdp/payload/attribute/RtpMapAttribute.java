package com.github.mikekwright.sip.softphone.sdp.payload.attribute;

import com.github.mikekwright.sip.softphone.sdp.payload.SdpAttribute;
import com.github.mikekwright.sip.softphone.sdp.payload.SdpAttribute;

/*
a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding
         parameters>]

         This attribute maps from an RTP payload type number (as used in
         an "m=" line) to an encoding name denoting the payload format
         to be used.  It also provides information on the clock rate and
         encoding parameters.  It is a media-level attribute that is not
         dependent on charset.
 */
public class RtpMapAttribute implements SdpAttribute {
    private static final Integer DEFAULT_CLOCKRATE_HERTZ = 8000;
    private String payloadType;
    private String encodingName;
    private Integer clockRate;

    public RtpMapAttribute() {
        clockRate = DEFAULT_CLOCKRATE_HERTZ;
    }


    public String getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(String payloadType) {
        this.payloadType = payloadType;
    }

    public String getEncodingName() {
        return encodingName;
    }

    public void setEncodingName(String encodingName) {
        this.encodingName = encodingName;
    }

    public Integer getClockRate() {
        return clockRate;
    }

    public void setClockRate(Integer clockRate) {
        this.clockRate = clockRate;
    }

    @Override
    public String encode() {
        return String.format("a=rtpmap:%s %s/%d", payloadType, encodingName, clockRate);
    }

    @Override
    public String toString() {
        return encode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RtpMapAttribute that = (RtpMapAttribute) o;

        if (payloadType != null ? !payloadType.equals(that.payloadType) : that.payloadType != null) return false;
        if (encodingName != null ? !encodingName.equals(that.encodingName) : that.encodingName != null) return false;
        return clockRate != null ? clockRate.equals(that.clockRate) : that.clockRate == null;

    }

    @Override
    public int hashCode() {
        int result = payloadType != null ? payloadType.hashCode() : 0;
        result = 31 * result + (encodingName != null ? encodingName.hashCode() : 0);
        result = 31 * result + (clockRate != null ? clockRate.hashCode() : 0);
        return result;
    }
}
