package com.github.mikekwright.sip.softphone.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the browser UI: files from {@code /web} on the classpath, or from a directory during development.
 */
final class StaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "mjs", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json");

    private final Path directory;

    /** @param directory serve from this directory instead of the classpath (null = classpath) */
    StaticFileHandler(Path directory) {
        this.directory = directory;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws IOException {
        if (!request.method().equals(HttpMethod.GET) && !request.method().equals(HttpMethod.HEAD)) {
            send(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain", "method not allowed".getBytes());
            return;
        }
        String path = new QueryStringDecoder(request.uri()).path();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", "forbidden".getBytes());
            return;
        }
        byte[] content = load(path);
        if (content == null) {
            send(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain",
                    ("not found: " + path).getBytes(StandardCharsets.UTF_8));
            return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        send(ctx, request, HttpResponseStatus.OK, TYPES.getOrDefault(ext, "application/octet-stream"), content);
    }

    private byte[] load(String path) throws IOException {
        if (directory != null) {
            Path file = directory.resolve(path.substring(1)).normalize();
            return file.startsWith(directory) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (InputStream in = StaticFileHandler.class.getResourceAsStream("/web" + path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void send(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                             String type, byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                request.method().equals(HttpMethod.HEAD) ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
