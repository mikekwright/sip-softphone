package com.github.mikekwright.sip.softphone.sip.message;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A SIP request method (RFC 3261 §7.1, §27.4). Methods are case-sensitive tokens; the well known methods are
 * provided as constants, extension methods (e.g. {@code PUBLISH}) can be obtained with {@link #of(String)}.
 *
 * <p>This is intentionally a value class rather than an enum so that new methods can be supported by
 * registering request handlers without changing the library.</p>
 */
public final class SipMethod {

    private static final Map<String, SipMethod> KNOWN = new ConcurrentHashMap<>();

    /** RFC 3261 §13 - session establishment. */
    public static final SipMethod INVITE = register("INVITE");
    /** RFC 3261 §13.2.2.4 / §17.1.1.3 - acknowledges a final response to INVITE. */
    public static final SipMethod ACK = register("ACK");
    /** RFC 3261 §15 - session termination. */
    public static final SipMethod BYE = register("BYE");
    /** RFC 3261 §9 - cancels a pending request. */
    public static final SipMethod CANCEL = register("CANCEL");
    /** RFC 3261 §10 - binds a contact address to an address-of-record. */
    public static final SipMethod REGISTER = register("REGISTER");
    /** RFC 3261 §11 - capability query. */
    public static final SipMethod OPTIONS = register("OPTIONS");
    /** RFC 6086 - mid-session application information (e.g. DTMF relay). */
    public static final SipMethod INFO = register("INFO");
    /** RFC 3428 - instant messages. */
    public static final SipMethod MESSAGE = register("MESSAGE");
    /** RFC 3262 - reliable provisional responses. */
    public static final SipMethod PRACK = register("PRACK");
    /** RFC 3311 - session update without changing dialog state. */
    public static final SipMethod UPDATE = register("UPDATE");
    /** RFC 6665 - event subscription. */
    public static final SipMethod SUBSCRIBE = register("SUBSCRIBE");
    /** RFC 6665 - event notification. */
    public static final SipMethod NOTIFY = register("NOTIFY");
    /** RFC 3515 - call transfer. */
    public static final SipMethod REFER = register("REFER");
    /** RFC 3903 - event state publication. */
    public static final SipMethod PUBLISH = register("PUBLISH");

    private final String name;

    private SipMethod(String name) {
        this.name = name;
    }

    private static SipMethod register(String name) {
        SipMethod method = new SipMethod(name);
        KNOWN.put(name, method);
        return method;
    }

    /** Returns the method for the given token, creating an extension method if it is not well known. */
    public static SipMethod of(String name) {
        Objects.requireNonNull(name, "name");
        SipMethod known = KNOWN.get(name);
        if (known != null) {
            return known;
        }
        if (name.isEmpty() || !SipSyntax.isToken(name)) {
            throw new IllegalArgumentException("invalid SIP method token: " + name);
        }
        return new SipMethod(name);
    }

    public String name() {
        return name;
    }

    /** True for INVITE, which uses the INVITE transaction state machines (RFC 3261 §17.1.1, §17.2.1). */
    public boolean isInvite() {
        return this.equals(INVITE);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SipMethod other && other.name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
