package com.github.mikekwright.sip.softphone.sip.codec;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Writes SIP messages to a byte stream (TCP or TLS). {@code Content-Length} is always present (RFC 3261 §18.3).
 */
@ChannelHandler.Sharable
public class SipStreamEncoder extends MessageToByteEncoder<SipMessage> {

    public SipStreamEncoder() {
        super(SipMessage.class);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, SipMessage msg, ByteBuf out) {
        out.writeBytes(msg.encode());
    }
}
