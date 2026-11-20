# Project TODO

## Done (spec 019)

- [x] Modern build: Gradle 9 wrapper, Java 21 toolchain, version catalog, CI workflow
- [x] SIP stack on Netty: parser/model, UDP/TCP/TLS transports, transactions (RFC 3261 §17 + RFC 6026), dialogs,
      digest auth, UA core, REGISTER, INVITE/CANCEL/BYE/re-INVITE, INFO, MESSAGE, OPTIONS
- [x] SDP model and RFC 3264 offer/answer
- [x] RTP/RTCP sessions, G.711, RFC 4733 DTMF, VP8 and H.264 payload formats, audio and video streams
- [x] `Softphone` / `Call` automation API with end-to-end tests
- [x] Terminal softphone with scriptable batch mode
- [x] Web softphone (audio + video) with browser E2E script
- [x] Architecture, protocol, guide and client documentation

## Next

- [ ] Approve spec 019 (`docs/specs/019-library-first-netty-softphone/approval.md`)
- [ ] Publish `softphone-lib` to Maven Central (signing keys + Sonatype credentials in CI)
- [ ] Run the web E2E script in CI (Playwright container job)
- [ ] Interop runs against Asterisk / FreeSWITCH / Kamailio (registration, auth, re-INVITE, DTMF)
- [ ] PRACK / 100rel (RFC 3262) and UPDATE (RFC 3311)
- [ ] Session timers (RFC 4028)
- [ ] REFER / blind and attended transfer (RFC 3515, RFC 3891)
- [ ] SRTP with SDES or DTLS-SRTP; ICE for NAT traversal
- [ ] Opus / G.722 audio codecs via `AudioCodecRegistry`
- [ ] NAPTR support in the resolver; SIP over WebSocket transport (RFC 7118)
- [ ] Optional MCP or other automation front ends on top of the library (earlier specs 008-010)
