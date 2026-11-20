package com.github.mikekwright.sip.softphone.sip.core;

import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.transaction.ServerTransaction;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;

import java.util.function.Consumer;

/**
 * A received request together with its server transaction and helpers to answer it (RFC 3261 §8.2.6).
 * Methods must be called on the SIP core executor (use {@link SipStack#execute} from other threads).
 */
public final class IncomingRequest {

    private final SipStack stack;
    private final ServerTransaction transaction;

    IncomingRequest(SipStack stack, ServerTransaction transaction) {
        this.stack = stack;
        this.transaction = transaction;
    }

    public SipRequest request() {
        return transaction.request();
    }

    public ServerTransaction transaction() {
        return transaction;
    }

    public InboundMessage origin() {
        return transaction.origin();
    }

    public SipStack stack() {
        return stack;
    }

    /** Creates a response with the mandatory headers copied from the request (RFC 3261 §8.2.6.2). */
    public SipResponse createResponse(int status) {
        return stack.messages().createResponse(request(), status, null);
    }

    /** Creates a response whose To header carries the given tag (dialog creating responses). */
    public SipResponse createResponse(int status, String toTag) {
        return stack.messages().createResponse(request(), status, toTag);
    }

    public void respond(SipResponse response) {
        transaction.sendResponse(response);
    }

    /** Sends a response with the default reason phrase; a To tag is generated for final responses if needed. */
    public void respond(int status) {
        respond(status, r -> {
        });
    }

    public void respond(int status, Consumer<SipResponse> customizer) {
        SipResponse response = createResponse(status, status > 100 ? stack.messages().newTag() : null);
        customizer.accept(response);
        transaction.sendResponse(response);
    }
}
