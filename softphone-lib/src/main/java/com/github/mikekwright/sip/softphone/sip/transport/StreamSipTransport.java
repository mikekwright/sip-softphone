package com.github.mikekwright.sip.softphone.sip.transport;

import com.github.mikekwright.sip.softphone.sip.codec.SipStreamDecoder;
import com.github.mikekwright.sip.softphone.sip.codec.SipStreamEncoder;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SIP over TCP or TLS (RFC 3261 §18, §26.2.1). Listens for inbound connections and opens outbound connections on
 * demand. Connections are reused in both directions and indexed by remote address, so responses travel back over the
 * connection the request arrived on (RFC 3261 §18.2.2) and later requests to the same peer reuse the connection
 * (in the spirit of RFC 5923). Idle connections are closed after a configurable time.
 */
public final class StreamSipTransport implements SipTransport {

    private static final Logger log = LoggerFactory.getLogger(StreamSipTransport.class);

    private final TransportType type;
    private final Channel serverChannel;
    private final Bootstrap clientBootstrap;
    private final Map<InetSocketAddress, Channel> connections = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, CompletableFuture<Channel>> pending = new ConcurrentHashMap<>();

    private StreamSipTransport(TransportType type, Channel serverChannel, Bootstrap clientBootstrap) {
        this.type = type;
        this.serverChannel = serverChannel;
        this.clientBootstrap = clientBootstrap;
    }

    /**
     * Starts a TCP (when {@code serverSsl}/{@code clientSsl} are null) or TLS listener.
     *
     * @param idleSeconds close connections without traffic for this long (0 disables)
     */
    public static CompletableFuture<SipTransport> bind(TransportType type, EventLoopGroup group,
                                                       InetSocketAddress bindAddress, Listener listener,
                                                       PipelineCustomizer customizer, SslContext serverSsl,
                                                       SslContext clientSsl, int idleSeconds) {
        if (!type.isReliable()) {
            throw new IllegalArgumentException("stream transport requires TCP or TLS");
        }
        if (type == TransportType.TLS && (serverSsl == null || clientSsl == null)) {
            throw new IllegalArgumentException("TLS transport requires server and client SslContext");
        }
        StreamSipTransport[] self = new StreamSipTransport[1];
        ChannelInitializer<SocketChannel> serverInit = initializer(type, listener, customizer, serverSsl, false,
                idleSeconds, () -> self[0]);
        ChannelInitializer<SocketChannel> clientInit = initializer(type, listener, customizer, clientSsl, true,
                idleSeconds, () -> self[0]);
        ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(serverInit);
        Bootstrap cb = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(clientInit);
        CompletableFuture<SipTransport> result = new CompletableFuture<>();
        sb.bind(bindAddress).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                self[0] = new StreamSipTransport(type, f.channel(), cb);
                result.complete(self[0]);
            } else {
                result.completeExceptionally(f.cause());
            }
        });
        return result;
    }

    private static ChannelInitializer<SocketChannel> initializer(TransportType type, Listener listener,
                                                                PipelineCustomizer customizer, SslContext ssl,
                                                                boolean client, int idleSeconds,
                                                                java.util.function.Supplier<StreamSipTransport> owner) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (ssl != null) {
                    ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc()));
                }
                if (idleSeconds > 0) {
                    ch.pipeline().addLast("idle", new IdleStateHandler(0, 0, idleSeconds, TimeUnit.SECONDS));
                }
                ch.pipeline()
                        .addLast("sip-decoder", new SipStreamDecoder())
                        .addLast("sip-encoder", new SipStreamEncoder())
                        .addLast("sip-handler", new InboundHandler(type, listener));
                customizer.customize(type, ch.pipeline());
                if (!client) {
                    StreamSipTransport transport = owner.get();
                    if (transport != null) {
                        transport.register(ch);
                    }
                }
            }
        };
    }

    private void register(Channel ch) {
        InetSocketAddress remote = (InetSocketAddress) ch.remoteAddress();
        if (remote == null) {
            return;
        }
        connections.put(remote, ch);
        ch.closeFuture().addListener(f -> connections.remove(remote, ch));
    }

    @Override
    public TransportType type() {
        return type;
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) serverChannel.localAddress();
    }

    /** Number of open connections (inbound and outbound). */
    public int connectionCount() {
        return connections.size();
    }

    @Override
    public CompletableFuture<Void> send(SipMessage message, InetSocketAddress remote) {
        return connection(remote).thenCompose(ch -> NettyFutures.toCompletable(ch.writeAndFlush(message)));
    }

    private CompletableFuture<Channel> connection(InetSocketAddress remote) {
        Channel existing = connections.get(remote);
        if (existing != null && existing.isActive()) {
            return CompletableFuture.completedFuture(existing);
        }
        return pending.computeIfAbsent(remote, addr -> {
            CompletableFuture<Channel> cf = new CompletableFuture<>();
            clientBootstrap.connect(addr).addListener((ChannelFuture f) -> {
                pending.remove(addr);
                if (f.isSuccess()) {
                    Channel ch = f.channel();
                    connections.put(addr, ch);
                    ch.closeFuture().addListener(x -> connections.remove(addr, ch));
                    cf.complete(ch);
                } else {
                    cf.completeExceptionally(f.cause());
                }
            });
            return cf;
        });
    }

    @Override
    public CompletableFuture<Void> close() {
        connections.values().forEach(Channel::close);
        return NettyFutures.toCompletable(serverChannel.close());
    }

    private static final class InboundHandler extends SimpleChannelInboundHandler<SipMessage> {
        private final TransportType type;
        private final Listener listener;

        InboundHandler(TransportType type, Listener listener) {
            this.type = type;
            this.listener = listener;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SipMessage msg) {
            listener.onMessage(new InboundMessage(msg, type, (InetSocketAddress) ctx.channel().remoteAddress(),
                    (InetSocketAddress) ctx.channel().localAddress(), ctx.channel()));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                log.debug("Closing idle {} connection to {}", type, ctx.channel().remoteAddress());
                ctx.close();
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // A framing or parse error leaves the stream unsynchronized: the only safe recovery is to close it.
            log.warn("Closing {} connection to {} after error: {}", type, ctx.channel().remoteAddress(),
                    cause.getMessage());
            ctx.close();
        }
    }
}
