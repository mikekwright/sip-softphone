# Using `softphone-lib`

`softphone-lib` is a pure Java (21+) library: SIP signalling, SDP negotiation and RTP media on Netty, with an
API built for automated call flows and test harnesses.

## Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.mikekwright:softphone-lib:1.0.0-SNAPSHOT")
}
```

Until it is published, install it into your local Maven repository with
`./gradlew :softphone-lib:publishToMavenLocal` and add `mavenLocal()` to your repositories. The library only logs
through SLF4J; add a binding such as Logback to see its output.

## Five minute tour

```java
SoftphoneConfig config = SoftphoneConfig.builder()
        .user("alice").domain("pbx.example.com").displayName("Alice")
        .password("secret")                       // used for any 401/407 challenge
        .registrar("sip:pbx.example.com")         // enables register()
        .transport(TransportType.UDP).sipPort(0)  // 0 = ephemeral port
        .audioCodecs(List.of("PCMU", "PCMA"))
        .videoCodecs(List.of("VP8"))              // empty list = audio only
        .build();

try (Softphone phone = Softphone.create(config).start().join()) {
    phone.register().get(10, TimeUnit.SECONDS);

    Call call = phone.call("sip:bob@pbx.example.com", CallOptions.audioOnly());
    call.setAudioSource(new WavFileSource(Path.of("prompt.wav"), true));
    call.setAudioSink(new WavFileSink(Path.of("recording.wav")));
    call.connected().get(30, TimeUnit.SECONDS);

    call.sendDtmf("1234#").join();
    call.hold().join();
    call.resume().join();
    call.hangup();
    TerminationReason why = call.ended().join();
}
```

A complete, tested version of an automated IVR test lives in
[`AutomatedCallExampleTest`](../../softphone-lib/src/test/java/com/github/mikekwright/sip/softphone/examples/AutomatedCallExampleTest.java).

## Receiving calls

```java
phone.addListener(new SoftphoneListener() {
    @Override public void onIncomingCall(Call call) {           // 180 Ringing already sent
        call.setAudioSource(new ToneSource(440, 0.3));
        if (call.remoteOffersVideo()) {
            call.answer(CallOptions.audioVideo());
        } else {
            call.answer();                                       // or call.reject(486)
        }
    }
    @Override public void onMessage(NameAddress from, String type, String body) { ... }   // SIP MESSAGE
    @Override public void onRegistrationStateChanged(RegistrationState s, int status) { ... }
});
```

Listeners run on a dedicated event thread, in order, so it is safe to call blocking API methods from them for
short periods; SIP processing continues on its own thread.

## Waiting for things (automation helpers)

| API | Use |
|-----|-----|
| `call.connected()`, `call.ended()` | futures for the two most common milestones |
| `call.stateFuture(CallState.RINGING)` / `call.awaitState(state, timeout)` | any state; fail fast if the call ends first (`CallEndedException`) |
| `RecordingSink.awaitSamples(n, timeout)` + `ToneDetector` | assert *what* was heard |
| `CallListener.onDtmf` | collect digits (RFC 4733 and SIP INFO) |
| `phone.addInterceptor(...)` | build SIP ladders, assert headers, drop or rewrite messages |
| `call.audioStream().get().packetsReceived()/packetsLost()` | media quality checks |

## Video

The library transports encoded frames; bring your own encoder/decoder (the web client uses WebCodecs):

```java
Call call = phone.call(target, CallOptions.audioVideo());
call.setVideoSink(frame -> decoder.decode(frame.data(), frame.keyFrame()));
call.addListener(new CallListener() {
    @Override public void onKeyFrameRequested(Call c) { encoder.forceKeyFrame(); }
});
encoder.onFrame((bytes, key, micros) ->
        call.sendVideoFrame(new EncodedVideoFrame(bytes, key, micros, call.videoFormat().get().encodingName())));
```

VP8 frames are raw VP8 bitstream frames; H.264 frames are Annex-B byte streams.

## Going lower level

`phone.stack()` exposes the `SipStack` underneath; every layer is usable on its own:

```java
SipStack stack = phone.stack();
// a custom request with full transaction semantics and digest retry
SipRequest req = stack.messages().createRequest(SipMethod.of("PUBLISH"), uri, phone.aor(), new NameAddress(uri), null, 1);
req.setHeader("Event", "presence").setBody("application/pidf+xml", pidf);
SipResponse r = NonInviteRequestFlow.send(stack, req).get();

// serve a new method
stack.registerHandler(SipMethod.SUBSCRIBE, in -> in.respond(489 /* Bad Event */));

// fault injection: drop every second ACK to exercise 2xx retransmission
stack.addInterceptor(new SipMessageInterceptor() {
    int acks;
    @Override public SipMessage onSend(SipMessage m, TransportAddress to) {
        return m.startLine().startsWith("ACK") && acks++ % 2 == 0 ? null : m;
    }
});
```

The SIP layers are described in [protocol/sip-layers.md](../protocol/sip-layers.md); extension recipes are in
[extending.md](extending.md).

## Configuration reference (`SoftphoneConfig.Builder`)

| Setting | Default | Notes |
|---------|---------|-------|
| `user`, `domain`, `displayName` | `softphone`, `localhost`, none | address-of-record `sip:user@domain` |
| `password`, `authUser` | none, `user` | digest credentials for 401/407 |
| `registrar`, `registerExpires` | none, 3600 | `register()` requires a registrar |
| `outboundProxy` | none | all out-of-dialog requests go there |
| `transport` | UDP | UDP, TCP or TLS (`tls(serverCtx, clientCtx)`) |
| `bindHost`, `sipPort` | `0.0.0.0`, 5060 | port 0 = ephemeral |
| `advertisedHost`, `mediaHost` | detected | address in Via/Contact and SDP (set for NAT / multi-homed hosts) |
| `rtpPorts(min, max)` | 20000-29998 | even ports, RTCP = RTP + 1 |
| `audioCodecs` | PCMU, PCMA | preference order |
| `videoCodecs` | VP8, H264 | empty list disables video |
| `telephoneEventPayloadType` | 101 | -1 disables RFC 4733 (in-band tones are used instead) |
| `timers` | T1=500ms, T2=4s, T4=5s | shrink in tests to speed up timeout paths |
| `resolver` | `DefaultSipResolver` | RFC 3263 (SRV + A) |
| `pipelineCustomizer` | none | add Netty handlers to SIP channels |
| `symmetricRtp` | true | latch RTP to the observed source address |
| `stunServer` | none | `host[:port]`; discover the public address behind NAT (see [NAT traversal](../protocol/nat-traversal.md)) |
| `keepAliveInterval` | 25 s | SIP CRLF and RTP keep-alives that hold NAT bindings open; `Duration.ZERO` disables |
