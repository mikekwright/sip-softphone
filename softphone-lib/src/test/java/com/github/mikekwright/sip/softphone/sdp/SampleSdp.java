package com.github.mikekwright.sip.softphone.sdp;

/** Real-world and RFC example session descriptions shared by the SDP tests. */
final class SampleSdp {

    private SampleSdp() {
    }

    /** Converts LF line endings to CRLF as sent on the wire. */
    static String crlf(String sdp) {
        return sdp.replace("\n", "\r\n");
    }

    /** Asterisk style audio offer with PCMU, PCMA and telephone-event. */
    static final String ASTERISK_AUDIO_OFFER = crlf("""
            v=0
            o=- 1928374650 1928374650 IN IP4 192.0.2.10
            s=Asterisk
            c=IN IP4 192.0.2.10
            t=0 0
            m=audio 17552 RTP/AVP 0 8 101
            a=rtpmap:0 PCMU/8000
            a=rtpmap:8 PCMA/8000
            a=rtpmap:101 telephone-event/8000
            a=fmtp:101 0-16
            a=ptime:20
            a=maxptime:150
            a=sendrecv
            """);

    /** FreeSWITCH style audio offer with an explicit RTCP port (RFC 3605) and static types without rtpmap. */
    static final String FREESWITCH_AUDIO_OFFER = """
            v=0
            o=FreeSWITCH 1700000000 1700000001 IN IP4 198.51.100.20
            s=FreeSWITCH
            c=IN IP4 198.51.100.20
            t=0 0
            m=audio 24680 RTP/AVP 9 0 8 18 101 13
            a=rtpmap:101 telephone-event/8000
            a=fmtp:101 0-16
            a=fmtp:18 annexb=no
            a=ptime:20
            a=rtcp:24681 IN IP4 198.51.100.20
            """;

    /** Audio + video offer with opus, VP8 and two H.264 modes, RTCP feedback and rtcp-mux. */
    static final String VIDEO_OFFER = crlf("""
            v=0
            o=- 20518 0 IN IP4 203.0.113.1
            s=-
            c=IN IP4 203.0.113.1
            b=AS:2048
            t=0 0
            m=audio 54400 RTP/AVPF 111 0 101
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;useinbandfec=1
            a=rtpmap:0 PCMU/8000
            a=rtpmap:101 telephone-event/8000
            a=rtcp-mux
            a=sendrecv
            m=video 55400 RTP/AVPF 96 97 98
            b=TIAS:1500000
            a=rtpmap:96 VP8/90000
            a=rtcp-fb:96 nack
            a=rtcp-fb:96 nack pli
            a=rtcp-fb:96 ccm fir
            a=rtpmap:97 H264/90000
            a=fmtp:97 profile-level-id=42e01f;level-asymmetry-allowed=1;packetization-mode=1
            a=rtcp-fb:97 nack
            a=rtcp-fb:97 nack pli
            a=rtpmap:98 H264/90000
            a=fmtp:98 profile-level-id=42e01f;level-asymmetry-allowed=1
            a=rtcp-fb:* goog-remb
            a=rtcp:55400 IN IP4 203.0.113.1
            a=rtcp-mux
            a=sendrecv
            """);

    /** The example of RFC 8866 §5. */
    static final String RFC8866_EXAMPLE = crlf("""
            v=0
            o=jdoe 3724394400 3724394405 IN IP4 198.51.100.1
            s=Call to John Smith
            i=SDP Offer #1
            u=http://www.jdoe.example.com/home.html
            e=Jane Doe <jane@jdoe.example.com>
            p=+1 617 555-6011
            c=IN IP4 198.51.100.1
            t=0 0
            m=audio 49170 RTP/AVP 0
            m=audio 49180 RTP/AVP 0
            m=video 51372 RTP/AVP 99
            c=IN IP6 2001:db8::2
            a=rtpmap:99 h263-1998/90000
            """);

    /** RFC 4317 §2.1 offer (Alice). */
    static final String RFC4317_2_1_OFFER = crlf("""
            v=0
            o=alice 2890844526 2890844526 IN IP4 host.atlanta.example.com
            s=
            c=IN IP4 host.atlanta.example.com
            t=0 0
            m=audio 49170 RTP/AVP 0 8 97
            a=rtpmap:0 PCMU/8000
            a=rtpmap:8 PCMA/8000
            a=rtpmap:97 iLBC/8000
            m=video 51372 RTP/AVP 31 32
            a=rtpmap:31 H261/90000
            a=rtpmap:32 MPV/90000
            """);

    /** RFC 4317 §2.1 answer (Bob). */
    static final String RFC4317_2_1_ANSWER = crlf("""
            v=0
            o=bob 2808844564 2808844564 IN IP4 host.biloxi.example.com
            s=
            c=IN IP4 host.biloxi.example.com
            t=0 0
            m=audio 49174 RTP/AVP 0
            a=rtpmap:0 PCMU/8000
            m=video 49170 RTP/AVP 32
            a=rtpmap:32 MPV/90000
            """);

    /** RFC 4317 §2.2 answer (Bob) to the §2.1 offer: two audio codecs, video rejected. */
    static final String RFC4317_2_2_ANSWER = crlf("""
            v=0
            o=bob 2808844564 2808844564 IN IP4 host.biloxi.example.com
            s=
            c=IN IP4 host.biloxi.example.com
            t=0 0
            m=audio 49172 RTP/AVP 0 8
            a=rtpmap:0 PCMU/8000
            a=rtpmap:8 PCMA/8000
            m=video 0 RTP/AVP 31
            a=rtpmap:31 H261/90000
            """);
}
