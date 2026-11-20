# Implementation - 019

1. Build: Gradle 9.9 wrapper, Kotlin DSL, version catalog, Java 21 toolchain; remove `lib/`, `sample-client/`,
   Wercker; add GitHub Actions.  **Done**
2. `sip.*` message model, parser, Netty codecs, transports, transactions, dialogs, digest auth, UA core,
   registration and INVITE session flows, with unit and loopback tests.  **Done**
3. `sdp` model, parser/writer and offer/answer negotiator with tests.  **Done**
4. `rtp` and `media` (RTP/RTCP, jitter buffer, G.711, RFC 4733, VP8/H.264 packetizers, audio/video streams) with
   tests.  **Done**
5. `Softphone` / `Call` facade combining 2-4, end-to-end tests (audio, DTMF, hold, video, cancel, 488, MESSAGE).
   **Done**
6. Terminal client with scriptable batch mode.  **Done** (verified with an echo endpoint scenario)
7. Web client: Netty gateway + browser UI (AudioWorklet, WebCodecs), Playwright two-tab E2E script.  **Done**
8. Documentation: architecture, protocol notes with ladders, guides, client docs, references.  **Done**
