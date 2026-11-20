# Architecture overview

## What the project is

A SIP softphone delivered primarily as a **Java library** (`softphone-lib`) for building automated call flows,
SIP test harnesses and custom clients, plus two sample clients that put a user experience on top of it:

* `clients/web-softphone` — browser UI with audio **and video**, backed by a Netty gateway;
* `clients/terminal-softphone` — interactive / scriptable terminal phone, audio only.

All packet handling — SIP messages, SDP, RTP/RTCP, DTMF events, video payload formats — is implemented in the
library on top of **Netty**, following the RFCs listed in [../references.md](../references.md).

## Layers

```
 clients/web-softphone      clients/terminal-softphone      your test suite / application
          │                           │                                │
          └───────────────┬───────────┴────────────────────────────────┘
                          ▼
  Softphone, Call (root, call)        automation facade: futures, listeners, media I/O
  ─────────────────────────────────────────────────────────────────────────────────────
  sip.session  sip.flow               INVITE session, REGISTER, generic requests
  sip.core                            UA core (TU): routing, §8.2 checks, CANCEL, dialog dispatch
  sip.dialog                          dialog state (§12)
  sip.transaction                     4 transaction state machines (§17, RFC 6026)
  sip.transport  sip.codec            UDP / TCP / TLS on Netty, framing, rport, RFC 3263 resolver
  sip.message  sip.parser  sip.auth   message model, grammar, digest auth
  ─────────────────────────────────────────────────────────────────────────────────────
  sdp                                 SDP model, parser/writer, offer/answer (RFC 8866 / 3264)
  ─────────────────────────────────────────────────────────────────────────────────────
  media  media.audio  media.dtmf      audio/video streams, G.711, RFC 4733, sources & sinks,
  media.video                         VP8 / H.264 packetization
  rtp                                 RTP/RTCP packets, sessions, statistics (RFC 3550)
  ─────────────────────────────────────────────────────────────────────────────────────
  Netty (event loops, channels, ByteBuf)
```

Dependencies only point downwards. SIP knows nothing about SDP or RTP (session bodies are opaque strings), SDP
knows nothing about RTP, and the media layer consumes a transport neutral `MediaStreamSpec`; the `call` package is
the only place where the three meet (`MediaNegotiation`, `Call`).

## Runtime model

* **SIP core executor** — one thread owns all SIP protocol state (transactions, dialogs, sessions, timers), so no
  locking is needed in the protocol code. See [../protocol/sip-layers.md](../protocol/sip-layers.md).
* **Netty I/O event loops** — socket I/O and parsing for SIP; RTP sessions are pinned to one event loop each, which
  also runs their media clock, jitter buffer and RTCP timers.
* **Event thread** — one per softphone; all `SoftphoneListener` / `CallListener` callbacks run there in order.
* Public methods are thread-safe and asynchronous (`CompletableFuture`), with blocking helpers
  (`Call.awaitState`) for tests.

## Design principles

1. **Spec traceability** — every state machine and rule carries the RFC section in its Javadoc; the flows are
   documented as ladders in [../protocol/call-flows.md](../protocol/call-flows.md).
2. **Separable complexity** — each protocol concern is its own package with a small interface; each can be used
   on its own (e.g. only the SIP parser, only the RTP packetizers).
3. **Automation first** — interceptors, deterministic futures, test sources/sinks (tones, WAV, recorders, tone
   detection), shrinkable timers and ephemeral ports make it easy to write reliable call-flow tests.
4. **Extensible** — handler registries, dialog usages, codec / payload format registries, resolver and pipeline
   hooks; see [../guides/extending.md](../guides/extending.md).
5. **Pure Java** — no native code; video is transported as encoded frames so the application chooses the codec
   implementation.

## Clients

Both clients only use the public library API. The web client's server keeps a `Softphone` per WebSocket and
bridges PCM audio and encoded video frames to the browser; the terminal client maps shell commands one-to-one
onto API calls. See [../clients/](../clients/).
