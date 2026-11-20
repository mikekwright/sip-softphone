# Extending the library

The code is organized so that each protocol concern can be replaced or extended without touching the others.
This page lists the intended seams with a recipe for each.

## Add support for a new SIP method (e.g. REFER, SUBSCRIBE)

1. **Out of dialog** - register a `RequestHandler`:
   ```java
   stack.registerHandler(SipMethod.SUBSCRIBE, in -> {
       SipRequest req = in.request();
       in.respond(202, r -> r.setHeader("Expires", "3600"));
   });
   ```
   Add the method to `MessageFactory.setAllow(...)` so it is advertised.
2. **In dialog** - implement `DialogUsage` (see `InviteSession` for a complete example) and register it with
   `stack.registerDialog(dialog.id(), usage)`. The UA core validates the To tag and routes requests to it;
   `Dialog.acceptRemoteSequence` gives you the §12.2.2 CSeq check.
3. **Client side** - build the request with `MessageFactory` or `Dialog.createRequest`, then use
   `NonInviteRequestFlow.send` (digest retry, 408/503 synthesis) or `SipStack.sendRequest` with your own
   `ClientTransactionListener`.

## Add a header type

Headers are stored raw and parsed on demand. Write a small immutable value class with a static `parse(String)` and
`toString()` (see `Via`, `NameAddress`, `CSeq`), then use `message.headers().all("My-Header", MyHeader::parse)`.
If the header is a comma separated list, add its name to `HeaderNames.LIST_HEADERS`.

## Add a transport

Implement `SipTransport` (send/close/localAddress) on top of a Netty channel and feed decoded messages to the
`SipTransport.Listener`. `UdpSipTransport` and `StreamSipTransport` are the templates; SIP over WebSocket
(RFC 7118) would be a `StreamSipTransport`-like class with Netty's WebSocket codec and one message per frame.
Register it in `TransportLayer.start` and add a `TransportType`.

## Change DNS / routing

Provide a `SipResolver` (e.g. a static map for tests, or a NAPTR-aware resolver) through
`SoftphoneConfig.Builder.resolver(...)`.

## Add an audio codec

```java
AudioCodecRegistry registry = phone.audioCodecs();
registry.register("G722", 8000, MyG722Codec::new);   // implements AudioCodec
```
and include the name in `SoftphoneConfig.audioCodecs`. For dynamic payload types add the preferred number to
`MediaNegotiation`.

## Add a video payload format

Implement `VideoPacketizer` and a `VideoDepacketizer` (extending `AbstractVideoDepacketizer` gives you gap
detection and key frame waiting for free), register them in `VideoPayloadFormats`, and add the SDP `RtpMap` in
`MediaNegotiation.videoFormats`.

## Customize SDP negotiation

Subclass `SdpNegotiator` and override `formatsMatch`, `supportsProtocol` or `commonRtcpFeedback`.

## Observe or manipulate the wire

* `SipMessageInterceptor` - message level (after parsing / before encoding), on the SIP core thread.
* `PipelineCustomizer` - byte level Netty handlers on every SIP channel (`LoggingHandler`, packet loss).
* `RtpSessionListener` - raw RTP / RTCP packets of a session.

## Ideas that fit the design

PRACK/100rel as an `InviteSession` extension, session timers (RFC 4028) as a timer in `InviteSession`, REFER based
transfer as a new handler plus `Softphone.transfer`, SRTP as a `PipelineCustomizer`-style handler in the RTP
session pipeline, and a registrar/proxy test fixture built from `RequestHandler`s (see `RegistrationTest`).
