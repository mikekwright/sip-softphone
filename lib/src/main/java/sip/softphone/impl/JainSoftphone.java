package sip.softphone.impl;

import sip.softphone.Dialog;
import sip.softphone.SipCredentialProvider;
import sip.softphone.Softphone;

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
