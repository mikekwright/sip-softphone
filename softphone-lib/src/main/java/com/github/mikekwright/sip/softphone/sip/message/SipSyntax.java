package com.github.mikekwright.sip.softphone.sip.message;

import java.util.ArrayList;
import java.util.List;

/**
 * Low level helpers for the RFC 3261 §25 grammar: tokens, quoted strings and comma separated header lists.
 */
public final class SipSyntax {

    private SipSyntax() {
    }

    /** RFC 3261 §25.1: token = 1*(alphanum / "-" / "." / "!" / "%" / "*" / "_" / "+" / "`" / "'" / "~"). */
    public static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-.!%*_+`'~".indexOf(c) >= 0;
    }

    public static boolean isToken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isTokenChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Wraps a value in double quotes, escaping {@code "} and {@code \} (RFC 3261 §25.1 quoted-string). */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** Removes surrounding quotes and unescapes quoted-pairs; returns the input unchanged when not quoted. */
    public static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
            return v;
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length() - 1) {
                c = v.charAt(++i);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Splits a header value on top level commas (RFC 3261 §7.3.1), ignoring commas inside quoted strings,
     * angle brackets ({@code <sip:a,b>}) and parentheses (comments).
     */
    public static List<String> splitList(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int angle = 0;
        int paren = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted) {
                current.append(c);
                if (c == '\\' && i + 1 < value.length()) {
                    current.append(value.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case '<' -> angle++;
                case '>' -> angle = Math.max(0, angle - 1);
                case '(' -> paren++;
                case ')' -> paren = Math.max(0, paren - 1);
                default -> {
                }
            }
            if (c == ',' && angle == 0 && paren == 0) {
                String part = current.toString().trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }

    /**
     * Finds the index of {@code target} at the top level of {@code s} starting at {@code from}, skipping quoted
     * strings and bracketed sections. Returns -1 if not found.
     */
    public static int indexOfUnquoted(String s, char target, int from) {
        boolean quoted = false;
        int angle = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == target && angle == 0) {
                return i;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle = Math.max(0, angle - 1);
            }
        }
        return -1;
    }
}
