package com.github.mikekwright.sip.softphone.nat;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.function.UnaryOperator;

/**
 * A minimal STUN Binding server (RFC 8489 §6.3) for tests and lab setups: answers every Binding request with the
 * source address it was received from. A mapping function can rewrite the reported address to emulate a NAT in
 * unit tests.
 *
 * <pre>java -cp 'lib/*' com.github.mikekwright.sip.softphone.nat.StunServer [bind-address] [port]</pre>
 */
public final class StunServer implements AutoCloseable {

    private final EventLoopGroup group;
    private final boolean ownsGroup;
    private final Channel channel;
    private volatile UnaryOperator<InetSocketAddress> mapping = UnaryOperator.identity();
    private volatile int dropFirst;

    private StunServer(EventLoopGroup group, boolean ownsGroup, InetSocketAddress bind) throws InterruptedException {
        this.group = group;
        this.ownsGroup = ownsGroup;
        this.channel = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        onPacket(ctx, packet);
                    }
                }).bind(bind).sync().channel();
    }

    public static StunServer start(InetSocketAddress bind) throws InterruptedException {
        return new StunServer(new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()), true, bind);
    }

    public static StunServer start(EventLoopGroup group, InetSocketAddress bind) throws InterruptedException {
        return new StunServer(group, false, bind);
    }

    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    /** Rewrites the address reported to clients (e.g. to pretend they are behind a NAT). */
    public void setMapping(UnaryOperator<InetSocketAddress> mapping) {
        this.mapping = mapping;
    }

    /** Ignores the next {@code n} requests, to exercise client retransmission. */
    public void dropNext(int n) {
        this.dropFirst = n;
    }

    private void onPacket(ChannelHandlerContext ctx, DatagramPacket packet) {
        if (!StunMessage.isStun(packet.content())) {
            return;
        }
        StunMessage request;
        try {
            request = StunMessage.decode(packet.content());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (request.type() != StunMessage.BINDING_REQUEST) {
            return;
        }
        if (dropFirst > 0) {
            dropFirst--;
            return;
        }
        ByteBuf out = ctx.alloc().buffer(64);
        StunMessage.bindingSuccess(request.transactionId(), mapping.apply(packet.sender()), "softphone-stun")
                .encode(out);
        ctx.writeAndFlush(new DatagramPacket(out, packet.sender()));
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        if (ownsGroup) {
            group.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 3478;
        StunServer server = start(new InetSocketAddress(host, port));
        System.out.println("STUN server listening on " + server.localAddress());
        server.channel.closeFuture().sync();
    }
}
