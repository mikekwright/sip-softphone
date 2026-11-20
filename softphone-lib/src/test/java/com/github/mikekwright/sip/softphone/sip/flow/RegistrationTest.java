package com.github.mikekwright.sip.softphone.sip.flow;

import com.github.mikekwright.sip.softphone.sip.auth.Credentials;
import com.github.mikekwright.sip.softphone.sip.auth.CredentialsProvider;
import com.github.mikekwright.sip.softphone.sip.auth.DigestAlgorithm;
import com.github.mikekwright.sip.softphone.sip.auth.DigestServer;
import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.core.TestStacks;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transaction.TimerSettings;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationTest {

    private SipStack ua;
    private SipStack registrar;

    @AfterEach
    void tearDown() {
        ua.close();
        registrar.close();
    }

    /** A minimal digest-protected registrar (RFC 3261 §10.3) built on the stack's extension points. */
    private Map<String, String> startRegistrar(int minExpires) {
        registrar = TestStacks.start(TransportType.UDP);
        DigestServer auth = new DigestServer("example.com", DigestAlgorithm.MD5, u -> "secret");
        Map<String, String> bindings = new ConcurrentHashMap<>();
        registrar.registerHandler(SipMethod.REGISTER, in -> {
            if (auth.verify(in.request(), false) != DigestServer.Result.OK) {
                in.respond(auth.challenge(in.createResponse(401, registrar.messages().newTag()), false));
                return;
            }
            int expires = in.request().expires().orElse(3600);
            if (expires > 0 && expires < minExpires) {
                in.respond(423, r -> r.setHeader(HeaderNames.MIN_EXPIRES, minExpires));
                return;
            }
            String aor = in.request().to().uri().toString();
            NameAddress contact = in.request().contact().orElseThrow();
            if (expires == 0) {
                bindings.remove(aor);
            } else {
                bindings.put(aor, contact.uri().toString());
            }
            in.respond(200, r -> {
                if (expires > 0) {
                    r.addHeader(HeaderNames.CONTACT, contact.withParameter("expires", String.valueOf(expires)));
                }
            });
        });
        return bindings;
    }

    @Test
    void registersWithDigestAuthAndUnregisters() throws Exception {
        Map<String, String> bindings = startRegistrar(60);
        ua = TestStacks.start(TransportType.UDP, TimerSettings.DEFAULT,
                CredentialsProvider.of(new Credentials("alice", "secret")));
        List<RegistrationState> states = new CopyOnWriteArrayList<>();
        SipUri registrarUri = TestStacks.uri(registrar, null);
        Registration reg = new Registration(ua, registrarUri,
                new NameAddress(SipUri.sip("alice", "example.com")), "alice", TransportType.UDP, 30,
                (s, r) -> states.add(s));

        SipResponse r = reg.register().get(5, TimeUnit.SECONDS);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(reg.state()).isEqualTo(RegistrationState.REGISTERED);
        // 30 s was too brief: retried with Min-Expires
        assertThat(reg.grantedExpires()).isEqualTo(60);
        assertThat(bindings).containsKey("sip:alice@example.com");
        assertThat(bindings.get("sip:alice@example.com")).startsWith("sip:alice@127.0.0.1:");

        assertThat(reg.unregister().get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(reg.state()).isEqualTo(RegistrationState.UNREGISTERED);
        assertThat(bindings).isEmpty();
        assertThat(states).contains(RegistrationState.REGISTERING, RegistrationState.REGISTERED,
                RegistrationState.UNREGISTERED);
    }

    @Test
    void wrongPasswordFails() throws Exception {
        startRegistrar(1);
        ua = TestStacks.start(TransportType.UDP, TimerSettings.DEFAULT,
                CredentialsProvider.of(new Credentials("alice", "wrong")));
        Registration reg = new Registration(ua, TestStacks.uri(registrar, null),
                new NameAddress(SipUri.sip("alice", "example.com")), "alice", TransportType.UDP, 300, null);
        assertThat(reg.register().get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        assertThat(reg.state()).isEqualTo(RegistrationState.FAILED);
    }
}
