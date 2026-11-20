package com.github.mikekwright.sip.softphone.sdp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A media description ({@code m=} section), RFC 8866 §5.14:
 * {@code m=<media> <port>[/<number of ports>] <proto> <fmt> ...} followed by optional {@code i=}, {@code c=},
 * {@code b=} and {@code a=} lines.
 *
 * <p>Like {@link SessionDescription} this is a mutable model whose setters return {@code this} and whose list
 * accessors return live lists. On top of the raw model it offers typed views of the attributes that matter for
 * RTP sessions: {@link #rtpMaps()} ({@code a=rtpmap}/{@code a=fmtp}/{@code a=rtcp-fb}), {@link #direction()},
 * {@link #rtcpPort()} ({@code a=rtcp}), {@link #rtcpMux()} and {@link #ptime()}.</p>
 */
public final class MediaDescription {

    /** The audio media type. */
    public static final String AUDIO = "audio";
    /** The video media type. */
    public static final String VIDEO = "video";
    /** The plain RTP profile, RFC 3551. */
    public static final String RTP_AVP = "RTP/AVP";
    /** The RTP profile with RTCP-based feedback, RFC 4585. */
    public static final String RTP_AVPF = "RTP/AVPF";

    private String media;
    private int port;
    private Integer numberOfPorts;
    private String protocol;
    private List<String> formats = new ArrayList<>();
    private String information;
    private Connection connection;
    private List<Bandwidth> bandwidths = new ArrayList<>();
    private List<String> otherLines = new ArrayList<>();
    private List<Attribute> attributes = new ArrayList<>();

    /**
     * Creates a media description without formats.
     *
     * @param media    media type, e.g. {@code audio}
     * @param port     transport port; 0 disables (rejects) the stream
     * @param protocol transport protocol, e.g. {@code RTP/AVP}
     */
    public MediaDescription(String media, int port, String protocol) {
        setMedia(media);
        setPort(port);
        setProtocol(protocol);
    }

    /** Media type ({@code audio}, {@code video}, {@code application}, ...). */
    public String media() {
        return media;
    }

    public MediaDescription setMedia(String media) {
        this.media = Objects.requireNonNull(media, "media");
        return this;
    }

    /** Transport port; 0 means the stream is disabled or rejected (RFC 3264 §5.1, §6). */
    public int port() {
        return port;
    }

    public MediaDescription setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
        return this;
    }

    /** The optional {@code /<number of ports>} suffix of the port, or {@code null}. */
    public Integer numberOfPorts() {
        return numberOfPorts;
    }

    public MediaDescription setNumberOfPorts(Integer numberOfPorts) {
        this.numberOfPorts = numberOfPorts;
        return this;
    }

    /** Transport protocol, e.g. {@code RTP/AVP}, {@code RTP/AVPF}, {@code RTP/SAVP}. */
    public String protocol() {
        return protocol;
    }

    public MediaDescription setProtocol(String protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        return this;
    }

    /** Media formats in preference order; for RTP these are payload type numbers. Live list. */
    public List<String> formats() {
        return formats;
    }

    public MediaDescription setFormats(List<String> formats) {
        this.formats = new ArrayList<>(formats);
        return this;
    }

    /** Media title ({@code i=}, RFC 8866 §5.4) or {@code null}. */
    public String information() {
        return information;
    }

    public MediaDescription setInformation(String information) {
        this.information = information;
        return this;
    }

    /** Media-level connection data ({@code c=}) or {@code null} to inherit the session-level one. */
    public Connection connection() {
        return connection;
    }

    public MediaDescription setConnection(Connection connection) {
        this.connection = connection;
        return this;
    }

    /** Media-level bandwidths ({@code b=}); live list. */
    public List<Bandwidth> bandwidths() {
        return bandwidths;
    }

    public MediaDescription setBandwidths(List<Bandwidth> bandwidths) {
        this.bandwidths = new ArrayList<>(bandwidths);
        return this;
    }

    /** Unmodelled media-level lines with their type letter (e.g. the obsolete {@code k=}); live list. */
    public List<String> otherLines() {
        return otherLines;
    }

    public MediaDescription setOtherLines(List<String> otherLines) {
        this.otherLines = new ArrayList<>(otherLines);
        return this;
    }

    /** Media-level attributes in SDP order; live list. */
    public List<Attribute> attributes() {
        return attributes;
    }

    public MediaDescription setAttributes(List<Attribute> attributes) {
        this.attributes = new ArrayList<>(attributes);
        return this;
    }

    /**
     * The value of the first attribute with this name (case-insensitive); property attributes yield an empty
     * string.
     */
    public Optional<String> attribute(String name) {
        return Attributes.first(attributes, name);
    }

    /** Values of all attributes with this name, in order (empty string for property attributes). */
    public List<String> attributes(String name) {
        return Attributes.values(attributes, name);
    }

    /** Whether an attribute with this name is present. */
    public boolean hasAttribute(String name) {
        return Attributes.has(attributes, name);
    }

    /** Appends an attribute. */
    public MediaDescription addAttribute(Attribute attribute) {
        attributes.add(Objects.requireNonNull(attribute, "attribute"));
        return this;
    }

    /** Appends an attribute; a {@code null} value creates a property attribute. */
    public MediaDescription addAttribute(String name, String value) {
        return addAttribute(new Attribute(name, value));
    }

    /** Removes all attributes with this name. */
    public MediaDescription removeAttributes(String name) {
        attributes.removeIf(a -> a.is(name));
        return this;
    }

    /** Whether the stream is disabled or rejected, i.e. its port is 0 (RFC 3264 §5.1, §6). */
    public boolean isRejected() {
        return port == 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // RTP payload formats
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Appends payload formats: each payload type is added to {@link #formats()} together with its
     * {@code a=rtpmap}, {@code a=fmtp} (when {@link RtpMap#fmtp()} is set) and {@code a=rtcp-fb} attributes.
     * An {@code a=rtpmap} line is written even for static payload types, which RFC 8866 §6.6 permits and many
     * implementations expect.
     */
    public MediaDescription addFormat(RtpMap... maps) {
        for (RtpMap map : maps) {
            String pt = Integer.toString(map.payloadType());
            formats.add(pt);
            addAttribute("rtpmap", map.toRtpMapValue());
            if (map.fmtp() != null && !map.fmtp().isBlank()) {
                addAttribute("fmtp", pt + ' ' + map.fmtp());
            }
            for (String feedback : map.rtcpFeedback()) {
                addAttribute("rtcp-fb", pt + ' ' + feedback);
            }
        }
        return this;
    }

    /**
     * The RTP payload formats of this media description, in {@link #formats()} order. Each entry combines the
     * {@code a=rtpmap} of the payload type, or the RFC 3551 static assignment if there is none
     * ({@link StaticPayloadTypes}), with its {@code a=fmtp} value and {@code a=rtcp-fb} values (including
     * {@code a=rtcp-fb:*} wildcard entries, RFC 4585 §4.2). Formats that are not numeric, have no mapping, or have
     * a malformed {@code a=rtpmap} are skipped.
     */
    public List<RtpMap> rtpMaps() {
        List<RtpMap> result = new ArrayList<>();
        for (String format : formats) {
            parsePayloadType(format).flatMap(this::rtpMap).ifPresent(result::add);
        }
        return result;
    }

    /** The payload format for a payload type, resolved as described in {@link #rtpMaps()}. */
    public Optional<RtpMap> rtpMap(int payloadType) {
        RtpMap map = null;
        String key = Integer.toString(payloadType);
        for (String value : Attributes.values(attributes, "rtpmap")) {
            String trimmed = value.trim();
            if (trimmed.startsWith(key + " ") || trimmed.startsWith(key + "\t")) {
                try {
                    map = RtpMap.parse(trimmed);
                    break;
                } catch (IllegalArgumentException ignored) {
                    // malformed rtpmap: fall back to the static table below
                }
            }
        }
        if (map == null) {
            map = StaticPayloadTypes.lookup(payloadType).orElse(null);
        }
        if (map == null) {
            return Optional.empty();
        }
        return Optional.of(new RtpMap(payloadType, map.encodingName(), map.clockRate(), map.channels(),
                fmtp(payloadType).orElse(null), rtcpFeedback(payloadType)));
    }

    /** The {@code a=fmtp} parameters of a payload type (the value after the payload type), RFC 8866 §6.15. */
    public Optional<String> fmtp(int payloadType) {
        return Attributes.keyed(attributes, "fmtp", Integer.toString(payloadType)).stream().findFirst();
    }

    /**
     * The {@code a=rtcp-fb} values that apply to a payload type (RFC 4585 §4.2): first those specific to the
     * payload type, then the {@code *} wildcard entries, without duplicates. Values exclude the payload type,
     * e.g. {@code nack}, {@code nack pli}, {@code ccm fir}.
     */
    public List<String> rtcpFeedback(int payloadType) {
        List<String> result = new ArrayList<>(Attributes.keyed(attributes, "rtcp-fb",
                Integer.toString(payloadType)));
        for (String wildcard : Attributes.keyed(attributes, "rtcp-fb", "*")) {
            if (!result.contains(wildcard)) {
                result.add(wildcard);
            }
        }
        return result;
    }

    /** The packetization time in milliseconds ({@code a=ptime}, RFC 8866 §6.4), if present and numeric. */
    public OptionalInt ptime() {
        return attribute("ptime").map(String::trim).map(v -> {
            try {
                return OptionalInt.of((int) Math.round(Double.parseDouble(v)));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }).orElse(OptionalInt.empty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Direction and RTCP
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The media-level direction attribute (RFC 8866 §6.7), or {@code null} if there is none so that callers can
     * fall back to the session level; see {@link #effectiveDirection(SessionDescription)}.
     */
    public MediaDirection direction() {
        return Attributes.direction(attributes);
    }

    /**
     * The direction in effect for this stream: the media-level attribute, else the session-level attribute, else
     * {@link MediaDirection#SENDRECV} (RFC 8866 §6.7).
     *
     * @param session the enclosing description, or {@code null} to ignore the session level
     */
    public MediaDirection effectiveDirection(SessionDescription session) {
        MediaDirection direction = direction();
        if (direction != null) {
            return direction;
        }
        return session == null ? MediaDirection.SENDRECV : session.direction();
    }

    /**
     * Replaces any media-level direction attribute with {@code direction} (keeping its position), or removes it
     * when {@code direction} is {@code null}.
     */
    public MediaDescription setDirection(MediaDirection direction) {
        Attributes.setDirection(attributes, direction);
        return this;
    }

    /** Whether {@code a=rtcp-mux} is present (RFC 5761 §5.1.1). */
    public boolean rtcpMux() {
        return hasAttribute("rtcp-mux");
    }

    /** Adds or removes {@code a=rtcp-mux}. */
    public MediaDescription setRtcpMux(boolean rtcpMux) {
        removeAttributes("rtcp-mux");
        if (rtcpMux) {
            addAttribute(Attribute.property("rtcp-mux"));
        }
        return this;
    }

    /** The explicit RTCP port of {@code a=rtcp:<port> [<nettype> <addrtype> <address>]} (RFC 3605 §2.1). */
    public Optional<Integer> rtcpPort() {
        return rtcpTokens().flatMap(tokens -> {
            try {
                return Optional.of(Integer.parseInt(tokens[0]));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /** The explicit RTCP address of {@code a=rtcp}, present only when the attribute carries one (RFC 3605). */
    public Optional<Connection> rtcpConnection() {
        return rtcpTokens().filter(tokens -> tokens.length >= 4)
                .map(tokens -> new Connection(tokens[1], tokens[2], tokens[3]));
    }

    private Optional<String[]> rtcpTokens() {
        return attribute("rtcp").map(String::trim).filter(v -> !v.isEmpty()).map(v -> v.split("\\s+"));
    }

    private static Optional<Integer> parsePayloadType(String format) {
        try {
            return Optional.of(Integer.parseInt(format));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A deep copy; the copy shares no mutable state with this description. */
    public MediaDescription copy() {
        return new MediaDescription(media, port, protocol)
                .setNumberOfPorts(numberOfPorts)
                .setFormats(formats)
                .setInformation(information)
                .setConnection(connection)
                .setBandwidths(bandwidths)
                .setOtherLines(otherLines)
                .setAttributes(attributes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof MediaDescription that
                && port == that.port
                && media.equals(that.media)
                && Objects.equals(numberOfPorts, that.numberOfPorts)
                && protocol.equals(that.protocol)
                && formats.equals(that.formats)
                && Objects.equals(information, that.information)
                && Objects.equals(connection, that.connection)
                && bandwidths.equals(that.bandwidths)
                && otherLines.equals(that.otherLines)
                && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(media, port, numberOfPorts, protocol, formats, information, connection, bandwidths,
                otherLines, attributes);
    }

    /** The {@code m=} section as SDP text (CRLF terminated lines). */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        SdpWriter.writeMedia(sb, this);
        return sb.toString();
    }
}
