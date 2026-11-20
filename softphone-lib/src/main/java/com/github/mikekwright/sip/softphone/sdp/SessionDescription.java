package com.github.mikekwright.sip.softphone.sdp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A complete SDP session description, RFC 8866 §5.
 *
 * <p>This is a mutable model: setters return {@code this} for chaining and list accessors return the live,
 * modifiable lists. Use {@link #copy()} for an independent deep copy, e.g. before deriving a re-offer from a
 * previous offer. {@link SdpParser} creates instances from text and {@link SdpWriter} (or {@link #toString()})
 * serializes them.</p>
 *
 * <p>Fields map to the session-level lines as follows: {@code v=} {@link #version()}, {@code o=}
 * {@link #origin()}, {@code s=} {@link #sessionName()}, {@code i=} {@link #sessionInformation()}, {@code u=}
 * {@link #uri()}, {@code e=} {@link #emails()}, {@code p=} {@link #phones()}, {@code c=} {@link #connection()},
 * {@code b=} {@link #bandwidths()}, {@code t=} {@link #timings()}, {@code a=} {@link #attributes()} and
 * {@code m=} sections {@link #media()}. Lines this model does not interpret ({@code r=}, {@code z=}, {@code k=}
 * and unknown types) are preserved verbatim, in order, in {@link #otherLines()} and are written after the
 * {@code t=} lines.</p>
 */
public final class SessionDescription {

    private int version;
    private Origin origin = new Origin("-", "0", 0, Connection.NET_TYPE_IN, Connection.IP4, "0.0.0.0");
    private String sessionName = "-";
    private String sessionInformation;
    private String uri;
    private List<String> emails = new ArrayList<>();
    private List<String> phones = new ArrayList<>();
    private Connection connection;
    private List<Bandwidth> bandwidths = new ArrayList<>();
    private List<Timing> timings = new ArrayList<>(List.of(Timing.PERMANENT));
    private List<String> otherLines = new ArrayList<>();
    private List<Attribute> attributes = new ArrayList<>();
    private List<MediaDescription> media = new ArrayList<>();

    /** Creates a description with {@code v=0}, {@code s=-}, {@code t=0 0} and a placeholder origin. */
    public SessionDescription() {
    }

    /** Protocol version ({@code v=}, RFC 8866 §5.1); always 0. */
    public int version() {
        return version;
    }

    public SessionDescription setVersion(int version) {
        this.version = version;
        return this;
    }

    /** Origin ({@code o=}, RFC 8866 §5.2). */
    public Origin origin() {
        return origin;
    }

    public SessionDescription setOrigin(Origin origin) {
        this.origin = Objects.requireNonNull(origin, "origin");
        return this;
    }

    /** Session name ({@code s=}, RFC 8866 §5.3); {@code "-"} when there is no meaningful name. */
    public String sessionName() {
        return sessionName;
    }

    public SessionDescription setSessionName(String sessionName) {
        this.sessionName = sessionName == null || sessionName.isEmpty() ? "-" : sessionName;
        return this;
    }

    /** Session information ({@code i=}, RFC 8866 §5.4) or {@code null}. */
    public String sessionInformation() {
        return sessionInformation;
    }

    public SessionDescription setSessionInformation(String sessionInformation) {
        this.sessionInformation = sessionInformation;
        return this;
    }

    /** URI of description ({@code u=}, RFC 8866 §5.5) or {@code null}. */
    public String uri() {
        return uri;
    }

    public SessionDescription setUri(String uri) {
        this.uri = uri;
        return this;
    }

    /** Email addresses ({@code e=}, RFC 8866 §5.6); live list. */
    public List<String> emails() {
        return emails;
    }

    public SessionDescription setEmails(List<String> emails) {
        this.emails = new ArrayList<>(emails);
        return this;
    }

    /** Phone numbers ({@code p=}, RFC 8866 §5.6); live list. */
    public List<String> phones() {
        return phones;
    }

    public SessionDescription setPhones(List<String> phones) {
        this.phones = new ArrayList<>(phones);
        return this;
    }

    /** Session-level connection data ({@code c=}, RFC 8866 §5.7) or {@code null}. */
    public Connection connection() {
        return connection;
    }

    public SessionDescription setConnection(Connection connection) {
        this.connection = connection;
        return this;
    }

    /** Session-level bandwidths ({@code b=}, RFC 8866 §5.8); live list. */
    public List<Bandwidth> bandwidths() {
        return bandwidths;
    }

    public SessionDescription setBandwidths(List<Bandwidth> bandwidths) {
        this.bandwidths = new ArrayList<>(bandwidths);
        return this;
    }

    /** Timings ({@code t=}, RFC 8866 §5.9); live list, by default a single {@code t=0 0}. */
    public List<Timing> timings() {
        return timings;
    }

    public SessionDescription setTimings(List<Timing> timings) {
        this.timings = new ArrayList<>(timings);
        return this;
    }

    /**
     * Unmodelled session-level lines, complete with their type letter (e.g. {@code r=7d 1h 0 25h},
     * {@code z=...}, {@code k=...}, RFC 8866 §5.10-5.12); live list.
     */
    public List<String> otherLines() {
        return otherLines;
    }

    public SessionDescription setOtherLines(List<String> otherLines) {
        this.otherLines = new ArrayList<>(otherLines);
        return this;
    }

    /** Session-level attributes ({@code a=}, RFC 8866 §5.13); live list in SDP order. */
    public List<Attribute> attributes() {
        return attributes;
    }

    public SessionDescription setAttributes(List<Attribute> attributes) {
        this.attributes = new ArrayList<>(attributes);
        return this;
    }

    /**
     * The value of the first session-level attribute with this name (case-insensitive). Property attributes
     * yield an empty string, so {@code attribute("ice-lite").isPresent()} tests for presence.
     */
    public Optional<String> attribute(String name) {
        return Attributes.first(attributes, name);
    }

    /** Values of all session-level attributes with this name, in order (empty string for property attributes). */
    public List<String> attributes(String name) {
        return Attributes.values(attributes, name);
    }

    /** Whether a session-level attribute with this name is present. */
    public boolean hasAttribute(String name) {
        return Attributes.has(attributes, name);
    }

    /** Appends a session-level attribute. */
    public SessionDescription addAttribute(Attribute attribute) {
        attributes.add(Objects.requireNonNull(attribute, "attribute"));
        return this;
    }

    /** Appends a session-level attribute; a {@code null} value creates a property attribute. */
    public SessionDescription addAttribute(String name, String value) {
        return addAttribute(new Attribute(name, value));
    }

    /** Removes all session-level attributes with this name. */
    public SessionDescription removeAttributes(String name) {
        attributes.removeIf(a -> a.is(name));
        return this;
    }

    /**
     * The session-level direction attribute, or {@link MediaDirection#SENDRECV} if none is present
     * (RFC 8866 §6.7). Media-level attributes override it, see
     * {@link MediaDescription#effectiveDirection(SessionDescription)}.
     */
    public MediaDirection direction() {
        MediaDirection direction = Attributes.direction(attributes);
        return direction == null ? MediaDirection.SENDRECV : direction;
    }

    /** Replaces any session-level direction attribute; {@code null} removes it. */
    public SessionDescription setDirection(MediaDirection direction) {
        Attributes.setDirection(attributes, direction);
        return this;
    }

    /** Media descriptions ({@code m=} sections, RFC 8866 §5.14) in order; live list. */
    public List<MediaDescription> media() {
        return media;
    }

    public SessionDescription setMedia(List<MediaDescription> media) {
        this.media = new ArrayList<>(media);
        return this;
    }

    /** Appends a media description. */
    public SessionDescription addMedia(MediaDescription mediaDescription) {
        media.add(Objects.requireNonNull(mediaDescription, "mediaDescription"));
        return this;
    }

    /**
     * The connection that applies to a media description: its own {@code c=} line, else the session-level one
     * (RFC 8866 §5.7). May be {@code null} for a description that lacks both.
     */
    public Connection connectionFor(MediaDescription mediaDescription) {
        return mediaDescription.connection() != null ? mediaDescription.connection() : connection;
    }

    /** A deep copy; the copy shares no mutable state with this description. */
    public SessionDescription copy() {
        SessionDescription copy = new SessionDescription()
                .setVersion(version)
                .setOrigin(origin)
                .setSessionName(sessionName)
                .setSessionInformation(sessionInformation)
                .setUri(uri)
                .setEmails(emails)
                .setPhones(phones)
                .setConnection(connection)
                .setBandwidths(bandwidths)
                .setTimings(timings)
                .setOtherLines(otherLines)
                .setAttributes(attributes);
        media.forEach(m -> copy.media.add(m.copy()));
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SessionDescription that
                && version == that.version
                && origin.equals(that.origin)
                && sessionName.equals(that.sessionName)
                && Objects.equals(sessionInformation, that.sessionInformation)
                && Objects.equals(uri, that.uri)
                && emails.equals(that.emails)
                && phones.equals(that.phones)
                && Objects.equals(connection, that.connection)
                && bandwidths.equals(that.bandwidths)
                && timings.equals(that.timings)
                && otherLines.equals(that.otherLines)
                && attributes.equals(that.attributes)
                && media.equals(that.media);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, origin, sessionName, sessionInformation, uri, emails, phones, connection,
                bandwidths, timings, otherLines, attributes, media);
    }

    /** The SDP text, identical to {@link SdpWriter#write(SessionDescription)}. */
    @Override
    public String toString() {
        return SdpWriter.write(this);
    }
}
