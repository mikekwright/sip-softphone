Java Sip Softphone
==============================================================

[![codecov](https://codecov.io/gh/mikekwright/sip-softphone/branch/master/graph/badge.svg)](https://codecov.io/gh/mikekwright/sip-softphone)
[![wercker status](https://app.wercker.com/status/d711a67ff47f19173d4fce1052e1d6ad/s/master "wercker status")](https://app.wercker.com/project/byKey/d711a67ff47f19173d4fce1052e1d6ad)

## Description

Library that can be used as a sip softphone that will support basic operations
such as

1. Call to client
2. Incoming call from other client
3. DTMF tones
4. IVR

As well as more advanced features such as:  

1. Call Transferring
2. Call Hold
3. Call Wait

## Usage

This library is stored in maven central and can be referenced using the below maven
entry:  

        <dependency>
          <groupId>sip</groupId>
          <artifactId>softphone</artifactId>
          <version>${softphone.version}</artifactId>
        </dependency>


## References

* [Jain Sip PDF Slides](http://www.oracle.com/technetwork/java/jain-sip-tutorial-149998.pdf)    
* [jain-sip SDP Test](https://github.com/rkday/jain-sip/blob/master/branches/3263-support/src/test/gov/nist/javax/sdp/parser/SdpParserTest.java)    
* [jain-sip Simple Call Setup](https://github.com/usnistgov/jsip/blob/master/src/examples/android/simplecallsetup/Shootist.java)    
* [Sip Registration using Jain](http://vkslabs.com/sip-register-request-using-jain-sip/)     
* [Jain sip for Android Example](https://github.com/RestComm/restcomm-android-sdk/tree/b1f1e7309b1f1a1d7e9aa8d5aad7b842d8691371/Examples/JAIN%20SIP)  
