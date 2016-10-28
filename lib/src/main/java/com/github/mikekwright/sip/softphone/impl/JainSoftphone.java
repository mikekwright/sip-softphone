package com.github.mikekwright.sip.softphone.impl;

import com.github.mikekwright.sip.softphone.Dialog;
import com.github.mikekwright.sip.softphone.SipCredentialProvider;
import com.github.mikekwright.sip.softphone.Softphone;

import java.io.IOException;

public class JainSoftphone implements Softphone {

    private final SipCredentialProvider credentials;
    private boolean initialized;

    public JainSoftphone(SipCredentialProvider credentials) {
        this.credentials = credentials;
        this.initialized = false;
    }

    private void init() {

    }

    @Override
    public synchronized Dialog callNumber(String telephoneNumber) {
        if (!initialized) { init(); }

        return null;
    }

    @Override
    public void close() throws IOException {

    }
}
