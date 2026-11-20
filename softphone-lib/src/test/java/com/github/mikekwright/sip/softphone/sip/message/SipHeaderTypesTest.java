package com.github.mikekwright.sip.softphone.sip.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SipHeaderTypesTest {

    @Test
    void parsesFullSipUri() {
        SipUri u = SipUri.parse("sips:alice:secret@atlanta.com:5061;transport=tcp;lr?subject=project%20x");
        assertThat(u.scheme()).isEqualTo("sips");
        assertThat(u.isSecure()).isTrue();
        assertThat(u.user()).isEqualTo("alice");
        assertThat(u.password()).isEqualTo("secret");
        assertThat(u.host()).isEqualTo("atlanta.com");
        assertThat(u.port()).isEqualTo(5061);
        assertThat(u.transport()).isEqualTo("tcp");
        assertThat(u.isLooseRouter()).isTrue();
        assertThat(u.headers()).isEqualTo("subject=project%20x");
        assertThat(u).hasToString("sips:alice:secret@atlanta.com:5061;transport=tcp;lr?subject=project%20x");
    }

    @Test
    void parsesIpv6AndUserParams() {
        SipUri u = SipUri.parse("sip:+1-212-555-1212;phone-context=example.com@[2001:db8::10]:5070");
        assertThat(u.user()).isEqualTo("+1-212-555-1212;phone-context=example.com");
        assertThat(u.host()).isEqualTo("[2001:db8::10]");
        assertThat(u.hostAddress()).isEqualTo("2001:db8::10");
        assertThat(u.port()).isEqualTo(5070);
    }

    @Test
    void defaultsPorts() {
        assertThat(SipUri.parse("sip:a@b").portOrDefault()).isEqualTo(5060);
        assertThat(SipUri.parse("sips:a@b").portOrDefault()).isEqualTo(5061);
        assertThat(SipUri.parse("sip:a@b;transport=tls").portOrDefault()).isEqualTo(5061);
    }

    @Test
    void comparesUrisPerRfc3261() {
        assertThat(SipUri.parse("sip:alice@AtLanTa.CoM;Transport=udp"))
                .isEqualTo(SipUri.parse("sip:alice@atlanta.com;transport=UDP"));
        assertThat(SipUri.parse("sip:alice@atlanta.com")).isNotEqualTo(SipUri.parse("sip:ALICE@atlanta.com"));
        assertThat(SipUri.parse("sip:a@b:5060")).isNotEqualTo(SipUri.parse("sip:a@b"));
    }

    @Test
    void keepsOtherSchemesOpaque() {
        SipUri tel = SipUri.parse("tel:+358-555-1234567;postd=pp22");
        assertThat(tel.isSip()).isFalse();
        assertThat(tel.user()).isEqualTo("+358-555-1234567");
        assertThat(tel).hasToString("tel:+358-555-1234567;postd=pp22");
    }

    @Test
    void rejectsBadUris() {
        assertThatThrownBy(() -> SipUri.parse("alice@atlanta.com")).isInstanceOf(SipParseException.class);
        assertThatThrownBy(() -> SipUri.parse("sip:a@b:99999")).isInstanceOf(SipParseException.class);
        assertThatThrownBy(() -> SipUri.parse("sip:a@")).isInstanceOf(SipParseException.class);
    }

    @Test
    void parsesNameAddressForms() {
        NameAddress quoted = NameAddress.parse("\"Bob \\\"B\\\" Smith\" <sip:bob@biloxi.com;transport=tcp>;tag=a6c85cf");
        assertThat(quoted.displayName()).isEqualTo("Bob \"B\" Smith");
        assertThat(quoted.uri().transport()).isEqualTo("tcp");
        assertThat(quoted.tag()).isEqualTo("a6c85cf");

        // without brackets the parameters belong to the header, not the URI (RFC 3261 §20.10)
        NameAddress bare = NameAddress.parse("sip:carol@chicago.com;tag=887s");
        assertThat(bare.tag()).isEqualTo("887s");
        assertThat(bare.uri().hasParameter("tag")).isFalse();

        assertThat(NameAddress.parse("*").isWildcard()).isTrue();
        assertThat(new NameAddress("Alice Smith", SipUri.parse("sip:a@b")).withTag("1"))
                .hasToString("\"Alice Smith\" <sip:a@b>;tag=1");
    }

    @Test
    void parsesViaVariants() {
        Via v = Via.parse("SIP / 2.0 / TCP 192.0.2.4:5080 ;branch=z9hG4bKx;rport;received=10.0.0.1");
        assertThat(v.transport()).isEqualTo("TCP");
        assertThat(v.host()).isEqualTo("192.0.2.4");
        assertThat(v.port()).isEqualTo(5080);
        assertThat(v.hasRport()).isTrue();
        assertThat(v.rport()).isEqualTo(-1);
        assertThat(v.received()).isEqualTo("10.0.0.1");
        assertThat(v.hasRfc3261Branch()).isTrue();

        Via v6 = Via.parse("SIP/2.0/UDP [2001:db8::9:1];branch=z9hG4bKas3");
        assertThat(v6.host()).isEqualTo("[2001:db8::9:1]");
        assertThat(v6.portOrDefault()).isEqualTo(5060);
        assertThat(Via.parse("SIP/2.0/TLS h;branch=1").portOrDefault()).isEqualTo(5061);
    }

    @Test
    void splitsListsRespectingQuotesAndBrackets() {
        assertThat(SipSyntax.splitList("<sip:a@b;x=1,2>, \"Doe, John\" <sip:c@d>,sip:e@f"))
                .containsExactly("<sip:a@b;x=1,2>", "\"Doe, John\" <sip:c@d>", "sip:e@f");
    }

    @Test
    void headersPreserveOrderAndPushPop() {
        SipHeaders h = new SipHeaders();
        h.add("Via", "SIP/2.0/UDP a;branch=z9hG4bK1");
        h.add("Route", "<sip:p1;lr>, <sip:p2;lr>");
        h.addFirst("v", "SIP/2.0/UDP b;branch=z9hG4bK2");
        assertThat(h.all("Via")).containsExactly("SIP/2.0/UDP b;branch=z9hG4bK2", "SIP/2.0/UDP a;branch=z9hG4bK1");
        assertThat(h.all("route")).hasSize(2);
        assertThat(h.removeFirst("Route")).contains("<sip:p1;lr>");
        assertThat(h.all("Route")).containsExactly("<sip:p2;lr>");
    }

    @Test
    void cseqParsing() {
        assertThat(CSeq.parse(" 4711   INVITE ")).isEqualTo(new CSeq(4711, SipMethod.INVITE));
        assertThatThrownBy(() -> CSeq.parse("INVITE")).isInstanceOf(SipParseException.class);
    }
}
