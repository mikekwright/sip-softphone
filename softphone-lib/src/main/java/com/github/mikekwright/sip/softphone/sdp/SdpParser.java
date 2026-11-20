package com.github.mikekwright.sip.softphone.sdp;

import java.nio.charset.StandardCharsets;

/**
 * Parses SDP text (RFC 8866 §5, §9) into a {@link SessionDescription}.
 *
 * <p>The parser follows the robustness principle:</p>
 * <ul>
 *   <li>lines may end with CRLF or a bare LF (RFC 8866 §5 asks parsers to accept LF); trailing whitespace is
 *       trimmed and blank lines are ignored;</li>
 *   <li>all attributes are kept, including unknown ones, in their original order;</li>
 *   <li>{@code r=}, {@code z=}, {@code k=} and lines with unknown type letters are preserved verbatim in
 *       {@link SessionDescription#otherLines()} / {@link MediaDescription#otherLines()};</li>
 *   <li>the order of session-level lines is not enforced.</li>
 * </ul>
 * <p>It is strict where a wrong guess would be harmful: the first line must be {@code v=}, an {@code o=} line is
 * required, every line must have the form {@code <letter>=<value>}, and the structured lines {@code v=},
 * {@code o=}, {@code c=}, {@code b=}, {@code t=} and {@code m=} must be well formed. Violations raise
 * {@link SdpParseException} with the line number.</p>
 */
public final class SdpParser {

    private SdpParser() {
    }

    /**
     * Parses UTF-8 encoded SDP (the SDP charset default, RFC 8866 §6.10).
     *
     * @throws SdpParseException if the description is malformed
     */
    public static SessionDescription parse(byte[] sdp) {
        return parse(new String(sdp, StandardCharsets.UTF_8));
    }

    /**
     * Parses SDP text.
     *
     * @throws SdpParseException if the description is malformed
     */
    public static SessionDescription parse(String sdp) {
        if (sdp == null) {
            throw new SdpParseException("no session description", 0);
        }
        SessionDescription session = new SessionDescription();
        session.timings().clear();
        MediaDescription media = null;
        boolean seenVersion = false;
        boolean seenOrigin = false;
        boolean seenSessionName = false;

        String[] lines = sdp.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i].stripTrailing();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() < 2 || line.charAt(1) != '=' || !isTypeLetter(line.charAt(0))) {
                throw new SdpParseException("expected <type>=<value> but got '" + abbreviate(line) + "'",
                        lineNumber);
            }
            char type = line.charAt(0);
            String value = line.substring(2);

            if (!seenVersion) {
                if (type != 'v') {
                    throw new SdpParseException("session description must start with v=", lineNumber);
                }
                session.setVersion(parseInt(value.trim(), "version", lineNumber));
                seenVersion = true;
                continue;
            }

            if (media == null) {
                switch (type) {
                    case 'v' -> throw new SdpParseException("duplicate v= line", lineNumber);
                    case 'o' -> {
                        if (seenOrigin) {
                            throw new SdpParseException("duplicate o= line", lineNumber);
                        }
                        session.setOrigin(parseOrigin(value, lineNumber));
                        seenOrigin = true;
                    }
                    case 's' -> {
                        if (seenSessionName) {
                            throw new SdpParseException("duplicate s= line", lineNumber);
                        }
                        // "s= " (a single space) is a valid, recommended empty name (RFC 8866 §5.3)
                        session.setSessionName(value.isEmpty() ? " " : value);
                        seenSessionName = true;
                    }
                    case 'i' -> session.setSessionInformation(value);
                    case 'u' -> session.setUri(value);
                    case 'e' -> session.emails().add(value);
                    case 'p' -> session.phones().add(value);
                    case 'c' -> session.setConnection(parseConnection(value, lineNumber));
                    case 'b' -> session.bandwidths().add(parseBandwidth(value, lineNumber));
                    case 't' -> session.timings().add(parseTiming(value, lineNumber));
                    case 'a' -> session.addAttribute(parseAttribute(value, lineNumber));
                    case 'm' -> {
                        media = parseMediaLine(value, lineNumber);
                        session.addMedia(media);
                    }
                    default -> session.otherLines().add(line);
                }
            } else {
                switch (type) {
                    case 'm' -> {
                        media = parseMediaLine(value, lineNumber);
                        session.addMedia(media);
                    }
                    case 'i' -> media.setInformation(value);
                    case 'c' -> media.setConnection(parseConnection(value, lineNumber));
                    case 'b' -> media.bandwidths().add(parseBandwidth(value, lineNumber));
                    case 'a' -> media.addAttribute(parseAttribute(value, lineNumber));
                    case 'v', 'o', 's', 'u', 'e', 'p', 't', 'r', 'z' -> throw new SdpParseException(
                            "session-level line '" + type + "=' inside a media description", lineNumber);
                    default -> media.otherLines().add(line);
                }
            }
        }

        if (!seenVersion) {
            throw new SdpParseException("empty session description", 0);
        }
        if (!seenOrigin) {
            throw new SdpParseException("missing o= line", 0);
        }
        if (session.timings().isEmpty()) {
            session.timings().add(Timing.PERMANENT);
        }
        return session;
    }

    private static boolean isTypeLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    /** {@code o=<username> <sess-id> <sess-version> <nettype> <addrtype> <unicast-address>} (RFC 8866 §5.2). */
    private static Origin parseOrigin(String value, int lineNumber) {
        String[] tokens = tokens(value);
        if (tokens.length != 6) {
            throw new SdpParseException("o= line must have 6 fields: '" + value + "'", lineNumber);
        }
        long version;
        try {
            version = Long.parseLong(tokens[2]);
        } catch (NumberFormatException e) {
            throw new SdpParseException("invalid o= session version '" + tokens[2] + "'", lineNumber);
        }
        return new Origin(tokens[0], tokens[1], version, tokens[3], tokens[4], tokens[5]);
    }

    /** {@code c=<nettype> <addrtype> <connection-address>} (RFC 8866 §5.7). */
    private static Connection parseConnection(String value, int lineNumber) {
        String[] tokens = tokens(value);
        if (tokens.length != 3) {
            throw new SdpParseException("c= line must have 3 fields: '" + value + "'", lineNumber);
        }
        return new Connection(tokens[0], tokens[1], tokens[2]);
    }

    /** {@code b=<bwtype>:<bandwidth>} (RFC 8866 §5.8). */
    private static Bandwidth parseBandwidth(String value, int lineNumber) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            throw new SdpParseException("b= line must be <type>:<value>: '" + value + "'", lineNumber);
        }
        long bandwidth;
        try {
            bandwidth = Long.parseLong(value.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new SdpParseException("invalid bandwidth '" + value + "'", lineNumber);
        }
        if (bandwidth < 0) {
            throw new SdpParseException("negative bandwidth '" + value + "'", lineNumber);
        }
        return new Bandwidth(value.substring(0, colon).trim(), bandwidth);
    }

    /** {@code t=<start-time> <stop-time>} (RFC 8866 §5.9). */
    private static Timing parseTiming(String value, int lineNumber) {
        String[] tokens = tokens(value);
        if (tokens.length != 2) {
            throw new SdpParseException("t= line must have 2 fields: '" + value + "'", lineNumber);
        }
        try {
            return new Timing(Long.parseLong(tokens[0]), Long.parseLong(tokens[1]));
        } catch (NumberFormatException e) {
            throw new SdpParseException("invalid t= line '" + value + "'", lineNumber);
        }
    }

    /** {@code a=<attribute>} or {@code a=<attribute>:<value>} (RFC 8866 §5.13). */
    private static Attribute parseAttribute(String value, int lineNumber) {
        int colon = value.indexOf(':');
        String name = colon < 0 ? value : value.substring(0, colon);
        if (name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
            throw new SdpParseException("invalid attribute name in 'a=" + abbreviate(value) + "'", lineNumber);
        }
        return new Attribute(name, colon < 0 ? null : value.substring(colon + 1));
    }

    /** {@code m=<media> <port>[/<number of ports>] <proto> <fmt> ...} (RFC 8866 §5.14). */
    private static MediaDescription parseMediaLine(String value, int lineNumber) {
        String[] tokens = tokens(value);
        if (tokens.length < 4) {
            throw new SdpParseException("m= line must have media, port, proto and at least one format: '"
                    + value + "'", lineNumber);
        }
        String portToken = tokens[1];
        Integer numberOfPorts = null;
        int slash = portToken.indexOf('/');
        if (slash >= 0) {
            numberOfPorts = parseInt(portToken.substring(slash + 1), "number of ports", lineNumber);
            if (numberOfPorts <= 0) {
                throw new SdpParseException("invalid number of ports '" + portToken + "'", lineNumber);
            }
            portToken = portToken.substring(0, slash);
        }
        int port = parseInt(portToken, "port", lineNumber);
        if (port < 0 || port > 65535) {
            throw new SdpParseException("port out of range: " + port, lineNumber);
        }
        MediaDescription media = new MediaDescription(tokens[0], port, tokens[2]).setNumberOfPorts(numberOfPorts);
        for (int i = 3; i < tokens.length; i++) {
            media.formats().add(tokens[i]);
        }
        return media;
    }

    private static int parseInt(String text, String what, int lineNumber) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new SdpParseException("invalid " + what + " '" + text + "'", lineNumber);
        }
    }

    private static String[] tokens(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("[ \\t]+");
    }

    private static String abbreviate(String text) {
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }
}
