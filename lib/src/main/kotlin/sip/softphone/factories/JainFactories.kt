package sip.softphone.factories

import sip.softphone.SipStackDetails
import sip.softphone.exceptions.JainWrappedException
import java.util.*
import javax.inject.Inject
import javax.sip.*
import javax.sip.address.AddressFactory
import javax.sip.header.HeaderFactory
import javax.sip.message.MessageFactory

open class JainFactories @Inject constructor(val sipStackDetails: SipStackDetails) {
    val sipFactory: SipFactory by lazy {
        createSipFactory()
    }

    private fun createSipFactory(): SipFactory {
        val factory = SipFactory.getInstance()
        factory.pathName = "gov.nist"
        return factory
    }

    open fun headerFactory(): HeaderFactory {
        try {
            return sipFactory.createHeaderFactory()
        } catch (e: PeerUnavailableException) {
            throw JainWrappedException("headerFactory failed with PeerUnavailable exception", e)
        }
    }

    open fun messageFactory(): MessageFactory {
        try {
            return sipFactory.createMessageFactory()
        } catch (e: PeerUnavailableException) {
            throw JainWrappedException("messageFactory failed with PeerUnavailable exception", e)
        }
    }

    open fun addressFactory(): AddressFactory {
        try {
            return sipFactory.createAddressFactory()
        } catch (e: PeerUnavailableException) {
            throw JainWrappedException("addressFactory failed with PeerUnavailable exception", e)
        }
    }

    open fun createSipStack(): SipStack {
        val properties = Properties()
        properties.setProperty("javax.sip.STACK_NAME", sipStackDetails.stackName())
        properties.setProperty("javax.sip.IP_ADDRESS", sipStackDetails.ipAddress())

        try {
            return sipFactory.createSipStack(properties)
        } catch (e: PeerUnavailableException) {
            throw JainWrappedException("createSipStack failed with PeerUnavailable exception", e)
        }
    }

    open fun createSipProvider(): SipProvider {
        return createSipProviderWithStackAndDetails(createSipStack(), sipStackDetails)
    }

    open fun createSipProviderWithStackDetails(stackDetails: SipStackDetails): SipProvider {
        return createSipProviderWithStackAndDetails(createSipStack(), stackDetails)
    }

    open fun createSipProviderWithStackAndDetails(sipStack: SipStack, stackDetails: SipStackDetails): SipProvider {
        val listeningPoint: ListeningPoint = try {
            sipStack.createListeningPoint(stackDetails.ipAddress(), stackDetails.port(), stackDetails.protocol())
        } catch (e: TransportNotSupportedException) {
            throw JainWrappedException("createListeningPoint had a TransportNotSupported exception", e)
        } catch (e: InvalidArgumentException) {
            throw JainWrappedException("createListeningPoint had an InvalidArgument exception", e)
        }

        try {
            return sipStack.createSipProvider(listeningPoint)
        } catch (e: ObjectInUseException) {
            throw JainWrappedException("createSipProvider failed with ObjectInUse Exception", e)
        }
    }
}