package com.github.mikekwright.sip.softphone.sdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SdpNegotiatorTest {

    private static final RtpMap PCMU = new RtpMap(0, "PCMU", 8000);
    private static final RtpMap PCMA = new RtpMap(8, "PCMA", 8000);
    private static final RtpMap OPUS = new RtpMap(111, "opus", 48000, 2).withFmtp("useinbandfec=1");
    private static final RtpMap DTMF = new RtpMap(101, "telephone-event", 8000).withFmtp("0-16");

    private final SdpNegotiator negotiator = new SdpNegotiator();

    // ---------------------------------------------------------------------------------------------------------
    // Offers
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void createsAudioVideoOffer() {
        SdpMediaConfig audio = SdpMediaConfig.audio("10.0.0.1", 4000, List.of(OPUS, PCMU, DTMF)).withPtime(20);
        SdpMediaConfig video = SdpMediaConfig.video("10.0.0.2", 5000, List.of(
                        new RtpMap(96, "VP8", 90000, 1, null, List.of("nack", "nack pli"))))
                .withProtocol("RTP/AVPF").withRtcpMux(true).withDirection(MediaDirection.RECVONLY);

        SessionDescription offer = negotiator.createOffer("alice", "10.0.0.1", 1234, 1, List.of(audio, video));

        assertThat(offer.toString()).isEqualTo(SampleSdp.crlf("""
                v=0
                o=alice 1234 1 IN IP4 10.0.0.1
                s=-
                c=IN IP4 10.0.0.1
                t=0 0
                m=audio 4000 RTP/AVP 111 0 101
                a=rtpmap:111 opus/48000/2
                a=fmtp:111 useinbandfec=1
                a=rtpmap:0 PCMU/8000
                a=rtpmap:101 telephone-event/8000
                a=fmtp:101 0-16
                a=ptime:20
                a=sendrecv
                m=video 5000 RTP/AVPF 96
                c=IN IP4 10.0.0.2
                a=rtpmap:96 VP8/90000
                a=rtcp-fb:96 nack
                a=rtcp-fb:96 nack pli
                a=rtcp-mux
                a=recvonly
                """));
        assertThat(SdpParser.parse(offer.toString())).isEqualTo(offer);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Answers
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void answersAsteriskOfferWithLocalPreferenceAndTelephoneEvent() {
        SessionDescription offer = SdpParser.parse(SampleSdp.ASTERISK_AUDIO_OFFER);
        SdpMediaConfig local = SdpMediaConfig.audio("10.0.0.5", 30000,
                List.of(PCMA, PCMU, new RtpMap(96, "telephone-event", 8000).withFmtp("0-15")));

        NegotiationResult result = negotiator.createAnswer(offer, "bob", "10.0.0.5", 77, 1, List.of(local));

        MediaDescription answered = result.answer().media().getFirst();
        assertThat(answered.port()).isEqualTo(30000);
        assertThat(answered.formats()).containsExactly("8", "0", "101");
        assertThat(answered.rtpMap(101)).contains(new RtpMap(101, "telephone-event", 8000).withFmtp("0-15"));
        assertThat(answered.direction()).isEqualTo(MediaDirection.SENDRECV);
        assertThat(result.answer().origin()).isEqualTo(Origin.of("bob", 77, 1, "10.0.0.5"));

        NegotiatedMedia media = result.media().getFirst();
        assertThat(media.rejected()).isFalse();
        assertThat(media.remoteAddress()).isEqualTo("192.0.2.10");
        assertThat(media.remotePort()).isEqualTo(17552);
        assertThat(media.remoteRtcpAddress()).isEqualTo("192.0.2.10");
        assertThat(media.remoteRtcpPort()).isEqualTo(17553);
        assertThat(media.rtcpMux()).isFalse();
        assertThat(media.direction()).isEqualTo(MediaDirection.SENDRECV);
        assertThat(media.formats()).extracting(NegotiatedFormat::encodingName).containsExactly("PCMA", "PCMU");
        assertThat(media.telephoneEvent()).isEqualTo(new NegotiatedFormat("telephone-event", 8000, 1, 101, 101,
                "0-15", "0-16", List.of()));
    }

    @Test
    void answerReusesOfferedDynamicPayloadTypesAndIntersectsFeedback() {
        SessionDescription offer = SdpParser.parse(SampleSdp.VIDEO_OFFER);
        SdpMediaConfig audio = SdpMediaConfig.audio("10.0.0.5", 30000,
                List.of(new RtpMap(109, "opus", 48000, 2).withFmtp("minptime=20"), DTMF)).withRtcpMux(true);
        SdpMediaConfig video = SdpMediaConfig.video("10.0.0.5", 30002, List.of(
                        new RtpMap(126, "H264", 90000, 1, "profile-level-id=42e01f;packetization-mode=1",
                                List.of("nack", "nack pli", "ccm fir")),
                        new RtpMap(100, "VP8", 90000, 1, null, List.of("nack pli", "goog-remb", "transport-cc"))))
                .withProtocol("RTP/AVPF").withRtcpMux(false);

        NegotiationResult result = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, List.of(audio, video));

        MediaDescription answeredAudio = result.answer().media().get(0);
        assertThat(answeredAudio.protocol()).isEqualTo("RTP/AVPF");
        assertThat(answeredAudio.formats()).containsExactly("111", "101");
        assertThat(answeredAudio.fmtp(111)).contains("minptime=20");
        assertThat(answeredAudio.rtcpMux()).isTrue();

        MediaDescription answeredVideo = result.answer().media().get(1);
        assertThat(answeredVideo.formats()).containsExactly("97", "96");
        assertThat(answeredVideo.rtcpFeedback(97)).containsExactly("nack", "nack pli");
        assertThat(answeredVideo.rtcpFeedback(96)).containsExactly("nack pli", "goog-remb");
        assertThat(answeredVideo.rtcpMux()).isFalse();

        NegotiatedMedia audioMedia = result.media().get(0);
        assertThat(audioMedia.rtcpMux()).isTrue();
        assertThat(audioMedia.remoteRtcpPort()).isEqualTo(54400);
        assertThat(audioMedia.formats()).containsExactly(new NegotiatedFormat("opus", 48000, 2, 111, 111,
                "minptime=20", "minptime=10;useinbandfec=1", List.of()));
        assertThat(audioMedia.telephoneEvent().localPayloadType()).isEqualTo(101);

        NegotiatedMedia videoMedia = result.media().get(1);
        assertThat(videoMedia.rtcpMux()).isFalse();
        assertThat(videoMedia.remoteRtcpPort()).isEqualTo(55400);
        assertThat(videoMedia.primaryFormat().encodingName()).isEqualTo("H264");
        assertThat(videoMedia.primaryFormat().remotePayloadType()).isEqualTo(97);
        assertThat(videoMedia.primaryFormat().remoteFmtp()).contains("packetization-mode=1");
        assertThat(videoMedia.telephoneEvent()).isNull();
    }

    @Test
    void h264RequiresSamePacketizationMode() {
        SessionDescription offer = SdpParser.parse(SampleSdp.VIDEO_OFFER);
        SdpMediaConfig video = SdpMediaConfig.video("10.0.0.5", 30002,
                List.of(new RtpMap(100, "H264", 90000).withFmtp("profile-level-id=42e01f")));

        NegotiationResult result = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, List.of(video));

        assertThat(result.answer().media().get(1).formats()).containsExactly("98");
        assertThat(result.media().get(1).primaryFormat().localPayloadType()).isEqualTo(98);
    }

    @Test
    void rejectsStreamsWithoutCommonCodecOrLocalConfigPreservingOrder() {
        SessionDescription offer = SdpParser.parse(SampleSdp.VIDEO_OFFER);
        SdpMediaConfig g729Only = SdpMediaConfig.audio("10.0.0.5", 30000, List.of(new RtpMap(18, "G729", 8000)));

        NegotiationResult result = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, List.of(g729Only));

        assertThat(result.answer().media()).hasSize(2);
        assertThat(result.answer().media()).extracting(MediaDescription::media).containsExactly("audio", "video");
        assertThat(result.answer().media()).allMatch(MediaDescription::isRejected);
        assertThat(result.answer().media()).extracting(m -> m.formats()).containsExactly(List.of("111"),
                List.of("96"));
        assertThat(result.media()).allMatch(NegotiatedMedia::rejected);
        assertThat(result.media()).extracting(NegotiatedMedia::direction).containsOnly(MediaDirection.INACTIVE);
        assertThat(result.media().getFirst().canSend()).isFalse();
    }

    @Test
    void answersRfc4317Example2_1() {
        SessionDescription offer = SdpParser.parse(SampleSdp.RFC4317_2_1_OFFER);
        List<SdpMediaConfig> bob = List.of(
                SdpMediaConfig.audio("host.biloxi.example.com", 49174, List.of(PCMU)),
                SdpMediaConfig.video("host.biloxi.example.com", 49170, List.of(new RtpMap(32, "MPV", 90000))));

        NegotiationResult result = negotiator.createAnswer(offer, "bob", "host.biloxi.example.com",
                2808844564L, 2808844564L, bob);

        SessionDescription expected = SdpParser.parse(SampleSdp.RFC4317_2_1_ANSWER);
        result.answer().setSessionName(" ");
        expected.media().forEach(m -> m.setDirection(MediaDirection.SENDRECV));
        assertThat(result.answer()).isEqualTo(expected);
    }

    @Test
    void answersRfc4317Example2_2RejectingVideo() {
        SessionDescription offer = SdpParser.parse(SampleSdp.RFC4317_2_1_OFFER);
        List<SdpMediaConfig> bob = List.of(
                SdpMediaConfig.audio("host.biloxi.example.com", 49172, List.of(PCMU, PCMA)));

        NegotiationResult result = negotiator.createAnswer(offer, "bob", "host.biloxi.example.com", 1, 1, bob);

        assertThat(result.answer().media().get(0).formats()).containsExactly("0", "8");
        assertThat(result.answer().media().get(1).port()).isZero();
        assertThat(result.answer().media().get(1).formats()).containsExactly("31");
        assertThat(result.media().get(1).rejected()).isTrue();
    }

    @Test
    void answersHoldOffersWithReversedDirection() {
        SessionDescription offer = SdpParser.parse(SampleSdp.ASTERISK_AUDIO_OFFER);
        SdpMediaConfig local = SdpMediaConfig.audio("10.0.0.5", 30000, List.of(PCMU));

        offer.media().getFirst().setDirection(MediaDirection.SENDONLY);
        assertThat(SdpNegotiator.isHold(offer)).isTrue();
        assertAnswerDirection(offer, local, MediaDirection.RECVONLY);

        offer.media().getFirst().setDirection(MediaDirection.INACTIVE);
        assertAnswerDirection(offer, local, MediaDirection.INACTIVE);

        offer.media().getFirst().setDirection(MediaDirection.RECVONLY);
        assertThat(SdpNegotiator.isHold(offer)).isFalse();
        assertAnswerDirection(offer, local, MediaDirection.SENDONLY);

        offer.media().getFirst().setDirection(MediaDirection.SENDRECV);
        assertAnswerDirection(offer, local.withDirection(MediaDirection.INACTIVE), MediaDirection.INACTIVE);
        assertAnswerDirection(offer, local.withDirection(MediaDirection.SENDONLY), MediaDirection.SENDONLY);
    }

    @Test
    void treatsUnspecifiedConnectionAddressAsHold() {
        SessionDescription offer = SdpParser.parse(SampleSdp.ASTERISK_AUDIO_OFFER);
        offer.setConnection(Connection.ip("0.0.0.0"));
        SdpMediaConfig local = SdpMediaConfig.audio("10.0.0.5", 30000, List.of(PCMU));

        assertThat(SdpNegotiator.isHold(offer)).isTrue();
        NegotiationResult result = assertAnswerDirection(offer, local, MediaDirection.RECVONLY);
        assertThat(result.media().getFirst().canSend()).isFalse();
        assertThat(result.media().getFirst().canReceive()).isTrue();
    }

    @Test
    void answerUsesOfferTimingAndMediaLevelAddress() {
        SessionDescription offer = SdpParser.parse(SampleSdp.RFC8866_EXAMPLE);
        offer.setTimings(List.of(new Timing(3724394400L, 3724398000L)));
        List<SdpMediaConfig> local = List.of(
                SdpMediaConfig.audio(null, 6000, List.of(PCMU)),
                SdpMediaConfig.audio("10.0.0.9", 6002, List.of(PCMU)),
                SdpMediaConfig.video(null, 6004, List.of(new RtpMap(99, "H263-1998", 90000))));

        NegotiationResult result = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, local);

        assertThat(result.answer().timings()).containsExactly(new Timing(3724394400L, 3724398000L));
        assertThat(result.answer().media()).extracting(MediaDescription::port).containsExactly(6000, 6002, 6004);
        assertThat(result.answer().media().get(0).connection()).isNull();
        assertThat(result.answer().media().get(1).connection()).isEqualTo(Connection.ip("10.0.0.9"));
        assertThat(result.media().get(2).remoteAddress()).isEqualTo("2001:db8::2");
        assertThat(result.media().get(2).formats().getFirst().encodingName()).isEqualTo("h263-1998");
    }

    @Test
    void eachLocalConfigServesOneOfferedStream() {
        SessionDescription offer = SdpParser.parse(SampleSdp.RFC8866_EXAMPLE);
        SdpMediaConfig audio = SdpMediaConfig.audio(null, 6000, List.of(PCMU));

        NegotiationResult single = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, List.of(audio));
        assertThat(single.media()).extracting(NegotiatedMedia::rejected).containsExactly(false, true, true);

        NegotiationResult twice = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, List.of(audio, audio));
        assertThat(twice.media()).extracting(NegotiatedMedia::rejected).containsExactly(false, false, true);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Processing answers
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void processesRfc4317Example2_1Answer() {
        SessionDescription offer = SdpParser.parse(SampleSdp.RFC4317_2_1_OFFER);
        SessionDescription answer = SdpParser.parse(SampleSdp.RFC4317_2_1_ANSWER);

        List<NegotiatedMedia> media = negotiator.processAnswer(offer, answer);

        assertThat(media).hasSize(2);
        assertThat(media.get(0).remoteAddress()).isEqualTo("host.biloxi.example.com");
        assertThat(media.get(0).remotePort()).isEqualTo(49174);
        assertThat(media.get(0).remoteRtcpPort()).isEqualTo(49175);
        assertThat(media.get(0).formats()).containsExactly(
                new NegotiatedFormat("PCMU", 8000, 1, 0, 0, null, null, List.of()));
        assertThat(media.get(1).formats()).extracting(NegotiatedFormat::encodingName).containsExactly("MPV");
        assertThat(media).extracting(NegotiatedMedia::direction).containsOnly(MediaDirection.SENDRECV);
    }

    @Test
    void processesAnswerWithRenumberedPayloadTypesRejectionAndDirection() {
        SdpMediaConfig audio = SdpMediaConfig.audio("10.0.0.1", 4000, List.of(OPUS, PCMU, DTMF)).withRtcpMux(true);
        SdpMediaConfig video = SdpMediaConfig.video("10.0.0.1", 5000, List.of(new RtpMap(96, "VP8", 90000)));
        SessionDescription offer = negotiator.createOffer("-", "10.0.0.1", 9, 1, List.of(audio, video));

        SessionDescription answer = SdpParser.parse(SampleSdp.crlf("""
                v=0
                o=- 5 1 IN IP4 192.0.2.50
                s=-
                c=IN IP4 192.0.2.50
                t=0 0
                m=audio 7000 RTP/AVP 0 120 100
                a=rtpmap:0 PCMU/8000
                a=rtpmap:120 OPUS/48000/2
                a=fmtp:120 stereo=1
                a=rtpmap:100 telephone-event/8000
                a=rtcp-mux
                a=recvonly
                m=video 0 RTP/AVP 96
                """));

        List<NegotiatedMedia> media = negotiator.processAnswer(offer, answer);

        NegotiatedMedia audioMedia = media.get(0);
        assertThat(audioMedia.direction()).isEqualTo(MediaDirection.SENDONLY);
        assertThat(audioMedia.rtcpMux()).isTrue();
        assertThat(audioMedia.remoteRtcpPort()).isEqualTo(7000);
        assertThat(audioMedia.formats()).containsExactly(
                new NegotiatedFormat("PCMU", 8000, 1, 0, 0, null, null, List.of()),
                new NegotiatedFormat("opus", 48000, 2, 111, 120, "useinbandfec=1", "stereo=1", List.of()));
        assertThat(audioMedia.telephoneEvent()).isEqualTo(
                new NegotiatedFormat("telephone-event", 8000, 1, 101, 100, "0-16", null, List.of()));

        assertThat(media.get(1).rejected()).isTrue();
        assertThat(media.get(1).canReceive()).isFalse();
    }

    @Test
    void offererMayNotSendToUnspecifiedAnswerAddress() {
        SessionDescription offer = negotiator.createOffer("-", "10.0.0.1", 9, 1,
                List.of(SdpMediaConfig.audio(null, 4000, List.of(PCMU))));
        SessionDescription answer = negotiator.createAnswer(offer, "-", "0.0.0.0", 1, 1,
                List.of(SdpMediaConfig.audio(null, 6000, List.of(PCMU)))).answer();

        assertThat(negotiator.processAnswer(offer, answer).getFirst().direction())
                .isEqualTo(MediaDirection.RECVONLY);
    }

    @Test
    void fullOfferAnswerRoundTrip() {
        SessionDescription offer = negotiator.createOffer("-", "10.0.0.1", 9, 1, List.of(
                SdpMediaConfig.audio(null, 4000, List.of(OPUS, PCMU, DTMF))));
        NegotiationResult answered = negotiator.createAnswer(SdpParser.parse(offer.toString()), "-", "10.0.0.2",
                3, 1, List.of(SdpMediaConfig.audio(null, 6000,
                        List.of(new RtpMap(0, "PCMU", 8000), new RtpMap(97, "telephone-event", 8000)))));

        List<NegotiatedMedia> offerer = negotiator.processAnswer(offer, SdpParser.parse(answered.answer().toString()));

        NegotiatedFormat offererView = offerer.getFirst().primaryFormat();
        NegotiatedFormat answererView = answered.media().getFirst().primaryFormat();
        assertThat(offererView.encodingName()).isEqualTo("PCMU");
        assertThat(offererView.remotePayloadType()).isEqualTo(answererView.localPayloadType());
        assertThat(offerer.getFirst().telephoneEvent().remotePayloadType()).isEqualTo(101);
        assertThat(offerer.getFirst().remoteAddress()).isEqualTo("10.0.0.2");
        assertThat(answered.media().getFirst().remoteAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void rejectsAnswerWithWrongMediaLines() {
        SessionDescription offer = SdpParser.parse(SampleSdp.RFC4317_2_1_OFFER);
        SessionDescription answer = SdpParser.parse(SampleSdp.RFC4317_2_1_ANSWER);
        answer.media().removeLast();
        assertThatThrownBy(() -> negotiator.processAnswer(offer, answer))
                .isInstanceOf(SdpNegotiationException.class);

        SessionDescription swapped = SdpParser.parse(SampleSdp.RFC4317_2_1_ANSWER);
        swapped.media().getFirst().setMedia("video");
        assertThatThrownBy(() -> negotiator.processAnswer(offer, swapped))
                .isInstanceOf(SdpNegotiationException.class);
    }

    private NegotiationResult assertAnswerDirection(SessionDescription offer, SdpMediaConfig local,
                                                    MediaDirection expected) {
        NegotiationResult result = negotiator.createAnswer(offer, "-", "10.0.0.5", 1, 1, List.of(local));
        assertThat(result.answer().media().getFirst().direction()).isEqualTo(expected);
        assertThat(result.media().getFirst().direction()).isEqualTo(expected);
        return result;
    }
}
