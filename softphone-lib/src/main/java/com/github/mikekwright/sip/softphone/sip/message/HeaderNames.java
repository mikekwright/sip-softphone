package com.github.mikekwright.sip.softphone.sip.message;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Header name constants, compact form expansion (RFC 3261 §7.3.3) and the set of headers whose values form
 * comma separated lists (RFC 3261 §7.3.1).
 */
public final class HeaderNames {

    public static final String ACCEPT = "Accept";
    public static final String ALLOW = "Allow";
    public static final String ALLOW_EVENTS = "Allow-Events";
    public static final String AUTHORIZATION = "Authorization";
    public static final String CALL_ID = "Call-ID";
    public static final String CONTACT = "Contact";
    public static final String CONTENT_DISPOSITION = "Content-Disposition";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CSEQ = "CSeq";
    public static final String DATE = "Date";
    public static final String EVENT = "Event";
    public static final String EXPIRES = "Expires";
    public static final String FROM = "From";
    public static final String MAX_FORWARDS = "Max-Forwards";
    public static final String MIN_EXPIRES = "Min-Expires";
    public static final String PROXY_AUTHENTICATE = "Proxy-Authenticate";
    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    public static final String PROXY_REQUIRE = "Proxy-Require";
    public static final String REASON = "Reason";
    public static final String RECORD_ROUTE = "Record-Route";
    public static final String REFER_TO = "Refer-To";
    public static final String REQUIRE = "Require";
    public static final String RETRY_AFTER = "Retry-After";
    public static final String ROUTE = "Route";
    public static final String SERVER = "Server";
    public static final String SUBJECT = "Subject";
    public static final String SUPPORTED = "Supported";
    public static final String TIMESTAMP = "Timestamp";
    public static final String TO = "To";
    public static final String UNSUPPORTED = "Unsupported";
    public static final String USER_AGENT = "User-Agent";
    public static final String VIA = "Via";
    public static final String WARNING = "Warning";
    public static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private static final Map<String, String> CANONICAL = new HashMap<>();
    private static final Map<Character, String> COMPACT = Map.ofEntries(
            Map.entry('a', "Accept-Contact"),
            Map.entry('b', "Referred-By"),
            Map.entry('c', CONTENT_TYPE),
            Map.entry('d', "Request-Disposition"),
            Map.entry('e', CONTENT_ENCODING),
            Map.entry('f', FROM),
            Map.entry('i', CALL_ID),
            Map.entry('j', "Reject-Contact"),
            Map.entry('k', SUPPORTED),
            Map.entry('l', CONTENT_LENGTH),
            Map.entry('m', CONTACT),
            Map.entry('o', EVENT),
            Map.entry('r', REFER_TO),
            Map.entry('s', SUBJECT),
            Map.entry('t', TO),
            Map.entry('u', ALLOW_EVENTS),
            Map.entry('v', VIA),
            Map.entry('x', "Session-Expires"),
            Map.entry('y', "Identity"));

    /** Headers whose values may be combined into one comma separated line (RFC 3261 §7.3.1). */
    private static final Set<String> LIST_HEADERS = Set.of(
            "accept", "accept-encoding", "accept-language", "alert-info", "allow", "allow-events", "call-info",
            "contact", "content-encoding", "content-language", "error-info", "in-reply-to", "path",
            "proxy-require", "record-route", "require", "route", "service-route", "supported", "unsupported",
            "via", "warning", "p-asserted-identity");

    static {
        for (String name : new String[]{ACCEPT, ALLOW, ALLOW_EVENTS, AUTHORIZATION, CALL_ID, CONTACT,
                CONTENT_DISPOSITION, CONTENT_ENCODING, CONTENT_LENGTH, CONTENT_TYPE, CSEQ, DATE, EVENT, EXPIRES,
                FROM, MAX_FORWARDS, MIN_EXPIRES, PROXY_AUTHENTICATE, PROXY_AUTHORIZATION, PROXY_REQUIRE, REASON,
                RECORD_ROUTE, REFER_TO, REQUIRE, RETRY_AFTER, ROUTE, SERVER, SUBJECT, SUPPORTED, TIMESTAMP, TO,
                UNSUPPORTED, USER_AGENT, VIA, WARNING, WWW_AUTHENTICATE, "Accept-Encoding", "Accept-Language",
                "Alert-Info", "Call-Info", "Error-Info", "In-Reply-To", "Organization", "Priority", "Reply-To",
                "Service-Route", "Path", "Session-Expires", "Min-SE", "RSeq", "RAck", "Referred-By",
                "Subscription-State", "P-Asserted-Identity", "Accept-Contact", "Reject-Contact",
                "Request-Disposition", "Identity", "Content-Language", "MIME-Version", "Authentication-Info"}) {
            CANONICAL.put(name.toLowerCase(Locale.ROOT), name);
        }
    }

    private HeaderNames() {
    }

    /**
     * Normalizes a header name: expands compact forms and applies the conventional capitalization for known
     * headers. Unknown header names are returned unchanged.
     */
    public static String canonical(String name) {
        String trimmed = name.trim();
        if (trimmed.length() == 1) {
            String expanded = COMPACT.get(Character.toLowerCase(trimmed.charAt(0)));
            if (expanded != null) {
                return expanded;
            }
        }
        String known = CANONICAL.get(trimmed.toLowerCase(Locale.ROOT));
        return known != null ? known : trimmed;
    }

    /**
     * True for list headers that are written comma separated on one line when serializing (e.g. Allow, Supported).
     * Via, Route, Record-Route, Contact, Path and Service-Route stay one value per line for readability.
     */
    public static boolean isCombinable(String name) {
        String c = canonical(name).toLowerCase(Locale.ROOT);
        return LIST_HEADERS.contains(c) && !Set.of("via", "route", "record-route", "contact", "path",
                "service-route", "p-asserted-identity").contains(c);
    }

    /** True if the header's grammar is a comma separated list that may be split into separate values. */
    public static boolean isListHeader(String name) {
        return LIST_HEADERS.contains(canonical(name).toLowerCase(Locale.ROOT));
    }
}
