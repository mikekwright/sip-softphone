package com.github.mikekwright.sip.softphone.sdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SdpModelTest {

    @Test
    void mediaDirectionRules() {
        assertThat(MediaDirection.fromSdpName("sendonly")).isEqualTo(MediaDirection.SENDONLY);
        assertThat(MediaDirection.fromSdpName("RECVONLY")).isEqualTo(MediaDirection.RECVONLY);
        assertThat(MediaDirection.fromSdpName("rtcp-mux")).isNull();
        assertThat(MediaDirection.SENDONLY.reverse()).isEqualTo(MediaDirection.RECVONLY);
        assertThat(MediaDirection.RECVONLY.reverse()).isEqualTo(MediaDirection.SENDONLY);
        assertThat(MediaDirection.SENDRECV.reverse()).isEqualTo(MediaDirection.SENDRECV);
        assertThat(MediaDirection.INACTIVE.reverse()).isEqualTo(MediaDirection.INACTIVE);
        assertThat(MediaDirection.SENDRECV.intersect(MediaDirection.RECVONLY)).isEqualTo(MediaDirection.RECVONLY);
        assertThat(MediaDirection.SENDONLY.intersect(MediaDirection.RECVONLY)).isEqualTo(MediaDirection.INACTIVE);
        assertThat(MediaDirection.SENDRECV.canSend()).isTrue();
        assertThat(MediaDirection.INACTIVE.canReceive()).isFalse();
        assertThat(MediaDirection.of(true, false)).isEqualTo(MediaDirection.SENDONLY);
    }

    @Test
    void setDirectionReplacesExistingAttributeInPlace() {
        MediaDescription m = new MediaDescription("audio", 4000, "RTP/AVP")
                .addAttribute(Attribute.property("sendrecv"))
                .addAttribute("ptime", "20");
        m.setDirection(MediaDirection.INACTIVE);

        assertThat(m.attributes()).containsExactly(Attribute.property("inactive"), new Attribute("ptime", "20"));
        assertThat(m.direction()).isEqualTo(MediaDirection.INACTIVE);

        m.setDirection(null);
        assertThat(m.direction()).isNull();
        assertThat(m.effectiveDirection(new SessionDescription().setDirection(MediaDirection.SENDONLY)))
                .isEqualTo(MediaDirection.SENDONLY);
        assertThat(m.effectiveDirection(new SessionDescription())).isEqualTo(MediaDirection.SENDRECV);
    }

    @Test
    void addFormatWritesRtpmapFmtpAndFeedback() {
        MediaDescription m = new MediaDescription("video", 5000, "RTP/AVPF").addFormat(
                new RtpMap(96, "VP8", 90000, 1, null, List.of("nack", "nack pli")),
                new RtpMap(97, "H264", 90000).withFmtp("packetization-mode=1"));

        assertThat(m.formats()).containsExactly("96", "97");
        assertThat(m.attributes()).extracting(Attribute::toSdpValue).containsExactly(
                "rtpmap:96 VP8/90000", "rtcp-fb:96 nack", "rtcp-fb:96 nack pli",
                "rtpmap:97 H264/90000", "fmtp:97 packetization-mode=1");
        assertThat(m.rtpMaps()).extracting(RtpMap::rtcpFeedback)
                .containsExactly(List.of("nack", "nack pli"), List.of());
        assertThat(m.isRejected()).isFalse();
        assertThat(m.setPort(0).isRejected()).isTrue();
    }

    @Test
    void rtpMapParsingAndFormatting() {
        assertThat(RtpMap.parse("96 opus/48000/2")).isEqualTo(new RtpMap(96, "opus", 48000, 2));
        assertThat(new RtpMap(96, "opus", 48000, 2).toRtpMapValue()).isEqualTo("96 opus/48000/2");
        assertThat(new RtpMap(0, "PCMU", 8000, 1).toRtpMapValue()).isEqualTo("0 PCMU/8000");
        assertThat(new RtpMap(101, "telephone-event", 8000).isTelephoneEvent()).isTrue();
        assertThatThrownBy(() -> RtpMap.parse("96 VP8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RtpMap.parse("x VP8/90000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staticPayloadTypes() {
        assertThat(StaticPayloadTypes.lookup(0)).contains(new RtpMap(0, "PCMU", 8000));
        assertThat(StaticPayloadTypes.lookup(10)).contains(new RtpMap(10, "L16", 44100, 2));
        assertThat(StaticPayloadTypes.lookup(34)).contains(new RtpMap(34, "H263", 90000));
        assertThat(StaticPayloadTypes.lookup(96)).isEmpty();
        assertThat(StaticPayloadTypes.isDynamic(101)).isTrue();
        assertThat(StaticPayloadTypes.all().keySet()).startsWith(0, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void connectionHelpers() {
        assertThat(Connection.ip("10.0.0.1")).isEqualTo(new Connection("IN", "IP4", "10.0.0.1"));
        assertThat(Connection.ip("2001:db8::1").addrType()).isEqualTo("IP6");
        assertThat(new Connection("IN", "IP4", "224.2.1.1/127/3").host()).isEqualTo("224.2.1.1");
        assertThat(Connection.ip("0.0.0.0").isUnspecified()).isTrue();
        assertThat(Connection.ip("10.0.0.1").isUnspecified()).isFalse();
    }

    @Test
    void sessionAttributeHelpers() {
        SessionDescription sd = new SessionDescription()
                .addAttribute("group", "BUNDLE 0 1")
                .addAttribute("group", "LS 0 1")
                .addAttribute(Attribute.property("ice-lite"));

        assertThat(sd.attribute("group")).contains("BUNDLE 0 1");
        assertThat(sd.attributes("group")).containsExactly("BUNDLE 0 1", "LS 0 1");
        assertThat(sd.attribute("missing")).isEmpty();
        assertThat(sd.removeAttributes("group").attributes()).containsExactly(Attribute.property("ice-lite"));
        assertThat(sd.timings()).containsExactly(Timing.PERMANENT);
    }
}
