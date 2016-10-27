package sip.softphone.example;

import static org.assertj.core.api.Assertions.*;

import sip.softphone.JainFactories;
import sip.softphone.exceptions.JainWrappedException;
import sip.softphone.impl.RequestFactory;
import gov.nist.javax.sip.address.SipUri;
import gov.nist.javax.sip.header.SIPHeaderList;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.stack.SIPClientTransaction;
import gov.nist.javax.sip.stack.SIPTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.sip.*;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import java.text.ParseException;

public class ExampleSoftphoneSimpleCall {

    private JainFactories factories;
    private ExampleSipStackDetails sipStackDetails;
    private RequestFactory requestFactory;

    @BeforeEach
    public void setup() throws JainWrappedException {
        sipStackDetails = new ExampleSipStackDetails();
        factories = new JainFactories(sipStackDetails);
        requestFactory = new RequestFactory(factories.messageFactory(), factories.headerFactory(), factories.addressFactory());
    }

    @Test
//    @Disabled
    public void manuallyTestASingleCall() throws JainWrappedException, ParseException, SipException, InvalidArgumentException {
        SipStack stack = factories.createSipStack();
        SipProvider provider = factories.createSipProviderWithStackAndDetails(stack, sipStackDetails);

//        Request request = requestFactory.createInitialInviteRequest("18017351900");
//        Transaction transaction = provider.getNewClientTransaction(request);
//        Dialog d = provider.getNewDialog(transaction);
//        d.createAck(1);

        //assertThat(1+1).isEqualTo(2);
    }
}
