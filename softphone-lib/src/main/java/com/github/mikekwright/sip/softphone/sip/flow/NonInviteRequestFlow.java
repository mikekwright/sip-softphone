package com.github.mikekwright.sip.softphone.sip.flow;

import com.github.mikekwright.sip.softphone.sip.core.SipStack;
import com.github.mikekwright.sip.softphone.sip.message.CSeq;
import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipStatus;
import com.github.mikekwright.sip.softphone.sip.transaction.ClientTransaction;
import com.github.mikekwright.sip.softphone.sip.transaction.ClientTransactionListener;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Sends a non-INVITE request and completes with its final response (RFC 3261 §8.1.3), transparently answering
 * 401/407 digest challenges once (§22.2, §22.3).
 *
 * <pre>
 *   UAC                         UAS / proxy
 *    |-------- REQUEST ----------->|
 *    |&lt;------- 401 / 407 ----------|   challenge (WWW-/Proxy-Authenticate)
 *    |-------- REQUEST ----------->|   same Call-ID, CSeq+1, new branch, Authorization
 *    |&lt;------- 200 OK -------------|
 * </pre>
 *
 * Transaction timeouts complete the future with a locally generated 408 and transport failures with a 503
 * (§8.1.3.1), so callers always observe a response.
 */
public final class NonInviteRequestFlow {

    private static final int MAX_AUTH_ATTEMPTS = 2;

    private final SipStack stack;
    private final LongSupplier nextCseq;
    private final Consumer<SipResponse> provisionalListener;
    private final CompletableFuture<SipResponse> result = new CompletableFuture<>();
    private int attempts;

    private NonInviteRequestFlow(SipStack stack, LongSupplier nextCseq, Consumer<SipResponse> provisionalListener) {
        this.stack = stack;
        this.nextCseq = nextCseq;
        this.provisionalListener = provisionalListener;
    }

    /** Sends an out-of-dialog request; re-sent requests increment the request's own CSeq. */
    public static CompletableFuture<SipResponse> send(SipStack stack, SipRequest request) {
        long[] seq = {request.cseq().number()};
        return send(stack, request, () -> ++seq[0], r -> {
        });
    }

    /**
     * @param nextCseq            supplies the CSeq number for a re-sent request (dialogs pass their counter)
     * @param provisionalListener receives 1xx responses
     */
    public static CompletableFuture<SipResponse> send(SipStack stack, SipRequest request, LongSupplier nextCseq,
                                                      Consumer<SipResponse> provisionalListener) {
        NonInviteRequestFlow flow = new NonInviteRequestFlow(stack, nextCseq, provisionalListener);
        flow.attempt(request);
        return flow.result;
    }

    private void attempt(SipRequest request) {
        attempts++;
        stack.sendRequest(request, new ClientTransactionListener() {
            @Override
            public void onResponse(ClientTransaction tx, SipResponse response) {
                if (response.isProvisional()) {
                    provisionalListener.accept(response);
                    return;
                }
                int code = response.statusCode();
                if ((code == SipStatus.UNAUTHORIZED || code == SipStatus.PROXY_AUTHENTICATION_REQUIRED)
                        && attempts < MAX_AUTH_ATTEMPTS) {
                    SipRequest retry = request.copy();
                    retry.headers().remove(HeaderNames.VIA);
                    retry.setHeader(HeaderNames.CSEQ, new CSeq(nextCseq.getAsLong(), request.method()));
                    if (stack.authenticator().authorize(retry, response)) {
                        attempt(retry);
                        return;
                    }
                }
                result.complete(response);
            }

            @Override
            public void onTimeout(ClientTransaction tx) {
                result.complete(localResponse(request, SipStatus.REQUEST_TIMEOUT));
            }

            @Override
            public void onTransportError(ClientTransaction tx, Throwable cause) {
                result.complete(localResponse(request, SipStatus.SERVICE_UNAVAILABLE));
            }
        }).exceptionally(err -> {
            result.complete(localResponse(request, SipStatus.SERVICE_UNAVAILABLE));
            return null;
        });
    }

    /** A response synthesized locally to represent a timeout or transport failure (RFC 3261 §8.1.3.1). */
    public static SipResponse localResponse(SipRequest request, int status) {
        SipResponse r = new SipResponse(status);
        r.headers().copyFrom(request.headers(), HeaderNames.VIA);
        r.headers().add(HeaderNames.FROM, request.header(HeaderNames.FROM));
        r.headers().add(HeaderNames.TO, request.header(HeaderNames.TO));
        r.headers().add(HeaderNames.CALL_ID, request.header(HeaderNames.CALL_ID));
        r.headers().add(HeaderNames.CSEQ, request.header(HeaderNames.CSEQ));
        r.setRawBody(new byte[0]);
        return r;
    }
}
