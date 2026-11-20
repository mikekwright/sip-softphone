package com.github.mikekwright.sip.softphone.sdp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SdpWriterTest {

    @Test
    void roundTripsRealWorldDescriptions() {
        for (String sdp : List.of(SampleSdp.ASTERISK_AUDIO_OFFER, SampleSdp.FREESWITCH_AUDIO_OFFER,
                SampleSdp.VIDEO_OFFER, SampleSdp.RFC8866_EXAMPLE, SampleSdp.RFC4317_2_1_OFFER,
                SampleSdp.RFC4317_2_2_ANSWER)) {
            SessionDescription parsed = SdpParser.parse(sdp);
            String written = SdpWriter.write(parsed);

            assertThat(SdpParser.parse(written)).isEqualTo(parsed);
            assertThat(parsed.toString()).isEqualTo(written);
        }
    }

    @Test
    void writesCanonicalTextForCrlfInput() {
        assertThat(SdpWriter.write(SdpParser.parse(SampleSdp.ASTERISK_AUDIO_OFFER)))
                .isEqualTo(SampleSdp.ASTERISK_AUDIO_OFFER);
        assertThat(SdpWriter.write(SdpParser.parse(SampleSdp.RFC8866_EXAMPLE)))
                .isEqualTo(SampleSdp.RFC8866_EXAMPLE);
    }

    @Test
    void writesLinesInRfc8866OrderWithCrlf() {
        SessionDescription sd = new SessionDescription()
                .setOrigin(Origin.of("alice", 42, 1, "10.0.0.1"))
                .addAttribute("tool", "test")
                .setConnection(Connection.ip("10.0.0.1"))
                .setSessionInformation("info")
                .setUri("http://example.com")
                .setBandwidths(List.of(new Bandwidth(Bandwidth.CT, 128)));
        sd.emails().add("a@example.com");
        sd.phones().add("+1 555 0100");
        sd.otherLines().add("z=0 0");
        MediaDescription audio = new MediaDescription("audio", 4000, "RTP/AVP")
                .addFormat(new RtpMap(0, "PCMU", 8000))
                .setDirection(MediaDirection.SENDONLY)
                .setConnection(Connection.ip("2001:db8::1"))
                .setInformation("voice");
        audio.bandwidths().add(new Bandwidth(Bandwidth.AS, 64));
        sd.addMedia(audio);

        assertThat(SdpWriter.write(sd)).isEqualTo(SampleSdp.crlf("""
                v=0
                o=alice 42 1 IN IP4 10.0.0.1
                s=-
                i=info
                u=http://example.com
                e=a@example.com
                p=+1 555 0100
                c=IN IP4 10.0.0.1
                b=CT:128
                t=0 0
                z=0 0
                a=tool:test
                m=audio 4000 RTP/AVP 0
                i=voice
                c=IN IP6 2001:db8::1
                b=AS:64
                a=rtpmap:0 PCMU/8000
                a=sendonly
                """));
    }

    @Test
    void copyIsDeep() {
        SessionDescription original = SdpParser.parse(SampleSdp.VIDEO_OFFER);
        SessionDescription copy = original.copy();
        assertThat(copy).isEqualTo(original).isNotSameAs(original);

        copy.media().getFirst().setPort(0).setDirection(MediaDirection.INACTIVE);
        copy.media().get(1).formats().clear();
        copy.addAttribute("x", null);
        copy.bandwidths().clear();

        assertThat(original).isEqualTo(SdpParser.parse(SampleSdp.VIDEO_OFFER));
        assertThat(copy).isNotEqualTo(original);
    }
}
