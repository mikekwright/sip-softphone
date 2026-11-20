package com.github.mikekwright.sip.softphone.nat;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty handler that sits first in a UDP pipeline shared with SIP or RTP and takes the STUN packets out of the
 * stream (RFC 7983 demultiplexing: STUN starts with 0b00 and carries the magic cookie, RTP/RTCP start with 0b10,
 * SIP with a letter). Responses complete the pending {@link StunClient} transactions; requests are answered with
 * the observed source address, so every socket of the softphone can also serve as a STUN responder (useful for
 * peers that probe us). Zero-length datagrams (RFC 6263 keep-alives) are dropped here as well.
 */
public final class StunHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "stun";
    private static final Logger log = LoggerFactory.getLogger(StunHandler.class);

    private final Map<String, CompletableFuture<StunMessage>> pending = new ConcurrentHashMap<>();

    /** Returns the handler of a channel, installing it at the head of the pipeline if needed. */
    public static StunHandler install(Channel channel) {
        StunHandler existing = channel.pipeline().get(StunHandler.class);
        if (existing != null) {
            return existing;
        }
        StunHandler handler = new StunHandler();
        try {
            channel.pipeline().addFirst(NAME, handler);
        } catch (IllegalArgumentException duplicate) {
            return channel.pipeline().get(StunHandler.class);
        }
        return handler;
    }

    /** Registers interest in the response to a transaction. */
    CompletableFuture<StunMessage> expect(StunMessage request) {
        CompletableFuture<StunMessage> f = new CompletableFuture<>();
        pending.put(request.transactionKey(), f);
        f.whenComplete((r, e) -> pending.remove(request.transactionKey()));
        return f;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DatagramPacket packet)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf content = packet.content();
        if (!content.isReadable()) {
            ReferenceCountUtil.release(msg); // RFC 6263 0-byte keep-alive
            return;
        }
        if (!StunMessage.isStun(content)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            StunMessage stun = StunMessage.decode(content);
            if (stun.isRequest() && stun.type() == StunMessage.BINDING_REQUEST) {
                answer(ctx, stun, packet.sender());
            } else if (!stun.isRequest()) {
                CompletableFuture<StunMessage> f = pending.get(stun.transactionKey());
                if (f != null) {
                    f.complete(stun);
                }
            }
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring malformed STUN packet from {}: {}", packet.sender(), e.getMessage());
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private static void answer(ChannelHandlerContext ctx, StunMessage request, InetSocketAddress sender) {
        ByteBuf out = ctx.alloc().buffer(64);
        StunMessage.bindingSuccess(request.transactionId(), sender, null).encode(out);
        ctx.writeAndFlush(new DatagramPacket(out, sender));
    }
}
