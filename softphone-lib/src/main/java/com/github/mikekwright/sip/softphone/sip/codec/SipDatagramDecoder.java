package com.github.mikekwright.sip.softphone.sip.codec;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.parser.SipMessageParser;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultAddressedEnvelope;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Decodes one SIP message per UDP datagram (RFC 3261 §18.3) into an
 * {@code AddressedEnvelope<SipMessage, InetSocketAddress>} carrying the sender address.
 *
 * <p>Datagrams consisting only of whitespace (CRLF keep-alives) are ignored. Datagrams that fail to parse are
 * dropped and logged: without parseable headers there is nowhere to send a {@code 400 Bad Request}.</p>
 */
public class SipDatagramDecoder extends MessageToMessageDecoder<DatagramPacket> {

    private static final Logger log = LoggerFactory.getLogger(SipDatagramDecoder.class);

    public SipDatagramDecoder() {
        super(DatagramPacket.class);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) {
        byte[] data = ByteBufUtil.getBytes(packet.content());
        if (isBlank(data)) {
            return;
        }
        try {
            SipMessage message = SipMessageParser.parse(data);
            out.add(new DefaultAddressedEnvelope<>(message, packet.recipient(), packet.sender()));
        } catch (RuntimeException e) {
            log.warn("Dropping unparseable datagram from {}: {}", packet.sender(), e.getMessage());
        }
    }

    private static boolean isBlank(byte[] data) {
        for (byte b : data) {
            if (b != '\r' && b != '\n' && b != ' ' && b != '\t' && b != 0) {
                return false;
            }
        }
        return true;
    }

    /** Helper for handlers receiving the envelope. */
    public static InetSocketAddress sender(DefaultAddressedEnvelope<SipMessage, InetSocketAddress> envelope) {
        return envelope.sender();
    }
}
