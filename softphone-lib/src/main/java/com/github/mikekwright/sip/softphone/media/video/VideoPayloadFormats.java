package com.github.mikekwright.sip.softphone.media.video;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Registry mapping RTP video encoding names (case-insensitive) to packetizer and depacketizer factories, so
 * that applications can add payload formats (e.g. VP9 per RFC 9628, AV1).
 *
 * <p>Thread-safety: all methods are synchronized. Every {@code create...} call returns a new instance.</p>
 */
public final class VideoPayloadFormats {

    private final Map<String, Entry> formats = new LinkedHashMap<>();

    /** Creates an empty registry. */
    public VideoPayloadFormats() {
    }

    /** A new, independent registry containing VP8 (RFC 7741) and H264 (RFC 6184, packetization-mode 1). */
    public static VideoPayloadFormats defaults() {
        VideoPayloadFormats formats = new VideoPayloadFormats();
        formats.register(Vp8Packetizer.ENCODING_NAME, Vp8Packetizer::new, Vp8Depacketizer::new);
        formats.register(H264Packetizer.ENCODING_NAME, H264Packetizer::new, H264Depacketizer::new);
        return formats;
    }

    /** Registers (or replaces) the factories for an encoding name. */
    public synchronized void register(String encodingName, Supplier<? extends VideoPacketizer> packetizer,
                                      Supplier<? extends VideoDepacketizer> depacketizer) {
        formats.put(key(encodingName), new Entry(Objects.requireNonNull(packetizer, "packetizer"),
                Objects.requireNonNull(depacketizer, "depacketizer")));
    }

    /** Whether the encoding name is registered. */
    public synchronized boolean supports(String encodingName) {
        return formats.containsKey(key(encodingName));
    }

    /** Registered encoding names (upper case) in registration order. */
    public synchronized List<String> encodingNames() {
        return List.copyOf(formats.keySet());
    }

    /** Creates a packetizer for the encoding name, if registered. */
    public Optional<VideoPacketizer> createPacketizer(String encodingName) {
        Entry entry = entry(encodingName);
        return entry == null ? Optional.empty() : Optional.of(entry.packetizer().get());
    }

    /** Creates a depacketizer for the encoding name, if registered. */
    public Optional<VideoDepacketizer> createDepacketizer(String encodingName) {
        Entry entry = entry(encodingName);
        return entry == null ? Optional.empty() : Optional.of(entry.depacketizer().get());
    }

    private synchronized Entry entry(String encodingName) {
        return formats.get(key(encodingName));
    }

    private static String key(String encodingName) {
        return Objects.requireNonNull(encodingName, "encodingName").toUpperCase(Locale.ROOT);
    }

    private record Entry(Supplier<? extends VideoPacketizer> packetizer,
                         Supplier<? extends VideoDepacketizer> depacketizer) {
    }
}
