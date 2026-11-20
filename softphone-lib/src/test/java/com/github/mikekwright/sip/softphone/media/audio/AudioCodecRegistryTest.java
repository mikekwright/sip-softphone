package com.github.mikekwright.sip.softphone.media.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioCodecRegistryTest {

    @Test
    void defaultsContainG711AndL16() {
        AudioCodecRegistry registry = AudioCodecRegistry.defaults();
        assertThat(registry.supported()).startsWith(new AudioCodecRegistry.CodecId("PCMU", 8000),
                new AudioCodecRegistry.CodecId("PCMA", 8000));
        assertThat(registry.create("pcmu", 8000)).get().isInstanceOf(PcmuCodec.class);
        assertThat(registry.create("PCMA", 8000)).get().isInstanceOf(PcmaCodec.class);
        assertThat(registry.create("L16", 16_000)).get().extracting(AudioCodec::sampleRate).isEqualTo(16_000);
        assertThat(registry.create("PCMU", 16_000)).isEmpty();
        assertThat(registry.create("opus", 48_000)).isEmpty();
    }

    @Test
    void createsNewInstancesAndAcceptsCustomCodecs() {
        AudioCodecRegistry registry = AudioCodecRegistry.defaults();
        assertThat(registry.create("PCMU", 8000).get()).isNotSameAs(registry.create("PCMU", 8000).get());

        registry.register("x-test", 8000, PcmuCodec::new);
        assertThat(registry.supports("X-TEST", 8000)).isTrue();
        assertThat(registry.supported()).endsWith(new AudioCodecRegistry.CodecId("X-TEST", 8000));
        assertThat(registry.unregister("x-test", 8000)).isTrue();
        assertThat(registry.supports("x-test", 8000)).isFalse();

        // defaults() returns independent registries
        assertThat(AudioCodecRegistry.defaults().supports("x-test", 8000)).isFalse();
    }
}
