package com.github.mikekwright.sip.softphone.nat;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Discovers the public (server reflexive) address of a UDP socket with a STUN Binding request (RFC 8489 §6).
 *
 * <p>The request is sent from the very socket whose mapping we want to learn (the SIP or RTP channel), so the NAT
 * binding it creates is the one remote parties will use. Retransmission follows RFC 8489 §6.2.1: the first
 * retransmission after {@code RTO}, doubling every time, until the overall timeout expires.</p>
 */
public final class StunClient {

    private static final Logger log = LoggerFactory.getLogger(StunClient.class);
    private static final Executor DNS = Executors.newVirtualThreadPerTaskExecutor();

    /** Initial retransmission timeout (RFC 8489 recommends 500 ms; LANs answer far quicker). */
    public static final Duration DEFAULT_RTO = Duration.ofMillis(250);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(4);

    private StunClient() {
    }

    /**
     * Parses {@code host[:port]} (default port 3478, RFC 8489 §9) and resolves it off the event loop.
     */
    public static CompletableFuture<InetSocketAddress> resolve(String server) {
        String s = server.trim();
        if (s.startsWith("stun:")) {
            s = s.substring(5);
        }
        String host = s;
        int port = 3478;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            host = s.substring(0, colon);
            port = Integer.parseInt(s.substring(colon + 1));
        }
        String h = host;
        int p = port;
        return CompletableFuture.supplyAsync(() -> new InetSocketAddress(h, p), DNS).thenApply(a -> {
            if (a.isUnresolved()) {
                throw new IllegalArgumentException("cannot resolve STUN server " + server);
            }
            return a;
        });
    }

    /** Discovers the reflexive address of {@code channel} using a STUN server given as {@code host[:port]}. */
    public static CompletableFuture<InetSocketAddress> discover(Channel channel, String server) {
        return resolve(server).thenCompose(addr -> discover(channel, addr, DEFAULT_RTO, DEFAULT_TIMEOUT));
    }

    /**
     * Sends a Binding request from {@code channel} to {@code server} and completes with the XOR-MAPPED-ADDRESS of
     * the success response.
     */
    public static CompletableFuture<InetSocketAddress> discover(Channel channel, InetSocketAddress server,
                                                                Duration rto, Duration timeout) {
        StunHandler handler = StunHandler.install(channel);
        StunMessage request = StunMessage.bindingRequest();
        CompletableFuture<StunMessage> response = handler.expect(request);
        long deadline = System.nanoTime() + timeout.toNanos();
        send(channel, request, server, rto.toMillis(), deadline, response);
        return response.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).handle((r, err) -> {
            if (err != null) {
                Throwable cause = err instanceof TimeoutException ? new TimeoutException(
                        "no STUN response from " + server + " within " + timeout.toMillis() + " ms") : err;
                throw new java.util.concurrent.CompletionException(cause);
            }
            if (!r.isSuccessResponse() || r.mappedAddress() == null) {
                throw new IllegalStateException("STUN server " + server + " answered " + r);
            }
            log.debug("STUN: {} is reachable as {}", channel.localAddress(), r.mappedAddress());
            return r.mappedAddress();
        });
    }

    private static void send(Channel channel, StunMessage request, InetSocketAddress server, long rto,
                             long deadline, CompletableFuture<StunMessage> response) {
        if (response.isDone() || !channel.isActive() || System.nanoTime() > deadline) {
            return;
        }
        ByteBuf buf = channel.alloc().buffer(48);
        request.encode(buf);
        channel.writeAndFlush(new DatagramPacket(buf, server));
        channel.eventLoop().schedule(() -> send(channel, request, server, rto * 2, deadline, response),
                rto, TimeUnit.MILLISECONDS);
    }
}
