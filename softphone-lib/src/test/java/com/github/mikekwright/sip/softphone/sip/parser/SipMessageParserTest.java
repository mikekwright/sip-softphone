package com.github.mikekwright.sip.softphone.sip.parser;

import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipParseException;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.Via;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SipMessageParserTest {

    /** RFC 3261 §24.2, message F1 (with a short SDP body). */
    static final String INVITE = """
            INVITE sip:bob@biloxi.com SIP/2.0\r
            Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bKnashds8\r
            Max-Forwards: 70\r
            To: Bob <sip:bob@biloxi.com>\r
            From: Alice <sip:alice@atlanta.com>;tag=1928301774\r
            Call-ID: a84b4c76e66710\r
            CSeq: 314159 INVITE\r
            Contact: <sip:alice@pc33.atlanta.com>\r
            Content-Type: application/sdp\r
            Content-Length: 12\r
            \r
            v=0\r
            o=x y\r
            """;

    @Test
    void parsesRfc3261Invite() {
        SipRequest r = (SipRequest) SipMessageParser.parse(INVITE);
        assertThat(r.method()).isEqualTo(SipMethod.INVITE);
        assertThat(r.requestUri().user()).isEqualTo("bob");
        assertThat(r.requestUri().host()).isEqualTo("biloxi.com");
        assertThat(r.topVia().branch()).isEqualTo("z9hG4bKnashds8");
        assertThat(r.topVia().transport()).isEqualTo("UDP");
        assertThat(r.from().displayName()).isEqualTo("Alice");
        assertThat(r.from().tag()).isEqualTo("1928301774");
        assertThat(r.to().tag()).isNull();
        assertThat(r.callId()).isEqualTo("a84b4c76e66710");
        assertThat(r.cseq().number()).isEqualTo(314159);
        assertThat(r.cseq().method()).isEqualTo(SipMethod.INVITE);
        assertThat(r.maxForwards()).contains(70);
        assertThat(r.contentType()).isEqualTo("application/sdp");
        assertThat(r.bodyAsString()).isEqualTo("v=0\r\no=x y\r\n");
    }

    @Test
    void roundTripsThroughEncode() {
        SipMessage first = SipMessageParser.parse(INVITE);
        SipMessage second = SipMessageParser.parse(first.encode());
        assertThat(second.toString()).isEqualTo(first.toString());
    }

    @Test
    void expandsCompactFormsFoldingAndLists() {
        String msg = "OPTIONS sip:carol@chicago.com SIP/2.0\n"
                + "v: SIP/2.0/UDP a.example.com;branch=z9hG4bK1, SIP/2.0/TCP b.example.com:5070;branch=z9hG4bK2\n"
                + "f: <sip:a@b>;tag=1\n"
                + "t: <sip:c@d>\n"
                + "i: abc\n"
                + "Subject: I know you're there,\n"
                + "   pick up the phone\n"
                + "CSeq   :   1 OPTIONS\n"
                + "l: 0\n\n";
        SipRequest r = (SipRequest) SipMessageParser.parse(msg);
        List<Via> vias = r.vias();
        assertThat(vias).hasSize(2);
        assertThat(vias.get(1).transport()).isEqualTo("TCP");
        assertThat(vias.get(1).port()).isEqualTo(5070);
        assertThat(r.header("Subject")).isEqualTo("I know you're there, pick up the phone");
        assertThat(r.callId()).isEqualTo("abc");
        assertThat(r.header(HeaderNames.FROM)).isEqualTo("<sip:a@b>;tag=1");
        assertThat(r.cseq().method()).isEqualTo(SipMethod.OPTIONS);
    }

    @Test
    void parsesResponse() {
        SipResponse r = (SipResponse) SipMessageParser.parse("""
                SIP/2.0 180 Ringing\r
                Via: SIP/2.0/UDP host;branch=z9hG4bK1;received=1.2.3.4;rport=5062\r
                From: <sip:a@b>;tag=1\r
                To: <sip:c@d>;tag=2\r
                Call-ID: x\r
                CSeq: 1 INVITE\r
                \r
                """);
        assertThat(r.statusCode()).isEqualTo(180);
        assertThat(r.reasonPhrase()).isEqualTo("Ringing");
        assertThat(r.topVia().received()).isEqualTo("1.2.3.4");
        assertThat(r.topVia().rport()).isEqualTo(5062);
        assertThat(r.to().tag()).isEqualTo("2");
    }

    @Test
    void datagramWithoutContentLengthTakesRemainder() {
        SipMessage m = SipMessageParser.parse("MESSAGE sip:a@b SIP/2.0\r\nVia: SIP/2.0/UDP h;branch=z9hG4bK1\r\n"
                + "Content-Type: text/plain\r\n\r\nhello");
        assertThat(m.bodyAsString()).isEqualTo("hello");
    }

    @Test
    void datagramBodyTruncatedToContentLength() {
        SipMessage m = SipMessageParser.parse("MESSAGE sip:a@b SIP/2.0\r\nContent-Length: 3\r\n\r\nhello");
        assertThat(m.bodyAsString()).isEqualTo("hel");
    }

    @Test
    void rejectsContentLengthLargerThanDatagram() {
        assertThatThrownBy(() -> SipMessageParser.parse("MESSAGE sip:a@b SIP/2.0\r\nContent-Length: 30\r\n\r\nhi"))
                .isInstanceOf(SipParseException.class);
    }

    @Test
    void ignoresLeadingCrlf() {
        SipMessage m = SipMessageParser.parse("\r\n\r\nBYE sip:a@b SIP/2.0\r\nContent-Length: 0\r\n\r\n");
        assertThat(m).isInstanceOf(SipRequest.class);
    }

    @Test
    void rejectsMalformedStartLines() {
        assertThatThrownBy(() -> SipMessageParser.parse("INVITE sip:a@b\r\n\r\n")).isInstanceOf(SipParseException.class);
        assertThatThrownBy(() -> SipMessageParser.parse("INVITE sip:a@b SIP/3.0\r\n\r\n"))
                .isInstanceOf(SipParseException.class);
        assertThatThrownBy(() -> SipMessageParser.parse("SIP/2.0 99 Bad\r\n\r\n")).isInstanceOf(SipParseException.class);
        assertThatThrownBy(() -> SipMessageParser.parse("BYE sip:a@b SIP/2.0\r\nNoColonHere\r\n\r\n"))
                .isInstanceOf(SipParseException.class);
    }
}
