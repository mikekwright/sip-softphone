package com.github.mikekwright.sip.softphone.media.audio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Maps RTP encoding names and clock rates (as negotiated through SDP {@code a=rtpmap}) to {@link AudioCodec}
 * factories, so applications can plug in further codecs (e.g. Opus or G.722 through a native library).
 *
 * <p>Lookups are case-insensitive on the encoding name. Every {@link #create} call returns a new codec instance
 * from the registered factory, since codecs may be stateful.</p>
 *
 * <p>Thread-safety: all methods are synchronized; registrations are visible to subsequent lookups from any
 * thread. {@link #supported()} lists codecs in registration order, which can serve as a preference order when
 * building SDP offers.</p>
 */
public final class AudioCodecRegistry {

    private final Map<CodecId, Supplier<AudioCodec>> factories = new LinkedHashMap<>();

    /** Creates an empty registry. */
    public AudioCodecRegistry() {
    }

    /**
     * A new registry with the built-in codecs: PCMU/8000, PCMA/8000 and L16 at 8000, 16000, 44100 and 48000 Hz.
     * Each call returns an independent registry that may be modified freely.
     */
    public static AudioCodecRegistry defaults() {
        AudioCodecRegistry registry = new AudioCodecRegistry();
        registry.register(PcmuCodec.ENCODING_NAME, 8000, PcmuCodec::new);
        registry.register(PcmaCodec.ENCODING_NAME, 8000, PcmaCodec::new);
        for (int rate : new int[]{8000, 16_000, 44_100, 48_000}) {
            registry.register(L16Codec.ENCODING_NAME, rate, () -> new L16Codec(rate));
        }
        return registry;
    }

    /** Registers (or replaces) the factory for {@code encodingName}/{@code clockRate}. */
    public synchronized void register(String encodingName, int clockRate, Supplier<AudioCodec> factory) {
        Objects.requireNonNull(factory, "factory");
        factories.put(new CodecId(encodingName, clockRate), factory);
    }

    /** Removes a registration; returns whether one existed. */
    public synchronized boolean unregister(String encodingName, int clockRate) {
        return factories.remove(new CodecId(encodingName, clockRate)) != null;
    }

    /** Whether a codec is registered for the encoding name and clock rate. */
    public synchronized boolean supports(String encodingName, int clockRate) {
        return factories.containsKey(new CodecId(encodingName, clockRate));
    }

    /** Creates a new codec instance, or returns empty if none is registered for the name and clock rate. */
    public Optional<AudioCodec> create(String encodingName, int clockRate) {
        Supplier<AudioCodec> factory;
        synchronized (this) {
            factory = factories.get(new CodecId(encodingName, clockRate));
        }
        return factory == null ? Optional.empty() : Optional.of(factory.get());
    }

    /** The registered codec identities in registration order. */
    public synchronized List<CodecId> supported() {
        return List.copyOf(new ArrayList<>(factories.keySet()));
    }

    /**
     * Identity of a registered codec.
     *
     * @param encodingName encoding name, normalised to upper case
     * @param clockRate    RTP clock rate in Hz
     */
    public record CodecId(String encodingName, int clockRate) {
        public CodecId {
            encodingName = Objects.requireNonNull(encodingName, "encodingName").toUpperCase(Locale.ROOT);
            if (clockRate <= 0) {
                throw new IllegalArgumentException("clock rate must be positive");
            }
        }
    }
}
