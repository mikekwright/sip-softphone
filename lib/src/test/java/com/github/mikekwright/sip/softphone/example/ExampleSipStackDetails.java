package com.github.mikekwright.sip.softphone.example;

import com.github.mikekwright.sip.softphone.SipStackDetails;
import com.github.mikekwright.sip.softphone.SipStackDetails;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class ExampleSipStackDetails implements SipStackDetails {
    @Override
    public String stackName() {
        return "ExampleStack";
    }

    @Override
    public String ipAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Override
    public Integer port() {
        return 5605;
    }
}
