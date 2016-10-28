package com.github.mikekwright.sip.softphone;

import com.github.mikekwright.sip.softphone.impl.JainSoftphone;
import com.github.mikekwright.sip.softphone.impl.JainSoftphone;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SoftphoneFactory {
    private final SipCredentialProvider provider;

    @Inject
    public SoftphoneFactory(SipCredentialProvider provider) {
        this.provider = provider;
    }

    public Softphone createSoftphone() {
        return new JainSoftphone(provider);
    }

    public Softphone createSoftphoneWithCredentials(final String username, final String password) {
        SipCredentialProvider customProvider = createCustomSipCredentialProvider(username, password);
        return new JainSoftphone(customProvider);
    }

    private static SipCredentialProvider createCustomSipCredentialProvider(final String username, final String password) {
        return new SipCredentialProvider() {
            @Override
            public String password() {
                return password;
            }

            @Override
            public String username() {
                return username;
            }
        };
    }
}
