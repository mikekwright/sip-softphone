package com.github.mikekwright.sip.softphone.sip.message;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * An ordered, case-insensitive multimap of SIP header fields (RFC 3261 §7.3).
 *
 * <p>Header order between fields with different names is not significant in SIP, but the relative order of fields
 * with the same name is (e.g. {@code Via}, {@code Route}), and this container preserves both. Values of list
 * headers (see {@link HeaderNames#isListHeader(String)}) are stored one value per entry, which makes it simple to
 * push/pop {@code Via} and {@code Route} values.</p>
 *
 * <p>Not thread-safe.</p>
 */
public final class SipHeaders implements Iterable<SipHeaders.Header> {

    /** One header field: canonical name and raw value. */
    public record Header(String name, String value) {
        @Override
        public String toString() {
            return name + ": " + value;
        }
    }

    private final List<Header> headers = new ArrayList<>();

    public SipHeaders() {
    }

    public SipHeaders(SipHeaders other) {
        headers.addAll(other.headers);
    }

    /** Appends a header value; list header values containing commas are split into separate entries. */
    public SipHeaders add(String name, String value) {
        String canonical = HeaderNames.canonical(name);
        if (HeaderNames.isListHeader(canonical) && value.indexOf(',') >= 0) {
            for (String part : SipSyntax.splitList(value)) {
                headers.add(new Header(canonical, part));
            }
        } else {
            headers.add(new Header(canonical, value.trim()));
        }
        return this;
    }

    public SipHeaders add(String name, Object value) {
        return add(name, String.valueOf(value));
    }

    /** Inserts a value before all existing values of the same header (e.g. pushing a {@code Via}). */
    public SipHeaders addFirst(String name, String value) {
        String canonical = HeaderNames.canonical(name);
        int idx = indexOf(canonical);
        Header h = new Header(canonical, value.trim());
        if (idx < 0) {
            headers.add(h);
        } else {
            headers.add(idx, h);
        }
        return this;
    }

    /** Replaces all values of a header with a single value, keeping the position of the first occurrence. */
    public SipHeaders set(String name, String value) {
        String canonical = HeaderNames.canonical(name);
        int idx = indexOf(canonical);
        remove(canonical);
        Header h = new Header(canonical, value.trim());
        if (idx < 0 || idx > headers.size()) {
            headers.add(h);
        } else {
            headers.add(idx, h);
        }
        return this;
    }

    public SipHeaders set(String name, Object value) {
        return set(name, String.valueOf(value));
    }

    /** Removes all values of a header. */
    public SipHeaders remove(String name) {
        String canonical = HeaderNames.canonical(name);
        headers.removeIf(h -> h.name.equalsIgnoreCase(canonical));
        return this;
    }

    /** Removes and returns the first value of a header (e.g. popping the top {@code Via} or {@code Route}). */
    public Optional<String> removeFirst(String name) {
        int idx = indexOf(HeaderNames.canonical(name));
        if (idx < 0) {
            return Optional.empty();
        }
        return Optional.of(headers.remove(idx).value);
    }

    public boolean contains(String name) {
        return indexOf(HeaderNames.canonical(name)) >= 0;
    }

    /** The first value of a header, if present. */
    public Optional<String> first(String name) {
        int idx = indexOf(HeaderNames.canonical(name));
        return idx < 0 ? Optional.empty() : Optional.of(headers.get(idx).value);
    }

    /** First value or {@code null}. */
    public String get(String name) {
        return first(name).orElse(null);
    }

    /** All values of a header in message order. */
    public List<String> all(String name) {
        String canonical = HeaderNames.canonical(name);
        List<String> values = new ArrayList<>();
        for (Header h : headers) {
            if (h.name.equalsIgnoreCase(canonical)) {
                values.add(h.value);
            }
        }
        return values;
    }

    /** All values of a header parsed with the given parser. */
    public <T> List<T> all(String name, Function<String, T> parser) {
        return all(name).stream().map(parser).toList();
    }

    /** Copies every value of {@code name} from another header set, appending them in order. */
    public SipHeaders copyFrom(SipHeaders other, String name) {
        for (String v : other.all(name)) {
            headers.add(new Header(HeaderNames.canonical(name), v));
        }
        return this;
    }

    public int size() {
        return headers.size();
    }

    @Override
    public Iterator<Header> iterator() {
        return List.copyOf(headers).iterator();
    }

    private int indexOf(String canonical) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).name.equalsIgnoreCase(canonical)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Header h : headers) {
            sb.append(h).append("\r\n");
        }
        return sb.toString();
    }
}
