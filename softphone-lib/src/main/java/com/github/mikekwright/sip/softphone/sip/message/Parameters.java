package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered set of {@code ;name[=value]} parameters as used by URIs (RFC 3261 §19.1.1), and by the
 * {@code Via}, {@code From}, {@code To} and {@code Contact} headers. Names compare case-insensitively; values are
 * kept verbatim (including surrounding quotes, if any) so that messages round trip unchanged. A parameter
 * without a value (e.g. {@code ;lr} or {@code ;rport}) has a {@code null} value.
 */
public final class Parameters implements Iterable<Map.Entry<String, String>> {

    /** Keyed by lower-case name; the entry stores the original-case name and value. */
    private final LinkedHashMap<String, Map.Entry<String, String>> params = new LinkedHashMap<>();

    public Parameters() {
    }

    public Parameters(Parameters other) {
        params.putAll(other.params);
    }

    /**
     * Parses a parameter list such as {@code ;branch=z9hG4bK1;rport}. Leading whitespace and the leading
     * semicolon are optional.
     */
    public static Parameters parse(String text) {
        Parameters p = new Parameters();
        if (text == null) {
            return p;
        }
        String s = text.trim();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == ';' || Character.isWhitespace(s.charAt(i))) {
                i++;
                continue;
            }
            int end = SipSyntax.indexOfUnquoted(s, ';', i);
            if (end < 0) {
                end = s.length();
            }
            String part = s.substring(i, end).trim();
            int eq = part.indexOf('=');
            if (eq < 0) {
                p.set(part, null);
            } else {
                p.set(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
            i = end + 1;
        }
        return p;
    }

    public boolean has(String name) {
        return params.containsKey(key(name));
    }

    /** Returns the raw value; {@code null} both when absent and for flag parameters (use {@link #has}). */
    public String get(String name) {
        Map.Entry<String, String> e = params.get(key(name));
        return e == null ? null : e.getValue();
    }

    public Optional<String> value(String name) {
        return Optional.ofNullable(get(name));
    }

    /** Sets (or replaces) a parameter; pass a {@code null} value for a flag parameter. */
    public Parameters set(String name, String value) {
        Objects.requireNonNull(name, "name");
        params.put(key(name), Map.entry(name, value == null ? "\u0000" : value));
        return this;
    }

    public Parameters remove(String name) {
        params.remove(key(name));
        return this;
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }

    public int size() {
        return params.size();
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        return params.values().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .map(e -> e.getValue().equals("\u0000") ? new FlagEntry(e.getKey()) : e)
                .iterator();
    }

    /** Formats the parameters as {@code ;a=b;c}. Empty string when there are none. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.values()) {
            sb.append(';').append(e.getKey());
            if (!e.getValue().equals("\u0000")) {
                sb.append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Parameters other) || other.params.size() != params.size()) {
            return false;
        }
        for (Map.Entry<String, Map.Entry<String, String>> e : params.entrySet()) {
            Map.Entry<String, String> theirs = other.params.get(e.getKey());
            if (theirs == null || !theirs.getValue().equalsIgnoreCase(e.getValue().getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return params.keySet().hashCode();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // Internally a flag parameter is stored with a NUL sentinel because Map.entry rejects null values.
    private record FlagEntry(String key) implements Map.Entry<String, String> {
        @Override
        public String getKey() {
            return key;
        }

        @Override
        public String getValue() {
            return null;
        }

        @Override
        public String setValue(String value) {
            throw new UnsupportedOperationException();
        }
    }
}
