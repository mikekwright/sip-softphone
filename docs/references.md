# Specification references

Every protocol rule implemented by `softphone-lib` is traceable to one of these documents. Source code
Javadoc cites the section (for example `RFC 3261 §17.1.1`) next to the code that implements it.

## SIP core

| Spec | Title | Where it is implemented |
|------|-------|-------------------------|
| [RFC 3261](https://www.rfc-editor.org/rfc/rfc3261) | SIP: Session Initiation Protocol | whole `sip` package: message grammar (§7, §25) in `sip.message`/`sip.parser`, transport (§18) in `sip.transport`, transactions (§17) in `sip.transaction`, dialogs (§12) in `sip.dialog`, UA core (§8) in `sip.core`, registration (§10) in `sip.flow.Registration`, INVITE/CANCEL/BYE/re-INVITE (§9, §13-§15) in `sip.session.InviteSession`, digest auth (§22) in `sip.auth` |
| [RFC 6026](https://www.rfc-editor.org/rfc/rfc6026) | Correct Transaction Handling for 2xx Responses to INVITE | `Accepted` state, Timers L and M in `InviteClientTransaction` / `InviteServerTransaction` |
| [RFC 3263](https://www.rfc-editor.org/rfc/rfc3263) | Locating SIP Servers | `DefaultSipResolver` (transport selection, SRV, A/AAAA; no NAPTR) |
| [RFC 3581](https://www.rfc-editor.org/rfc/rfc3581) | Symmetric Response Routing (`rport`) | `TransportLayer.stampReceived` / `responseDestination`, `rport` in every Via we send |
| [RFC 5626](https://www.rfc-editor.org/rfc/rfc5626) §3.5.1, §4.4 | CRLF keep-alive | `SipStreamDecoder` answers double-CRLF pings; `SipStack.keepAlive` sends them to registrars and call peers |
| [RFC 3428](https://www.rfc-editor.org/rfc/rfc3428) | SIP MESSAGE | `Softphone.sendMessage`, in-dialog MESSAGE in `InviteSession` |
| [RFC 6086](https://www.rfc-editor.org/rfc/rfc6086) | SIP INFO | in-dialog INFO, `application/dtmf-relay` DTMF fallback |
| [RFC 5057](https://www.rfc-editor.org/rfc/rfc5057) | Multiple dialog usages | 481/408 to a re-INVITE terminate the dialog |
| [RFC 4475](https://www.rfc-editor.org/rfc/rfc4475) | SIP torture tests | style of the parser tests (folding, compact forms, odd whitespace) |

## Authentication

| Spec | Title | Where |
|------|-------|-------|
| [RFC 2617](https://www.rfc-editor.org/rfc/rfc2617) | HTTP Digest authentication (MD5, qop) | `DigestAuthenticator`, `DigestServer` |
| [RFC 7616](https://www.rfc-editor.org/rfc/rfc7616) | HTTP Digest (SHA-256, SHA-512/256) | `DigestAlgorithm` |
| [RFC 8760](https://www.rfc-editor.org/rfc/rfc8760) | SIP digest algorithm upgrade | strongest supported challenge preferred per realm |

## Session description

| Spec | Title | Where |
|------|-------|-------|
| [RFC 8866](https://www.rfc-editor.org/rfc/rfc8866) | SDP (obsoletes RFC 4566) | `sdp` model, `SdpParser`, `SdpWriter` |
| [RFC 3264](https://www.rfc-editor.org/rfc/rfc3264) | Offer/answer model | `SdpNegotiator`, hold (§8.4) in `Call.hold()` |
| [RFC 4317](https://www.rfc-editor.org/rfc/rfc4317) | Offer/answer examples | negotiator tests |
| [RFC 3605](https://www.rfc-editor.org/rfc/rfc3605) | `a=rtcp` attribute | `MediaDescription.rtcpPort()` |
| [RFC 5761](https://www.rfc-editor.org/rfc/rfc5761) | RTP/RTCP multiplexing | `a=rtcp-mux`, `RtpPacket.looksLikeRtcp` |

## Media

| Spec | Title | Where |
|------|-------|-------|
| [RFC 3550](https://www.rfc-editor.org/rfc/rfc3550) | RTP / RTCP | `rtp.RtpPacket`, RTCP packets, `ReceptionStatistics` (A.1, A.3, A.8), `RtpSession` |
| [RFC 3551](https://www.rfc-editor.org/rfc/rfc3551) | RTP A/V profile, static payload types | `StaticPayloadTypes` |
| [ITU-T G.711](https://www.itu.int/rec/T-REC-G.711) | PCMU / PCMA | `PcmuCodec`, `PcmaCodec` |
| [RFC 4733](https://www.rfc-editor.org/rfc/rfc4733) | telephone-event (DTMF) | `media.dtmf` |
| [RFC 4585](https://www.rfc-editor.org/rfc/rfc4585) | RTCP feedback (NACK, PLI) | `PictureLossIndication`, `GenericNack`, `a=rtcp-fb` |
| [RFC 5104](https://www.rfc-editor.org/rfc/rfc5104) | Codec control messages (FIR) | `FullIntraRequest` |
| [RFC 7741](https://www.rfc-editor.org/rfc/rfc7741) | RTP payload for VP8 | `Vp8Packetizer`, `Vp8Depacketizer` |
| [RFC 6184](https://www.rfc-editor.org/rfc/rfc6184) | RTP payload for H.264 | `H264Packetizer`, `H264Depacketizer` (packetization-mode 1: single NAL, STAP-A, FU-A) |
| [RFC 4961](https://www.rfc-editor.org/rfc/rfc4961) | Symmetric RTP | `RtpSession` symmetric latching |

## NAT traversal

| Spec | Title | Where |
|------|-------|-------|
| [RFC 8489](https://www.rfc-editor.org/rfc/rfc8489) | STUN (Binding, XOR-MAPPED-ADDRESS, FINGERPRINT) | `nat.StunMessage`, `StunClient`, `StunServer` |
| [RFC 7983](https://www.rfc-editor.org/rfc/rfc7983) | Multiplexing STUN with RTP/SIP on one port | `nat.StunHandler` |
| [RFC 6263](https://www.rfc-editor.org/rfc/rfc6263) | RTP keep-alives | `RtpSession.enableKeepAlive` |
| [RFC 3605](https://www.rfc-editor.org/rfc/rfc3605) | `a=rtcp` for NAT mapped RTCP ports | `Call.withNatRtcp` |

## Libraries and platform

* [Netty 4.2](https://netty.io/wiki/) - all sockets, codecs, timers and event loops.
* [WebCodecs](https://www.w3.org/TR/webcodecs/) and [AudioWorklet](https://www.w3.org/TR/webaudio/#AudioWorklet) - media I/O in the web client.
* [JLine 3](https://github.com/jline/jline3) - line editing in the terminal client.

## Not implemented (extension candidates)

PRACK / reliable provisional responses (RFC 3262), UPDATE (RFC 3311), session timers (RFC 4028), REFER /
transfer (RFC 3515), SUBSCRIBE/NOTIFY (RFC 6665), SIP over WebSocket (RFC 7118), NAPTR lookups, SRTP / DTLS-SRTP
(RFC 3711 / RFC 5764), ICE (RFC 8445) / TURN (RFC 8656), Opus and other audio codecs. See
[guides/extending.md](guides/extending.md) for how the library is designed to accommodate them.
