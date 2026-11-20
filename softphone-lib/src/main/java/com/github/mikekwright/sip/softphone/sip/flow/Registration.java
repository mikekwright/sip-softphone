package com.github.mikekwright.sip.softphone.sip.flow;

import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipStatus;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Registration of a contact address with a registrar (RFC 3261 §10.2), including automatic refresh before the
 * binding expires, {@code 423 Interval Too Brief} handling (§10.2.8) and de-registration.
 *
 * <pre>
 *   UA                                   Registrar
 *    |--- REGISTER (Contact;expires=3600) -->|
 *    |&lt;-- 401 Unauthorized (nonce) ----------|
 *    |--- REGISTER + Authorization -------->|   CSeq+1, same Call-ID
 *    |&lt;-- 200 OK (Contact;expires=3600) ----|
 *    |          ... refresh before expiry ...
 *    |--- REGISTER (expires=0) ------------>|   unregister
 *    |&lt;-- 200 OK ---------------------------|
 * </pre>
 *
 * The same Call-ID is used for all REGISTERs of this binding and the CSeq increases (§10.2). Thread-safe: public
 * methods hop onto the SIP core executor.
 */
public final class Registration {

    private static final Logger log = LoggerFactory.getLogger(Registration.class);

    private final SipStack stack;
    private final SipUri registrar;
    private final NameAddress aor;
    private final String contactUser;
    private final TransportType contactTransport;
    private final int requestedExpires;
    private final String callId;
    private final BiConsumer<RegistrationState, SipResponse> listener;
    private long cseq = 0;
    private RegistrationState state = RegistrationState.UNREGISTERED;
    private int grantedExpires;
    private ScheduledFuture<?> refresh;
    private SipUri contactUri;

    /**
     * @param stack            the SIP stack
     * @param registrar        Request-URI of the REGISTER, normally {@code sip:domain} (§10.2)
     * @param aor              address-of-record placed in To and From
     * @param contactUser      user part of our Contact URI
     * @param contactTransport transport advertised in the Contact
     * @param expires          requested binding lifetime in seconds
     * @param listener         notified on every state change with the response that caused it (may be null)
     */
    public Registration(SipStack stack, SipUri registrar, NameAddress aor, String contactUser,
                        TransportType contactTransport, int expires,
                        BiConsumer<RegistrationState, SipResponse> listener) {
        this.stack = stack;
        this.registrar = Objects.requireNonNull(registrar);
        this.aor = Objects.requireNonNull(aor);
        this.contactUser = contactUser;
        this.contactTransport = contactTransport;
        this.requestedExpires = expires;
        this.callId = stack.messages().newCallId();
        this.listener = listener == null ? (s, r) -> {
        } : listener;
    }

    public RegistrationState state() {
        return state;
    }

    /** Lifetime granted by the registrar in seconds (0 when not registered). */
    public int grantedExpires() {
        return grantedExpires;
    }

    /** Registers (or refreshes). Completes with the final response. */
    public CompletableFuture<SipResponse> register() {
        return stack.submit(() -> send(requestedExpires)).thenCompose(f -> f);
    }

    /** Removes the binding (Expires: 0) and stops refreshing. */
    public CompletableFuture<SipResponse> unregister() {
        return stack.submit(() -> {
            cancelRefresh();
            return send(0);
        }).thenCompose(f -> f);
    }

    private CompletableFuture<SipResponse> send(int expires) {
        setState(expires == 0 ? RegistrationState.UNREGISTERING : RegistrationState.REGISTERING, null);
        contactUri = stack.messages().contactUri(contactUser, contactTransport);
        SipRequest request = stack.messages().createRequest(SipMethod.REGISTER, registrar,
                aor.tag() == null ? aor.withTag(stack.messages().newTag()) : aor, aor.withoutParameter("tag"),
                callId, ++cseq);
        request.addHeader(HeaderNames.CONTACT, new NameAddress(contactUri).withParameter("expires",
                String.valueOf(expires)));
        request.addHeader(HeaderNames.EXPIRES, expires);
        request.setRawBody(new byte[0]);
        return NonInviteRequestFlow.send(stack, request, () -> ++cseq, r -> {
        }).thenApplyAsync(response -> {
            handleResponse(response, expires);
            return response;
        }, stack.core()).thenCompose(r -> {
            // §10.2.8: retry once with the registrar's minimum
            if (r.statusCode() == SipStatus.INTERVAL_TOO_BRIEF && expires > 0) {
                int min = r.headers().first(HeaderNames.MIN_EXPIRES).map(String::trim).map(Integer::parseInt)
                        .orElse(expires * 2);
                if (min > expires) {
                    return send(min);
                }
            }
            return CompletableFuture.completedFuture(r);
        });
    }

    private void handleResponse(SipResponse response, int requested) {
        if (!response.isSuccess()) {
            if (response.statusCode() != SipStatus.INTERVAL_TOO_BRIEF) {
                setState(RegistrationState.FAILED, response);
            }
            return;
        }
        if (requested == 0) {
            grantedExpires = 0;
            setState(RegistrationState.UNREGISTERED, response);
            return;
        }
        grantedExpires = grantedExpires(response, requested);
        setState(RegistrationState.REGISTERED, response);
        scheduleRefresh(grantedExpires);
    }

    /** §10.2.4: the expiry of our binding is given by its Contact expires param, else the Expires header. */
    private int grantedExpires(SipResponse response, int requested) {
        for (NameAddress c : response.contacts()) {
            if (c.uri() != null && sameBinding(c.uri(), contactUri) && c.parameter("expires") != null) {
                try {
                    return Integer.parseInt(c.parameter("expires").trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return response.expires().orElse(requested);
    }

    private static boolean sameBinding(SipUri a, SipUri b) {
        return Objects.equals(a.user(), b.user()) && a.hostAddress().equalsIgnoreCase(b.hostAddress())
                && a.portOrDefault() == b.portOrDefault();
    }

    private void scheduleRefresh(int expires) {
        cancelRefresh();
        long delay = expires > 60 ? expires - 30 : Math.max(1, expires / 2);
        refresh = stack.core().schedule(() -> {
            if (state == RegistrationState.REGISTERED) {
                send(requestedExpires).exceptionally(e -> {
                    log.warn("Registration refresh failed", e);
                    return null;
                });
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelRefresh() {
        if (refresh != null) {
            refresh.cancel(false);
            refresh = null;
        }
    }

    private void setState(RegistrationState next, SipResponse response) {
        if (state != next || response != null) {
            state = next;
            try {
                listener.accept(next, response);
            } catch (RuntimeException e) {
                log.warn("Registration listener failed", e);
            }
        }
    }
}
