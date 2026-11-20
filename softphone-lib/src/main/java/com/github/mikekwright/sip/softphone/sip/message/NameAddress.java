package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Objects;

/**
 * The value of an address header such as {@code From}, {@code To}, {@code Contact}, {@code Route} or
 * {@code Record-Route} (RFC 3261 §20.10, §25.1 {@code name-addr / addr-spec}):
 * <pre>"Alice" &lt;sip:alice@example.com&gt;;tag=1928301774</pre>
 *
 * <p>When an address is written without angle brackets, parameters following the URI are header parameters
 * (RFC 3261 §20.10); this class follows that rule when parsing and always emits angle brackets when formatting.
 * The special Contact value {@code *} is represented by {@link #isWildcard()}.</p>
 */
public final class NameAddress {

    private final String displayName;
    private final SipUri uri;
    private final Parameters parameters;
    private final boolean wildcard;

    private NameAddress(String displayName, SipUri uri, Parameters parameters, boolean wildcard) {
        this.displayName = displayName;
        this.uri = uri;
        this.parameters = parameters;
        this.wildcard = wildcard;
    }

    public NameAddress(String displayName, SipUri uri) {
        this(displayName, Objects.requireNonNull(uri, "uri"), new Parameters(), false);
    }

    public NameAddress(SipUri uri) {
        this(null, uri);
    }

    /** The {@code Contact: *} wildcard used to remove all registrations (RFC 3261 §10.2.2). */
    public static NameAddress wildcard() {
        return new NameAddress(null, null, new Parameters(), true);
    }

    public static NameAddress parse(String text) {
        Objects.requireNonNull(text, "text");
        String s = text.trim();
        if (s.equals("*")) {
            return wildcard();
        }
        int lt = SipSyntax.indexOfUnquoted(s, '<', 0);
        if (lt >= 0) {
            String display = s.substring(0, lt).trim();
            int gt = s.indexOf('>', lt);
            if (gt < 0) {
                throw new SipParseException("unterminated '<' in address: " + text);
            }
            SipUri uri = SipUri.parse(s.substring(lt + 1, gt));
            Parameters params = Parameters.parse(s.substring(gt + 1));
            return new NameAddress(display.isEmpty() ? null : SipSyntax.unquote(display), uri, params, false);
        }
        // addr-spec form: everything after the first ';' are header parameters
        int semi = s.indexOf(';');
        String uriText = semi < 0 ? s : s.substring(0, semi);
        Parameters params = semi < 0 ? new Parameters() : Parameters.parse(s.substring(semi));
        return new NameAddress(null, SipUri.parse(uriText), params, false);
    }

    public String displayName() {
        return displayName;
    }

    public SipUri uri() {
        return uri;
    }

    public boolean isWildcard() {
        return wildcard;
    }

    public Parameters parameters() {
        return new Parameters(parameters);
    }

    public String parameter(String name) {
        return parameters.get(name);
    }

    public boolean hasParameter(String name) {
        return parameters.has(name);
    }

    /** The {@code tag} parameter of From/To headers (RFC 3261 §19.3), or {@code null}. */
    public String tag() {
        return parameters.get("tag");
    }

    public NameAddress withTag(String tag) {
        return withParameter("tag", tag);
    }

    public NameAddress withParameter(String name, String value) {
        return new NameAddress(displayName, uri, new Parameters(parameters).set(name, value), wildcard);
    }

    public NameAddress withoutParameter(String name) {
        return new NameAddress(displayName, uri, new Parameters(parameters).remove(name), wildcard);
    }

    public NameAddress withUri(SipUri newUri) {
        return new NameAddress(displayName, newUri, parameters, wildcard);
    }

    public NameAddress withDisplayName(String name) {
        return new NameAddress(name, uri, parameters, wildcard);
    }

    @Override
    public String toString() {
        if (wildcard) {
            return "*";
        }
        StringBuilder sb = new StringBuilder();
        if (displayName != null && !displayName.isEmpty()) {
            sb.append(SipSyntax.isToken(displayName) ? displayName : SipSyntax.quote(displayName)).append(' ');
        }
        sb.append('<').append(uri).append('>').append(parameters);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NameAddress other && wildcard == other.wildcard && Objects.equals(uri, other.uri)
                && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, wildcard);
    }
}
