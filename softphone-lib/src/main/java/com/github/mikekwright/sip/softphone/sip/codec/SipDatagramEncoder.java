package com.github.mikekwright.sip.softphone.sip.codec;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import io.netty.buffer.Unpooled;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Encodes an {@code AddressedEnvelope<SipMessage, InetSocketAddress>} into a single UDP datagram.
 */
public class SipDatagramEncoder extends MessageToMessageEncoder<AddressedEnvelope<SipMessage, InetSocketAddress>> {

    @Override
    public boolean acceptOutboundMessage(Object msg) {
        return msg instanceof AddressedEnvelope<?, ?> env && env.content() instanceof SipMessage;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, AddressedEnvelope<SipMessage, InetSocketAddress> msg,
                          List<Object> out) {
        out.add(new DatagramPacket(Unpooled.wrappedBuffer(msg.content().encode()), msg.recipient()));
    }
}
