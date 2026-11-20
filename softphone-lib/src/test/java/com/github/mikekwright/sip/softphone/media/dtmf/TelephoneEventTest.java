package com.github.mikekwright.sip.softphone.media.dtmf;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelephoneEventTest {

    @Test
    void encodesAndDecodesKnownBytes() {
        TelephoneEvent event = new TelephoneEvent(5, true, 10, 800);
        assertThat(HexFormat.of().formatHex(event.encode())).isEqualTo("058a0320");
        assertThat(TelephoneEvent.decode(HexFormat.of().parseHex("058a0320"))).isEqualTo(event);
        // R bit is ignored, trailing redundant data is ignored
        assertThat(TelephoneEvent.decode(HexFormat.of().parseHex("0b7fffff0000"))).isEqualTo(
                new TelephoneEvent(11, false, 63, 0xFFFF));
        assertThat(event.durationMillis(8000)).isEqualTo(100);
        assertThat(event.isDtmf()).isTrue();
        assertThat(event.digit()).isEqualTo('5');
        assertThat(new TelephoneEvent(16, false, 0, 0).isDtmf()).isFalse();
    }

    @Test
    void mapsDigitsToEventCodes() {
        String digits = "0123456789*#ABCD";
        for (int code = 0; code < 16; code++) {
            assertThat(TelephoneEvent.eventCode(digits.charAt(code))).isEqualTo(code);
            assertThat(TelephoneEvent.digit(code)).isEqualTo(digits.charAt(code));
        }
        assertThat(TelephoneEvent.eventCode('a')).isEqualTo(12);
        assertThat(TelephoneEvent.isDtmfDigit('d')).isTrue();
        assertThat(TelephoneEvent.isDtmfDigit('E')).isFalse();
        assertThatThrownBy(() -> TelephoneEvent.eventCode('x')).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelephoneEvent.digit(16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelephoneEvent.validateDigits("12x")).hasMessageContaining("'x'");
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> TelephoneEvent.decode(new byte[3])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelephoneEvent(1, false, 64, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelephoneEvent(1, false, 0, 65536)).isInstanceOf(IllegalArgumentException.class);
    }
}
