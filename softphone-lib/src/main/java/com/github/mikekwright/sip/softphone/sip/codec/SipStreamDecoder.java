package com.github.mikekwright.sip.softphone.sip.codec;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.parser.SipMessageParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Frames and decodes SIP messages from a byte stream (TCP or TLS), RFC 3261 §18.3: the header section ends with an
 * empty line and {@code Content-Length} (mandatory on streams; treated as 0 when missing) gives the body size.
 *
 * <p>Implements the RFC 5626 §3.5.1 CRLF keep-alive: a double CRLF "ping" received between messages is answered
 * with a single CRLF "pong"; single CRLFs between messages are discarded (RFC 3261 §7.5).</p>
 */
public class SipStreamDecoder extends ByteToMessageDecoder {

    private static final ByteBuf PONG = Unpooled.unreleasableBuffer(
            Unpooled.copiedBuffer("\r\n", StandardCharsets.US_ASCII));

    private final int maxMessageSize;

    public SipStreamDecoder() {
        this(SipMessageParser.MAX_HEAD_SIZE + 1024 * 1024);
    }

    public SipStreamDecoder(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            if (!skipKeepAlives(ctx, in)) {
                return;
            }
            int start = in.readerIndex();
            int headEnd = findHeadEnd(in, start);
            if (headEnd < 0) {
                if (in.readableBytes() > SipMessageParser.MAX_HEAD_SIZE) {
                    in.skipBytes(in.readableBytes());
                    throw new TooLongFrameException("SIP header section exceeds " + SipMessageParser.MAX_HEAD_SIZE);
                }
                return;
            }
            int sepLen = in.getByte(headEnd) == '\n' ? 2 : 4;
            String head = in.toString(start, headEnd - start, StandardCharsets.UTF_8);
            SipMessage message = SipMessageParser.parseHead(head);
            int contentLength = Math.max(0, SipMessageParser.contentLength(message));
            int total = headEnd - start + sepLen + contentLength;
            if (total > maxMessageSize) {
                in.skipBytes(in.readableBytes());
                throw new TooLongFrameException("SIP message of " + total + " bytes exceeds " + maxMessageSize);
            }
            if (in.readableBytes() < total) {
                return; // wait for the rest of the body
            }
            byte[] body = new byte[contentLength];
            in.getBytes(headEnd + sepLen, body);
            in.readerIndex(start + total);
            SipMessageParser.attachBody(message, body);
            out.add(message);
        }
    }

    /**
     * Consumes CRLFs preceding a message, answering a double-CRLF ping with a pong. Returns false if only part of a
     * keep-alive sequence is available.
     */
    private boolean skipKeepAlives(ChannelHandlerContext ctx, ByteBuf in) {
        int crlfCount = 0;
        while (in.readableBytes() >= 2 && in.getByte(in.readerIndex()) == '\r'
                && in.getByte(in.readerIndex() + 1) == '\n') {
            in.skipBytes(2);
            crlfCount++;
            if (crlfCount == 2) {
                ctx.writeAndFlush(PONG.duplicate());
                crlfCount = 0;
            }
        }
        while (in.isReadable() && in.getByte(in.readerIndex()) == '\n') {
            in.skipBytes(1);
        }
        return !(in.readableBytes() == 1 && in.getByte(in.readerIndex()) == '\r') && in.isReadable();
    }

    private static int findHeadEnd(ByteBuf in, int from) {
        int to = in.writerIndex();
        for (int i = from; i < to - 1; i++) {
            byte b = in.getByte(i);
            if (b == '\n' && in.getByte(i + 1) == '\n') {
                return i;
            }
            if (b == '\r' && i + 3 < to && in.getByte(i + 1) == '\n' && in.getByte(i + 2) == '\r'
                    && in.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
