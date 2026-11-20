package com.github.mikekwright.sip.softphone.sip.core;

import com.github.mikekwright.sip.softphone.sip.auth.CredentialsProvider;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transaction.TimerSettings;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Helpers to run SIP stacks on the loopback interface in tests. */
public final class TestStacks {

    /** Fast timers so timeout paths complete in well under a second (64*T1 = 640 ms). */
    public static final TimerSettings FAST = new TimerSettings(Duration.ofMillis(10), Duration.ofMillis(40),
            Duration.ofMillis(50));

    private TestStacks() {
    }

    public static SipStack start(TransportType transport) {
        return start(transport, TimerSettings.DEFAULT, CredentialsProvider.NONE);
    }

    public static SipStack start(TransportType transport, TimerSettings timers, CredentialsProvider creds) {
        SipStackConfig.Builder b = SipStackConfig.builder()
                .listen(transport, "127.0.0.1", 0)
                .advertisedHost("127.0.0.1")
                .timers(timers)
                .credentials(creds)
                .ioThreads(2);
        if (transport != TransportType.UDP) {
            b.listen(TransportType.UDP, "127.0.0.1", 0);
        }
        SipStack stack = new SipStack(b.build());
        stack.start().join();
        return stack;
    }

    public static SipUri uri(SipStack stack, String user) {
        TransportType t = stack.config().defaultTransport();
        int port = stack.transport().transport(t).localAddress().getPort();
        SipUri u = SipUri.sip(user, "127.0.0.1", port);
        return t == TransportType.UDP ? u : u.withParameter("transport", t.uriParameter());
    }

    /** Records every message seen on the wire and can drop messages matching a predicate. */
    public static final class Recorder implements SipMessageInterceptor {
        public final List<String> sent = new CopyOnWriteArrayList<>();
        public final List<String> received = new CopyOnWriteArrayList<>();
        public volatile Predicate<SipMessage> dropOutgoing = m -> false;
        public volatile Predicate<SipMessage> dropIncoming = m -> false;

        @Override
        public SipMessage onSend(SipMessage message, TransportAddress destination) {
            if (dropOutgoing.test(message)) {
                return null;
            }
            sent.add(message.startLine());
            return message;
        }

        @Override
        public SipMessage onReceive(InboundMessage inbound) {
            if (dropIncoming.test(inbound.message())) {
                return null;
            }
            received.add(inbound.message().startLine());
            return inbound.message();
        }

        public long countSent(String prefix) {
            return sent.stream().filter(s -> s.startsWith(prefix)).count();
        }

        public long countReceived(String prefix) {
            return received.stream().filter(s -> s.startsWith(prefix)).count();
        }
    }
}
