package com.github.mikekwright.sip.softphone.sdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SdpParserTest {

    @Test
    void parsesAsteriskAudioOffer() {
        SessionDescription sd = SdpParser.parse(SampleSdp.ASTERISK_AUDIO_OFFER);

        assertThat(sd.version()).isZero();
        assertThat(sd.origin()).isEqualTo(new Origin("-", "1928374650", 1928374650L, "IN", "IP4", "192.0.2.10"));
        assertThat(sd.sessionName()).isEqualTo("Asterisk");
        assertThat(sd.connection()).isEqualTo(new Connection("IN", "IP4", "192.0.2.10"));
        assertThat(sd.timings()).containsExactly(new Timing(0, 0));
        assertThat(sd.media()).hasSize(1);

        MediaDescription audio = sd.media().getFirst();
        assertThat(audio.media()).isEqualTo("audio");
        assertThat(audio.port()).isEqualTo(17552);
        assertThat(audio.protocol()).isEqualTo("RTP/AVP");
        assertThat(audio.formats()).containsExactly("0", "8", "101");
        assertThat(audio.rtpMaps()).containsExactly(
                new RtpMap(0, "PCMU", 8000),
                new RtpMap(8, "PCMA", 8000),
                new RtpMap(101, "telephone-event", 8000).withFmtp("0-16"));
        assertThat(audio.fmtp(101)).contains("0-16");
        assertThat(audio.ptime()).hasValue(20);
        assertThat(audio.attribute("maxptime")).contains("150");
        assertThat(audio.direction()).isEqualTo(MediaDirection.SENDRECV);
        assertThat(audio.rtcpMux()).isFalse();
        assertThat(audio.rtcpPort()).isEmpty();
    }

    @Test
    void resolvesStaticPayloadTypesWithoutRtpmapAndParsesRtcpAttribute() {
        SessionDescription sd = SdpParser.parse(SampleSdp.FREESWITCH_AUDIO_OFFER);
        MediaDescription audio = sd.media().getFirst();

        assertThat(audio.rtpMaps()).extracting(RtpMap::payloadType, RtpMap::encodingName, RtpMap::clockRate)
                .containsExactly(
                        tuple(9, "G722", 8000),
                        tuple(0, "PCMU", 8000),
                        tuple(8, "PCMA", 8000),
                        tuple(18, "G729", 8000),
                        tuple(101, "telephone-event", 8000),
                        tuple(13, "CN", 8000));
        assertThat(audio.rtpMap(18)).get().extracting(RtpMap::fmtp).isEqualTo("annexb=no");
        assertThat(audio.rtpMap(18).orElseThrow().fmtpParameter("AnnexB")).contains("no");
        assertThat(audio.rtcpPort()).contains(24681);
        assertThat(audio.rtcpConnection()).contains(new Connection("IN", "IP4", "198.51.100.20"));
        assertThat(audio.direction()).isNull();
        assertThat(audio.effectiveDirection(sd)).isEqualTo(MediaDirection.SENDRECV);
    }

    @Test
    void parsesVideoOfferWithFeedbackAndRtcpMux() {
        SessionDescription sd = SdpParser.parse(SampleSdp.VIDEO_OFFER);
        assertThat(sd.bandwidths()).containsExactly(new Bandwidth("AS", 2048));

        MediaDescription audio = sd.media().get(0);
        assertThat(audio.rtpMap(111)).contains(
                new RtpMap(111, "opus", 48000, 2, "minptime=10;useinbandfec=1", List.of()));
        assertThat(audio.rtpMap(111).orElseThrow().toRtpMapValue()).isEqualTo("111 opus/48000/2");

        MediaDescription video = sd.media().get(1);
        assertThat(video.bandwidths()).containsExactly(new Bandwidth("TIAS", 1_500_000));
        assertThat(video.rtcpMux()).isTrue();
        assertThat(video.rtcpPort()).contains(55400);
        assertThat(video.rtcpFeedback(96)).containsExactly("nack", "nack pli", "ccm fir", "goog-remb");
        assertThat(video.rtcpFeedback(98)).containsExactly("goog-remb");

        RtpMap h264 = video.rtpMap(97).orElseThrow();
        assertThat(h264.encodingName()).isEqualTo("H264");
        assertThat(h264.fmtpParameter("packetization-mode")).contains("1");
        assertThat(h264.rtcpFeedback()).containsExactly("nack", "nack pli", "goog-remb");
        assertThat(video.rtpMap(98).orElseThrow().fmtpParameter("packetization-mode")).isEmpty();
        assertThat(video.rtpMap(99)).isEmpty();
    }

    @Test
    void parsesRfc8866Example() {
        SessionDescription sd = SdpParser.parse(SampleSdp.RFC8866_EXAMPLE);

        assertThat(sd.origin().username()).isEqualTo("jdoe");
        assertThat(sd.origin().sessionVersion()).isEqualTo(3724394405L);
        assertThat(sd.sessionName()).isEqualTo("Call to John Smith");
        assertThat(sd.sessionInformation()).isEqualTo("SDP Offer #1");
        assertThat(sd.uri()).isEqualTo("http://www.jdoe.example.com/home.html");
        assertThat(sd.emails()).containsExactly("Jane Doe <jane@jdoe.example.com>");
        assertThat(sd.phones()).containsExactly("+1 617 555-6011");
        assertThat(sd.media()).extracting(MediaDescription::media).containsExactly("audio", "audio", "video");

        MediaDescription video = sd.media().get(2);
        assertThat(video.connection()).isEqualTo(new Connection("IN", "IP6", "2001:db8::2"));
        assertThat(sd.connectionFor(video).host()).isEqualTo("2001:db8::2");
        assertThat(sd.connectionFor(sd.media().getFirst()).host()).isEqualTo("198.51.100.1");
        assertThat(video.rtpMaps()).containsExactly(new RtpMap(99, "h263-1998", 90000));
    }

    @Test
    void acceptsBareLfBlankLinesTrailingWhitespaceAndBytes() {
        String sdp = "v=0\n\no=- 1 2 IN IP4 10.0.0.1  \ns= \nt=0 0\t\nm=audio 4000 RTP/AVP 0\na=x-custom:foo bar\n";
        SessionDescription sd = SdpParser.parse(sdp.getBytes(StandardCharsets.UTF_8));

        assertThat(sd.origin().unicastAddress()).isEqualTo("10.0.0.1");
        assertThat(sd.sessionName()).isEqualTo(" ");
        assertThat(sd.connection()).isNull();
        assertThat(sd.media().getFirst().attribute("x-custom")).contains("foo bar");
    }

    @Test
    void preservesUnmodelledLinesAndPropertyAttributes() {
        String sdp = SampleSdp.crlf("""
                v=0
                o=- 1 1 IN IP4 10.0.0.1
                s=-
                t=2873397496 2873404696
                r=7d 1h 0 25h
                z=2882844526 -1h 2898848070 0
                k=prompt
                a=ice-lite
                a=recvonly
                m=audio 4000/2 RTP/AVP 0
                k=clear:secret
                a=rtcp-mux
                """);
        SessionDescription sd = SdpParser.parse(sdp);

        assertThat(sd.timings()).containsExactly(new Timing(2873397496L, 2873404696L));
        assertThat(sd.otherLines()).containsExactly("r=7d 1h 0 25h", "z=2882844526 -1h 2898848070 0", "k=prompt");
        assertThat(sd.attribute("ice-lite")).contains("");
        assertThat(sd.attributes()).first().matches(Attribute::isProperty);
        assertThat(sd.direction()).isEqualTo(MediaDirection.RECVONLY);

        MediaDescription audio = sd.media().getFirst();
        assertThat(audio.numberOfPorts()).isEqualTo(2);
        assertThat(audio.otherLines()).containsExactly("k=clear:secret");
        assertThat(audio.direction()).isNull();
        assertThat(audio.effectiveDirection(sd)).isEqualTo(MediaDirection.RECVONLY);
        assertThat(SdpWriter.write(sd)).isEqualTo(sdp);
    }

    @Test
    void rejectsMalformedInput() {
        assertParseError("", 0);
        assertParseError("garbage", 1);
        assertParseError("o=- 1 1 IN IP4 10.0.0.1\r\nv=0\r\n", 1);
        assertParseError("v=zero\r\no=- 1 1 IN IP4 10.0.0.1\r\n", 1);
        assertParseError("v=0\r\ns=-\r\nt=0 0\r\n", 0);
        assertParseError("v=0\r\no=- 1 IN IP4 10.0.0.1\r\n", 2);
        assertParseError("v=0\r\no=- 1 x IN IP4 10.0.0.1\r\n", 2);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nc=IN IP4\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nb=AS\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nt=0\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nm=audio RTP/AVP 0\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nm=audio 70000 RTP/AVP 0\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nm=audio 4000 RTP/AVP\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nm=audio 4000 RTP/AVP 0\r\nt=0 0\r\n", 4);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\nthis is not sdp\r\n", 3);
        assertParseError("v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\no=- 1 1 IN IP4 10.0.0.1\r\n", 3);
    }

    private static void assertParseError(String sdp, int lineNumber) {
        assertThatThrownBy(() -> SdpParser.parse(sdp))
                .isInstanceOfSatisfying(SdpParseException.class,
                        e -> assertThat(e.lineNumber()).isEqualTo(lineNumber));
    }
}
