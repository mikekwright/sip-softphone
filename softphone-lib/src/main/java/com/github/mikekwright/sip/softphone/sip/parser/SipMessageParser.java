package com.github.mikekwright.sip.softphone.sip.parser;

import com.github.mikekwright.sip.softphone.sip.message.HeaderNames;
import com.github.mikekwright.sip.softphone.sip.message.SipHeaders;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipMethod;
import com.github.mikekwright.sip.softphone.sip.message.SipParseException;
import com.github.mikekwright.sip.softphone.sip.message.SipRequest;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.message.SipUri;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses SIP messages from their wire representation (RFC 3261 §7, §25).
 *
 * <p>The parser is deliberately lenient where RFC 3261 asks implementations to be (§7.5 leading CRLFs, LF-only line
 * endings, header line folding, compact header names, arbitrary whitespace around the colon) and strict where an
 * error would make the message meaningless (bad start line, header without colon, invalid Content-Length).</p>
 *
 * <p>Framing is transport dependent (RFC 3261 §18.3): for datagrams the message is the whole packet and the body may
 * be truncated to {@code Content-Length}; for streams {@code Content-Length} delimits the message. The Netty codecs
 * in {@code sip.codec} take care of framing and call {@link #parseHead(String)} / {@link #parse(byte[])}.</p>
 */
public final class SipMessageParser {

    /** Upper bound for the start line plus headers, protects against memory exhaustion. */
    public static final int MAX_HEAD_SIZE = 64 * 1024;

    private SipMessageParser() {
    }

    /**
     * Parses a complete message as received in a datagram. If {@code Content-Length} is absent the remainder of the
     * datagram is the body; if it is smaller than the remainder, the extra bytes are discarded; if it is larger the
     * message is rejected (RFC 3261 §18.3).
     */
    public static SipMessage parse(byte[] data) {
        int start = skipLeadingLineBreaks(data, 0);
        int headEnd = findHeadEnd(data, start, data.length);
        if (headEnd < 0) {
            // tolerate a message with headers but no blank line terminator (no body)
            return parseHead(new String(data, start, data.length - start, StandardCharsets.UTF_8));
        }
        int bodyStart = headEnd + separatorLength(data, headEnd);
        SipMessage msg = parseHead(new String(data, start, headEnd - start, StandardCharsets.UTF_8));
        int available = data.length - bodyStart;
        int contentLength = contentLength(msg);
        if (contentLength < 0) {
            contentLength = available;
        } else if (contentLength > available) {
            throw new SipParseException("Content-Length " + contentLength + " exceeds the " + available
                    + " body bytes received");
        }
        attachBody(msg, Arrays.copyOfRange(data, bodyStart, bodyStart + contentLength));
        return msg;
    }

    /** Parses a message string; convenient for tests and examples. Line endings may be LF or CRLF. */
    public static SipMessage parse(String text) {
        return parse(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses the start line and headers (everything before the empty line). The returned message has an empty body;
     * attach a body with {@link #attachBody(SipMessage, byte[])}.
     */
    public static SipMessage parseHead(String head) {
        List<String> lines = unfold(head);
        if (lines.isEmpty()) {
            throw new SipParseException("empty message");
        }
        SipMessage msg = parseStartLine(lines.getFirst());
        SipHeaders headers = msg.headers();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new SipParseException("malformed header line: " + line);
            }
            String name = line.substring(0, colon).trim();
            if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
                throw new SipParseException("malformed header name: " + line);
            }
            headers.add(name, line.substring(colon + 1).trim());
        }
        return msg;
    }

    /** Sets the body without touching the Content-Type header. */
    public static void attachBody(SipMessage msg, byte[] body) {
        msg.setRawBody(body);
    }

    /** The declared Content-Length or -1 when the header is absent. */
    public static int contentLength(SipMessage msg) {
        String cl = msg.header(HeaderNames.CONTENT_LENGTH);
        if (cl == null) {
            return -1;
        }
        try {
            int v = Integer.parseInt(cl.trim());
            if (v < 0) {
                throw new SipParseException("negative Content-Length: " + cl);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new SipParseException("invalid Content-Length: " + cl, e);
        }
    }

    private static SipMessage parseStartLine(String line) {
        if (line.startsWith(SipMessage.SIP_VERSION + " ") || line.startsWith("SIP/")) {
            // Status-Line = SIP-Version SP Status-Code SP Reason-Phrase
            String[] parts = line.split(" ", 3);
            if (parts.length < 2 || !parts[0].equals(SipMessage.SIP_VERSION)) {
                throw new SipParseException("unsupported or malformed status line: " + line);
            }
            int code;
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new SipParseException("invalid status code: " + line, e);
            }
            if (code < 100 || code > 699) {
                throw new SipParseException("status code out of range: " + line);
            }
            return new SipResponse(code, parts.length > 2 ? parts[2].trim() : "", new SipHeaders());
        }
        // Request-Line = Method SP Request-URI SP SIP-Version
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3) {
            throw new SipParseException("malformed request line: " + line);
        }
        if (!parts[2].equals(SipMessage.SIP_VERSION)) {
            throw new SipParseException("unsupported SIP version: " + parts[2]);
        }
        try {
            return new SipRequest(SipMethod.of(parts[0]), SipUri.parse(parts[1]), new SipHeaders());
        } catch (IllegalArgumentException e) {
            throw new SipParseException("malformed request line: " + line, e);
        }
    }

    /** Splits into lines (CRLF or LF) and joins folded continuation lines (RFC 3261 §7.3.1). */
    private static List<String> unfold(String head) {
        String[] raw = head.split("\r?\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        for (String line : raw) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && !lines.isEmpty()) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + " " + line.trim());
            } else {
                lines.add(line);
            }
        }
        while (!lines.isEmpty() && lines.getFirst().isEmpty()) {
            lines.removeFirst();
        }
        return lines;
    }

    /** Skips CRLF / LF characters preceding the start line (RFC 3261 §7.5). */
    public static int skipLeadingLineBreaks(byte[] data, int from) {
        int i = from;
        while (i < data.length && (data[i] == '\r' || data[i] == '\n')) {
            i++;
        }
        return i;
    }

    /**
     * Finds the end of the header section: the index of the blank line separator ({@code CRLFCRLF} or {@code LFLF}),
     * or -1 if not present in {@code [from, to)}.
     */
    public static int findHeadEnd(byte[] data, int from, int to) {
        for (int i = from; i < to - 1; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                return i;
            }
            if (i + 3 < to && data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Length of the separator found at {@code headEnd} (2 for LFLF, 4 for CRLFCRLF). */
    public static int separatorLength(byte[] data, int headEnd) {
        return data[headEnd] == '\n' ? 2 : 4;
    }
}
