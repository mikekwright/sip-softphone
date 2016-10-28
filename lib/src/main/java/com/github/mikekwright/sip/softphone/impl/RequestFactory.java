package com.github.mikekwright.sip.softphone.impl;

import com.github.mikekwright.sip.softphone.SipContact;
import com.github.mikekwright.sip.softphone.SipContact;
import gov.nist.javax.sdp.fields.SDPField;
import gov.nist.javax.sdp.parser.SDPParser;

import javax.inject.Inject;
import javax.sdp.SdpEncoder;
import javax.sip.InvalidArgumentException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import java.text.ParseException;
import java.util.Collections;

public class RequestFactory {
    private final MessageFactory messageFactory;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;

    @Inject
    public RequestFactory(final MessageFactory messageFactory,
                          final HeaderFactory headerFactory,
                          final AddressFactory addressFactory) {
        this.messageFactory = messageFactory;
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
    }

    public Request createInitialInviteRequest(final SipContact to, final SipContact from,
                                              final String callId, final ViaDetails via) {
        try {
            SipURI requestUri = addressFactory.createSipURI(to.getContact(), to.getSipAddress());
            FromHeader fromHeader = createFromHeader(from);
            ToHeader toHeader = createToHeader(to);
            CallIdHeader callIdHeader = headerFactory.createCallIdHeader(callId);
            CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.INVITE);
            MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");

            ViaHeader viaHeader = headerFactory.createViaHeader(via.getIpAddress(), via.getPort(), via.getProtocol(), via.getBranch());
            Request request = messageFactory.createRequest(requestUri, Request.INVITE, callIdHeader, cSeqHeader,
                    fromHeader, toHeader, Collections.singletonList(viaHeader), maxForwardsHeader);

            request.addHeader(createContactHeader(from));

            return request;
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }

        return null;
    }

    private ContactHeader createContactHeader(SipContact contact) throws ParseException {
        return headerFactory.createContactHeader(createContactAddress(contact));
    }

    private FromHeader createFromHeader(SipContact contact) throws ParseException {
        return headerFactory.createFromHeader(createContactAddress(contact), contact.getTag());
    }

    private ToHeader createToHeader(SipContact contact) throws ParseException {
        return headerFactory.createToHeader(createContactAddress(contact), contact.getTag());
    }

    private Address createContactAddress(SipContact contact) throws ParseException {
        SipURI sipUri = addressFactory.createSipURI(contact.getContact(), contact.getSipAddress());
        Address sipAddress = addressFactory.createAddress(sipUri);
        sipAddress.setDisplayName(contact.getDisplayName());
        return sipAddress;
    }
}
