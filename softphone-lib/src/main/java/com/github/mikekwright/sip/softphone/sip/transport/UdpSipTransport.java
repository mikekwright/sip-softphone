package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.codec.SipDatagramDecoder;
import com.github.mikekwright.sip.softphone.sip.codec.SipDatagramEncoder;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultAddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * SIP over UDP (RFC 3261 §18): a single datagram socket used both to receive and to send, so that responses and
 * requests leave from the advertised port (which keeps {@code rport}/NAT bindings symmetric, RFC 3581).
 */
public final class UdpSipTransport implements SipTransport {

    private final Channel channel;

    private UdpSipTransport(Channel channel) {
        this.channel = channel;
    }

    /** Binds a UDP socket and installs the SIP datagram codec. */
    public static CompletableFuture<SipTransport> bind(EventLoopGroup group, InetSocketAddress bindAddress,
                                                       Listener listener, PipelineCustomizer customizer) {
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline()
                                .addLast("sip-decoder", new SipDatagramDecoder())
                                .addLast("sip-encoder", new SipDatagramEncoder())
                                .addLast("sip-handler", new InboundHandler(listener));
                        customizer.customize(TransportType.UDP, ch.pipeline());
                    }
                });
        CompletableFuture<SipTransport> result = new CompletableFuture<>();
        b.bind(bindAddress).addListener(f -> {
            if (f.isSuccess()) {
                result.complete(new UdpSipTransport(((io.netty.channel.ChannelFuture) f).channel()));
            } else {
                result.completeExceptionally(f.cause());
            }
        });
        return result;
    }

    @Override
    public TransportType type() {
        return TransportType.UDP;
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public CompletableFuture<Void> send(SipMessage message, InetSocketAddress remote) {
        AddressedEnvelope<SipMessage, InetSocketAddress> env = new DefaultAddressedEnvelope<>(message, remote);
        return NettyFutures.toCompletable(channel.writeAndFlush(env));
    }

    @Override
    public CompletableFuture<Void> close() {
        return NettyFutures.toCompletable(channel.close());
    }

    private static final class InboundHandler
            extends SimpleChannelInboundHandler<AddressedEnvelope<SipMessage, InetSocketAddress>> {
        private final Listener listener;

        InboundHandler(Listener listener) {
            this.listener = listener;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, AddressedEnvelope<SipMessage, InetSocketAddress> env) {
            listener.onMessage(new InboundMessage(env.content(), TransportType.UDP, env.sender(),
                    (InetSocketAddress) ctx.channel().localAddress(), ctx.channel()));
        }
    }
}
