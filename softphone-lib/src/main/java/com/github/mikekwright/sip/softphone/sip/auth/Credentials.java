package com.github.mikekwright.sip.softphone.sip.auth;

import java.util.Objects;

/**
 * A username/password pair used to answer digest challenges.
 *
 * @param username authentication user name (often, but not necessarily, the AOR user part)
 * @param password shared secret
 * @param realm    restrict these credentials to one realm, or {@code null} for any realm
 */
public record Credentials(String username, String password, String realm) {

    public Credentials {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }

    public Credentials(String username, String password) {
        this(username, password, null);
    }

    public boolean matchesRealm(String r) {
        return realm == null || realm.equals(r);
    }

    @Override
    public String toString() {
        return "Credentials[" + username + "@" + (realm == null ? "*" : realm) + "]";
    }
}
