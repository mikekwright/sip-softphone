package com.github.mikekwright.sip.softphone.sip.auth;

import java.util.Optional;

/**
 * Supplies credentials for a realm when a request is challenged with 401 or 407 (RFC 3261 §22.2, §22.3).
 */
@FunctionalInterface
public interface CredentialsProvider {

    CredentialsProvider NONE = realm -> Optional.empty();

    Optional<Credentials> credentialsFor(String realm);

    static CredentialsProvider of(Credentials credentials) {
        return realm -> credentials.matchesRealm(realm) ? Optional.of(credentials) : Optional.empty();
    }
}
