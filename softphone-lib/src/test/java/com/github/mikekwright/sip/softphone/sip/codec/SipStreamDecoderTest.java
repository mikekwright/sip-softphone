package com.github.mikekwright.sip.softphone.sip.codec;

import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SipStreamDecoderTest {

    private static final String MSG = "MESSAGE sip:a@b SIP/2.0\r\nVia: SIP/2.0/TCP h;branch=z9hG4bK1\r\n"
            + "Content-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello";

    private static ByteBuf buf(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.UTF_8);
    }

    @Test
    void framesPipelinedMessagesAndPartialBodies() {
        EmbeddedChannel ch = new EmbeddedChannel(new SipStreamDecoder());
        String two = MSG + MSG;
        ch.writeInbound(buf(two.substring(0, 70)));
        assertThat(ch.<Object>readInbound()).isNull();
        ch.writeInbound(buf(two.substring(70, MSG.length() + 10)));
        SipMessage first = ch.readInbound();
        assertThat(first.bodyAsString()).isEqualTo("hello");
        assertThat(ch.<Object>readInbound()).isNull();
        ch.writeInbound(buf(two.substring(MSG.length() + 10)));
        SipMessage second = ch.readInbound();
        assertThat(second.bodyAsString()).isEqualTo("hello");
        ch.finishAndReleaseAll();
    }

    @Test
    void answersDoubleCrlfPingWithPong() {
        EmbeddedChannel ch = new EmbeddedChannel(new SipStreamDecoder());
        ch.writeInbound(buf("\r\n\r\n"));
        ByteBuf pong = ch.readOutbound();
        assertThat(pong.toString(StandardCharsets.US_ASCII)).isEqualTo("\r\n");
        pong.release();
        ch.writeInbound(buf("\r\n" + MSG));
        assertThat(ch.<Object>readInbound()).isNotNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void encoderAndDatagramCodecsRoundTrip() {
        EmbeddedChannel enc = new EmbeddedChannel(new SipStreamEncoder());
        SipMessage m = com.github.mikekwright.sip.softphone.sip.parser.SipMessageParser.parse(MSG);
        enc.writeOutbound(m);
        ByteBuf out = enc.readOutbound();
        EmbeddedChannel dec = new EmbeddedChannel(new SipStreamDecoder());
        dec.writeInbound(out);
        SipMessage back = dec.readInbound();
        assertThat(back.toString()).isEqualTo(m.toString());
        enc.finishAndReleaseAll();
        dec.finishAndReleaseAll();
    }
}
