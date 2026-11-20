package com.github.mikekwright.sip.softphone.web;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Web softphone server: serves the browser UI and bridges each WebSocket connection to its own
 * {@link com.github.mikekwright.sip.softphone.Softphone} (see {@link BrowserSession}).
 *
 * <pre>
 *   web-softphone [--http-port 8080] [--http-bind 0.0.0.0] [--sip-host 192.168.1.10]
 *                 [--rtp-ports 30000-30998] [--web-root dir] [--tls-cert cert.pem --tls-key key.pem]
 * </pre>
 * Browsers only grant camera/microphone access to secure contexts: use {@code http://localhost} or HTTPS.
 */
public final class WebSoftphone implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebSoftphone.class);
    private static final AttributeKey<BrowserSession> SESSION = AttributeKey.valueOf("browserSession");

    private final WebSoftphoneOptions options;
    private final EventLoopGroup group;
    private Channel serverChannel;

    public WebSoftphone(WebSoftphoneOptions options) {
        this.options = options;
        this.group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    public static void main(String[] args) throws Exception {
        WebSoftphoneOptions options = parse(args);
        WebSoftphone server = new WebSoftphone(options);
        server.start();
        String scheme = options.tlsCert() != null ? "https" : "http";
        System.out.println("Web softphone running at " + scheme + "://localhost:" + server.port() + "/");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.serverChannel.closeFuture().sync();
    }

    /** Starts listening; returns the bound port. */
    public int start() throws Exception {
        SslContext ssl = options.tlsCert() == null ? null
                : SslContextBuilder.forServer(options.tlsCert().toFile(), options.tlsKey().toFile()).build();
        ServerBootstrap b = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (ssl != null) {
                            ch.pipeline().addLast(ssl.newHandler(ch.alloc()));
                        }
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1 << 20))
                                .addLast(new WebSocketServerProtocolHandler("/ws", null, true, 4 << 20))
                                .addLast(new WebSocketHandler())
                                .addLast(new StaticFileHandler(options.webRoot()));
                    }
                });
        serverChannel = b.bind(new InetSocketAddress(options.httpBind(), options.httpPort())).sync().channel();
        log.info("Web softphone listening on {}", serverChannel.localAddress());
        return port();
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        group.shutdownGracefully();
    }

    /** Creates a {@link BrowserSession} per WebSocket and routes frames to it. */
    private final class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                ctx.channel().attr(SESSION).set(new BrowserSession(ctx.channel(), options));
                log.info("Browser connected from {}", ctx.channel().remoteAddress());
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            BrowserSession session = ctx.channel().attr(SESSION).get();
            if (session == null) {
                return;
            }
            if (frame instanceof TextWebSocketFrame text) {
                session.onText(text.text());
            } else if (frame instanceof BinaryWebSocketFrame binary) {
                session.onBinary(binary.content());
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            BrowserSession session = ctx.channel().attr(SESSION).getAndSet(null);
            if (session != null) {
                session.close();
                log.info("Browser disconnected from {}", ctx.channel().remoteAddress());
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("WebSocket error: {}", cause.getMessage());
            ctx.close();
        }
    }

    static WebSoftphoneOptions parse(String[] args) {
        String httpBind = "0.0.0.0";
        int httpPort = 8080;
        String sipBind = "0.0.0.0";
        String sipHost = null;
        int rtpMin = 30000;
        int rtpMax = 39998;
        Path webRoot = null;
        Path cert = null;
        Path key = null;
        String stun = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--http-port" -> httpPort = Integer.parseInt(args[++i]);
                case "--http-bind" -> httpBind = args[++i];
                case "--sip-bind" -> sipBind = args[++i];
                case "--sip-host" -> sipHost = args[++i];
                case "--rtp-ports" -> {
                    String[] r = args[++i].split("-");
                    rtpMin = Integer.parseInt(r[0]);
                    rtpMax = Integer.parseInt(r[1]);
                }
                case "--web-root" -> webRoot = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--tls-cert" -> cert = Path.of(args[++i]);
                case "--tls-key" -> key = Path.of(args[++i]);
                case "--stun" -> stun = args[++i];
                case "-h", "--help" -> {
                    System.out.println("""
                            Usage: web-softphone [options]
                              --http-port N         HTTP/WebSocket port (default 8080, 0 = any)
                              --http-bind ADDR      HTTP bind address (default 0.0.0.0)
                              --sip-bind ADDR       bind address for SIP/RTP sockets (default 0.0.0.0)
                              --sip-host ADDR       address advertised in SIP and SDP (default: detected)
                              --rtp-ports MIN-MAX   RTP port range (default 30000-39998)
                              --web-root DIR        serve the UI from a directory (development)
                              --tls-cert PEM        enable HTTPS with this certificate chain
                              --tls-key PEM         private key for --tls-cert
                              --stun HOST[:PORT]    default STUN server for NAT traversal (per-account override)
                            Open the page, configure the account and call another tab or any SIP device.""");
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("unknown option " + a.toLowerCase(Locale.ROOT));
            }
        }
        return new WebSoftphoneOptions(httpBind, httpPort, sipBind, sipHost, rtpMin, rtpMax, webRoot, cert, key,
                stun);
    }
}
