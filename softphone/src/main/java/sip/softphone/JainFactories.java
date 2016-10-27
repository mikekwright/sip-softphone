package sip.softphone;

import sip.softphone.exceptions.JainWrappedException;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import java.util.Properties;

@Singleton
public class JainFactories {
    private final Object factoryLock;
    private final SipStackDetails stackDetails;
    private SipFactory createdFactory;

    @Inject
    public JainFactories(final SipStackDetails stackDetails) {
        this.stackDetails = stackDetails;
        factoryLock = new Object();
    }

    public SipFactory getSipFactory() {
        synchronized (factoryLock) {
            if (createdFactory == null) {
                createdFactory = createSipFactory();
            }
        }
        return createdFactory;
    }

    private SipFactory createSipFactory() {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        return factory;
    }

    public HeaderFactory headerFactory() throws JainWrappedException {
        try {
            return getSipFactory().createHeaderFactory();
        } catch (PeerUnavailableException e) {
            throw new JainWrappedException("headerFactory failed with PeerUnavailable exception", e);
        }
    }

    public MessageFactory messageFactory() throws JainWrappedException {
        try {
            return getSipFactory().createMessageFactory();
        } catch (PeerUnavailableException e) {
            throw new JainWrappedException("messageFactory failed with PeerUnavailable exception", e);
        }
    }

    public AddressFactory addressFactory() throws JainWrappedException {
        try {
            return getSipFactory().createAddressFactory();
        } catch (PeerUnavailableException e) {
            throw new JainWrappedException("addressFactory failed with PeerUnavailable exception", e);
        }
    }

    public SipStack createSipStack() throws JainWrappedException {
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", stackDetails.stackName());
        properties.setProperty("javax.sip.IP_ADDRESS", stackDetails.ipAddress());

        try {
            return getSipFactory().createSipStack(properties);
        } catch (PeerUnavailableException e) {
            throw new JainWrappedException("createSipStack failed with PeerUnavailable exception", e);
        }
    }

    public SipProvider createSipProvider() throws JainWrappedException {
        return createSipProviderWithStackAndDetails(createSipStack(), stackDetails);
    }

    public SipProvider createSipProviderWithStackDetails(final SipStackDetails stackDetails) throws JainWrappedException {
        return createSipProviderWithStackAndDetails(createSipStack(), stackDetails);
    }

    public SipProvider createSipProviderWithStackAndDetails(final SipStack sipStack, final SipStackDetails stackDetails) throws JainWrappedException {
        ListeningPoint listeningPoint = null;
        try {
            listeningPoint = sipStack.createListeningPoint(stackDetails.ipAddress(), stackDetails.port(), stackDetails.protocol());
        } catch (TransportNotSupportedException e) {
            throw new JainWrappedException("createListeningPoint had a TransportNotSupported exception", e);
        } catch (InvalidArgumentException e) {
            throw new JainWrappedException("createListeningPoint had an InvalidArgument exception", e);
        }

        try {
            return sipStack.createSipProvider(listeningPoint);
        } catch (ObjectInUseException e) {
            throw new JainWrappedException("createSipProvider failed with ObjectInUse Exception", e);
        }
    }
}
