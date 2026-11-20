# Project structure and namespaces

```
sip-softphone/
├── softphone-lib/                  the published jar (com.github.mikekwright:softphone-lib)
│   └── src/main/java/com/github/mikekwright/sip/softphone/
│       ├── Softphone, SoftphoneConfig, SoftphoneListener      facade
│       ├── call/          Call, CallState, CallListener, CallOptions, MediaNegotiation
│       ├── sip/
│       │   ├── message/   SipMessage/Request/Response, SipUri, NameAddress, Via, CSeq, headers
│       │   ├── parser/    SipMessageParser
│       │   ├── codec/     Netty datagram / stream codecs
│       │   ├── transport/ TransportLayer, UDP + TCP/TLS transports, resolver, interceptors
│       │   ├── transaction/ TransactionLayer + 4 state machines, TimerSettings
│       │   ├── dialog/    Dialog, DialogId
│       │   ├── auth/      digest client and server
│       │   ├── core/      SipStack (UA core), MessageFactory, handler / dialog-usage SPI
│       │   ├── flow/      NonInviteRequestFlow, Registration
│       │   └── session/   InviteSession
│       ├── nat/           STUN client/server, RFC 7983 demultiplexing
│       ├── sdp/           model, parser, writer, SdpNegotiator
│       ├── rtp/           RtpPacket, RTCP packets, RtpSession, statistics, PortAllocator
│       └── media/         AudioStream, VideoStream, JitterBuffer, MediaStreamSpec
│           ├── audio/     codecs, sources, sinks, tone detection
│           ├── dtmf/      RFC 4733 sender/receiver, in-band tones
│           └── video/     EncodedVideoFrame, VP8 / H.264 packetizers
├── clients/
│   ├── terminal-softphone/        ...softphone.terminal (JLine shell, batch mode)
│   └── web-softphone/             ...softphone.web (Netty HTTP/WebSocket) + src/main/resources/web (UI)
├── docs/                          architecture, protocol notes, guides, client docs, specs
└── gradle/libs.versions.toml      dependency versions
```

## Dependency rules

* `softphone-lib` depends only on Netty and SLF4J.
* Inside the library: `sip.*` must not import `sdp`, `rtp` or `media`; `sdp` imports nothing else from the
  library; `media`/`rtp` must not import `sip` or `sdp`; only the root package and `call` combine them.
* Clients depend on the library's public API only (no internal packages are required).
* Test fixtures live in `src/test` of the module they test.

## Naming

* Packages stay under `com.github.mikekwright.sip.softphone`.
* Classes that implement a specific RFC construct use the RFC's vocabulary (`InviteClientTransaction`,
  `Dialog`, `TelephoneEvent`, `PictureLossIndication`).
