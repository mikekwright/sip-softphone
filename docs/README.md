# Docs

| Folder / file | Contents |
|---------------|----------|
| [architecture/overview.md](architecture/overview.md) | what the project is, layers, runtime model, design principles |
| [architecture/project-structure-and-namespaces.md](architecture/project-structure-and-namespaces.md) | modules, packages, dependency rules |
| [architecture/tooling-and-build.md](architecture/tooling-and-build.md) | Java/Gradle/Netty baseline, commands, publishing |
| [protocol/sip-layers.md](protocol/sip-layers.md) | SIP stack layering, threading, request/response paths, extension points |
| [protocol/transactions.md](protocol/transactions.md) | RFC 3261 §17 state machines and timers |
| [protocol/call-flows.md](protocol/call-flows.md) | ladders for REGISTER, INVITE, CANCEL, BYE, re-INVITE/hold, DTMF, MESSAGE |
| [protocol/sdp-offer-answer.md](protocol/sdp-offer-answer.md) | SDP model and RFC 3264 negotiation rules |
| [protocol/rtp-media.md](protocol/rtp-media.md) | RTP/RTCP sessions, audio, DTMF, video packetization |
| [guides/library-usage.md](guides/library-usage.md) | using the jar: calls, automation helpers, configuration |
| [guides/extending.md](guides/extending.md) | adding methods, headers, transports, codecs, payload formats |
| [clients/terminal-softphone.md](clients/terminal-softphone.md) | terminal client usage and batch automation |
| [clients/web-softphone.md](clients/web-softphone.md) | web client (audio + video) |
| [clients/web-softphone-protocol.md](clients/web-softphone-protocol.md) | browser ⇄ server WebSocket protocol |
| [references.md](references.md) | every RFC / standard used and where it is implemented |
| [specs/](specs/) | numbered decision records (019 records the library-first restart) |
| [TODO.md](TODO.md) | status and next steps |
