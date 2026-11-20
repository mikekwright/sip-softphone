# SDP and the offer/answer model

Package: `com.github.mikekwright.sip.softphone.sdp` (RFC 8866, RFC 3264).

## Model

| Class | SDP lines |
|-------|-----------|
| `SessionDescription` | `v= o= s= i= u= e= p= c= b= t= r= z= k= a=` + media sections |
| `Origin` | `o=<user> <sess-id> <sess-version> IN IP4 <addr>` |
| `Connection` | `c=IN IP4 <addr>` (`isUnspecified()` for `0.0.0.0`) |
| `MediaDescription` | `m=<media> <port> <proto> <fmt...>` + its `c= b= a=` |
| `RtpMap` | `a=rtpmap`, plus the matching `a=fmtp` and `a=rtcp-fb` |
| `MediaDirection` | `a=sendrecv / sendonly / recvonly / inactive` |
| `StaticPayloadTypes` | RFC 3551 table, used when a static payload type has no `a=rtpmap` |

`SdpParser.parse` is lenient about line endings and unknown lines (they are preserved and written back),
strict about structurally broken `v= o= c= m=` lines (`SdpParseException` with the line number).
`SdpWriter.write` emits the RFC 8866 §5 line order with CRLF endings.

## Offer/answer (`SdpNegotiator`)

```
 local codec prefs ──► SdpMediaConfig ──► createOffer() ──► offer SDP ──► (INVITE / re-INVITE)
                                                                                  │
 answer SDP ◄─────────────────────────────────────────────────────────── 200 OK ◄┘
     │
     └──► processAnswer(offer, answer) ──► List<NegotiatedMedia> ──► MediaNegotiation.toSpec ──► RTP streams

 remote offer ──► createAnswer(offer, local configs) ──► NegotiationResult(answer SDP, List<NegotiatedMedia>)
```

Rules applied (RFC 3264 §6):

* The answer has exactly the offer's m-lines, in order. Unsupported or unwanted lines are rejected with port 0.
* Codecs match on encoding name, clock rate and channels; H.264 additionally on `packetization-mode`.
* Dynamic payload type numbers of the offerer are reused in the answer; `NegotiatedFormat` keeps both numbers:
  we **send** with the remote's number and **receive** on ours.
* telephone-event is negotiated separately (`NegotiatedMedia.telephoneEvent()`), matched by clock rate.
* Direction: answer = reverse(offer direction) ∩ local wish. `c=0.0.0.0` in an offer (RFC 2543 hold) is treated
  as "the offerer does not receive", so we never send to `0.0.0.0`.
* `a=rtcp-mux` is accepted if both sides want it; the RTCP address otherwise comes from `a=rtcp` or port + 1.
* If no m-line can be accepted, the softphone rejects the INVITE with **488 Not Acceptable Here**.

The negotiator is a class with protected hooks (`formatsMatch`, `supportsProtocol`, `commonRtcpFeedback`) so
that stricter or vendor specific matching can be added by subclassing.

## Hold (RFC 3264 §8.4)

| We want | Remote state | We offer | Remote answers | Our `NegotiatedMedia.direction()` |
|---------|--------------|----------|----------------|-----------------------------------|
| resume  | not holding  | sendrecv | sendrecv | SENDRECV |
| hold    | not holding  | sendonly | recvonly | SENDONLY |
| hold    | holding us   | inactive | inactive | INACTIVE |

`Call.isRemoteHold()` becomes true when the negotiated direction no longer lets us send.

## Default codec set offered by the softphone

```
m=audio <port> RTP/AVP 0 8 101
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-16
a=ptime:20
m=video <port> RTP/AVP 96 97                  (only for video calls)
a=rtpmap:96 VP8/90000
a=rtcp-fb:96 nack / nack pli / ccm fir
a=rtpmap:97 H264/90000
a=fmtp:97 profile-level-id=42e01f;packetization-mode=1
```
Configured with `SoftphoneConfig.audioCodecs`, `videoCodecs` and `telephoneEventPayloadType`.
