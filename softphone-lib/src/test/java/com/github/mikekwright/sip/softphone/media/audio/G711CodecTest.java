package com.github.mikekwright.sip.softphone.media.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class G711CodecTest {

    @Test
    void pcmuKnownVectors() {
        assertThat(PcmuCodec.encodeSample((short) 0)).isEqualTo(0xFF);
        assertThat(PcmuCodec.decodeSample(0xFF)).isEqualTo((short) 0);
        assertThat(PcmuCodec.decodeSample(0x7F)).isEqualTo((short) 0); // "negative zero"
        assertThat(PcmuCodec.decodeSample(0x80)).isEqualTo((short) 32124);
        assertThat(PcmuCodec.decodeSample(0x00)).isEqualTo((short) -32124);
        assertThat(PcmuCodec.encodeSample(Short.MAX_VALUE)).isEqualTo(0x80);
        assertThat(PcmuCodec.encodeSample(Short.MIN_VALUE)).isEqualTo(0x00);
        // first segment: 4-unit quantization of the 14-bit value around zero
        assertThat(PcmuCodec.decodeSample(0xFE)).isEqualTo((short) 8);
        assertThat(PcmuCodec.decodeSample(0x7E)).isEqualTo((short) -8);
        assertThat(PcmuCodec.encodeSample((short) 8)).isEqualTo(0xFE);
        assertThat(PcmuCodec.encodeSample((short) -8)).isEqualTo(0x7E);
    }

    @Test
    void pcmaKnownVectors() {
        assertThat(PcmaCodec.encodeSample((short) 0)).isEqualTo(0xD5);
        assertThat(PcmaCodec.decodeSample(0xD5)).isEqualTo((short) 8);
        assertThat(PcmaCodec.decodeSample(0x55)).isEqualTo((short) -8);
        assertThat(PcmaCodec.decodeSample(0xAA)).isEqualTo((short) 32256);
        assertThat(PcmaCodec.decodeSample(0x2A)).isEqualTo((short) -32256);
        assertThat(PcmaCodec.encodeSample(Short.MAX_VALUE)).isEqualTo(0xAA);
        assertThat(PcmaCodec.encodeSample(Short.MIN_VALUE)).isEqualTo(0x2A);
        assertThat(PcmaCodec.encodeSample((short) -1)).isEqualTo(0x55);
    }

    @Test
    void everyCodeWordSurvivesDecodeEncode() {
        for (int code = 0; code < 256; code++) {
            assertThat(PcmaCodec.encodeSample(PcmaCodec.decodeSample(code))).as("A-law %02x", code).isEqualTo(code);
            int expectedUlaw = code == 0x7F ? 0xFF : code; // both zeros encode to positive zero
            assertThat(PcmuCodec.encodeSample(PcmuCodec.decodeSample(code))).as("u-law %02x", code)
                    .isEqualTo(expectedUlaw);
        }
    }

    @Test
    void decodedValuesAreMonotonicInMagnitude() {
        for (int code = 0x80; code < 0xFF; code++) {
            // positive u-law values decrease as the code increases (complemented representation)
            assertThat(PcmuCodec.decodeSample(code)).isGreaterThan(PcmuCodec.decodeSample(code + 1));
        }
    }

    @Test
    void quantizationErrorIsBounded() {
        PcmuCodec ulaw = new PcmuCodec();
        PcmaCodec alaw = new PcmaCodec();
        short[] all = new short[65536];
        for (int i = 0; i < all.length; i++) {
            all[i] = (short) (i - 32768);
        }
        short[] u = ulaw.decode(ulaw.encode(all));
        short[] a = alaw.decode(alaw.encode(all));
        for (int i = 0; i < all.length; i++) {
            int x = all[i];
            if (Math.abs(x) > 32000) {
                continue; // clipping region
            }
            int bound = Math.abs(x) / 16 + 16;
            assertThat(Math.abs(u[i] - x)).as("u-law error at %d", x).isLessThanOrEqualTo(bound);
            assertThat(Math.abs(a[i] - x)).as("A-law error at %d", x).isLessThanOrEqualTo(bound);
        }
    }

    @Test
    void codecMetadata() {
        PcmuCodec pcmu = new PcmuCodec();
        assertThat(pcmu.encodingName()).isEqualTo("PCMU");
        assertThat(pcmu.defaultPayloadType()).isZero();
        assertThat(pcmu.clockRate()).isEqualTo(8000);
        assertThat(pcmu.sampleRate()).isEqualTo(8000);
        assertThat(pcmu.channels()).isEqualTo(1);
        assertThat(pcmu.samplesPerFrame(20)).isEqualTo(160);
        assertThat(pcmu.timestampIncrement(30)).isEqualTo(240);
        PcmaCodec pcma = new PcmaCodec();
        assertThat(pcma.encodingName()).isEqualTo("PCMA");
        assertThat(pcma.defaultPayloadType()).isEqualTo(8);
        assertThat(pcma.encode(new short[160])).hasSize(160).containsOnly((byte) 0xD5);
    }

    @Test
    void toneSurvivesCompanding() {
        short[] tone = new short[800];
        new ToneSource(1000, 0.5).read(tone, 8000);
        for (AudioCodec codec : new AudioCodec[]{new PcmuCodec(), new PcmaCodec()}) {
            short[] decoded = codec.decode(codec.encode(tone));
            assertThat(ToneDetector.relativePower(decoded, 8000, 1000)).isGreaterThan(0.95);
        }
    }

    @Test
    void l16IsLosslessBigEndian() {
        L16Codec codec = new L16Codec(16_000);
        short[] pcm = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 0x1234};
        byte[] payload = codec.encode(pcm);
        assertThat(payload).startsWith(0, 0, 0, 1, (byte) 0xFF, (byte) 0xFF);
        assertThat(payload[10]).isEqualTo((byte) 0x12);
        assertThat(codec.decode(payload)).containsExactly(pcm);
        assertThat(codec.defaultPayloadType()).isEqualTo(-1);
        assertThat(new L16Codec(44_100).defaultPayloadType()).isEqualTo(11);
        assertThat(codec.samplesPerFrame(20)).isEqualTo(320);
    }
}
