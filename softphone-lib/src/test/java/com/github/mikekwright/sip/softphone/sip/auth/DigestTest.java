package com.github.mikekwright.sip.softphone.sip.auth;

import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DigestTest {

    @Test
    void matchesRfc2617Example() {
        // RFC 2617 §3.5
        String response = DigestAuthenticator.computeResponse(DigestAlgorithm.MD5, "Mufasa", "testrealm@host.com",
                "Circle Of Life", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "0a4f113b", "00000001", "auth", "GET",
                "/dir/index.html", null);
        assertThat(response).isEqualTo("6629fae49393a05397450978507c4ef1");
    }

    @Test
    void matchesRfc7616Sha256Example() {
        // RFC 7616 §3.9.1
        String response = DigestAuthenticator.computeResponse(DigestAlgorithm.SHA_256, "Mufasa",
                "http-auth@example.org", "Circle of Life",
                "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ",
                "00000001", "auth", "GET", "/dir/index.html", null);
        assertThat(response).isEqualTo("753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1");
    }

    @Test
    void parsesAndFormatsChallenge() {
        AuthHeader h = AuthHeader.parse("Digest realm=\"atlanta.com\", domain=\"sip:ss1.carrier.com\", "
                + "qop=\"auth,auth-int\", nonce=\"f84f1cec41e6cbe5aea9c8e88d359\", opaque=\"\", stale=FALSE, "
                + "algorithm=MD5");
        assertThat(h.isDigest()).isTrue();
        assertThat(h.get("realm")).isEqualTo("atlanta.com");
        assertThat(h.get("qop")).isEqualTo("auth,auth-int");
        assertThat(h.get("opaque")).isEmpty();
        assertThat(h.parameters()).containsEntry("algorithm", "MD5");
        assertThat(new AuthHeader("Digest", Map.of("qop", "auth")).format(false)).isEqualTo("Digest qop=\"auth\"");
    }

    @Test
    void clientAnswersServerChallenge() {
        for (DigestAlgorithm alg : DigestAlgorithm.values()) {
            DigestServer server = new DigestServer("example.com", alg, u -> u.equals("alice") ? "s3cret" : null);
            SipRequest req = new SipRequest(SipMethod.REGISTER, SipUri.parse("sip:example.com"));
            SipResponse challenge = server.challenge(new SipResponse(401), false);

            assertThat(server.verify(req, false)).isEqualTo(DigestServer.Result.MISSING);
            DigestAuthenticator client = new DigestAuthenticator(CredentialsProvider.of(
                    new Credentials("alice", "s3cret")));
            assertThat(client.authorize(req, challenge)).isTrue();
            assertThat(server.verify(req, false)).as(alg.token()).isEqualTo(DigestServer.Result.OK);

            SipRequest wrong = new SipRequest(SipMethod.REGISTER, SipUri.parse("sip:example.com"));
            new DigestAuthenticator(CredentialsProvider.of(new Credentials("alice", "nope")))
                    .authorize(wrong, challenge);
            assertThat(server.verify(wrong, false)).isEqualTo(DigestServer.Result.FAILED);
        }
    }

    @Test
    void noCredentialsForRealm() {
        DigestServer server = new DigestServer("other.com", DigestAlgorithm.MD5, u -> "x");
        SipRequest req = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@other.com"));
        DigestAuthenticator client = new DigestAuthenticator(CredentialsProvider.of(
                new Credentials("alice", "pw", "example.com")));
        assertThat(client.authorize(req, server.challenge(new SipResponse(407), false))).isFalse();
    }
}
