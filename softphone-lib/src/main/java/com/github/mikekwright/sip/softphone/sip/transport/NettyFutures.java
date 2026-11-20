package com.github.mikekwright.sip.softphone.sip.transport;

import io.netty.util.concurrent.Future;

import java.util.concurrent.CompletableFuture;

/** Bridges Netty futures to {@link CompletableFuture}. */
public final class NettyFutures {

    private NettyFutures() {
    }

    public static CompletableFuture<Void> toCompletable(Future<?> future) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        future.addListener(f -> {
            if (f.isSuccess()) {
                cf.complete(null);
            } else {
                cf.completeExceptionally(f.cause());
            }
        });
        return cf;
    }
}
