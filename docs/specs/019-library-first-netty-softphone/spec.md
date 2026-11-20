# 019 Library-first Netty softphone with web and terminal clients

- Status: proposed (implemented, awaiting approval)
- Spec number: 019
- Date: 2026-11-20
- Supersedes: the MCP-centred direction of 002, 006-010 and 012; folds in 001, 003-005, 011, 013, 014, 017, 018

## Context

The restart plan (specs 001-018) targeted an stdio MCP server plus a TUI. The project owner has since redefined
the goal: the main deliverable is a **reusable jar** that implements SIP and media for automated call flows and
tests, with two sample clients (a web softphone with video and a terminal softphone without video).

## Problem

The legacy code (JAIN SIP, Kotlin/Scala, Gradle 3.3) cannot be extended into that goal, and the MCP-oriented
specs no longer describe the product.

## Goals

- a Java 21 / Gradle 9 multi-project build with `softphone-lib`, `clients/web-softphone`,
  `clients/terminal-softphone`
- all packets built and parsed with Netty, implementing RFC 3261 (with RFC 6026, 3263, 3581, 5626 keep-alive),
  digest auth (RFC 2617/7616/8760), SDP (RFC 8866/3264), RTP/RTCP (RFC 3550/3551/4585/5104/5761), DTMF
  (RFC 4733), VP8 (RFC 7741) and H.264 (RFC 6184) payload formats
- protocol layers split into packages that can be read, tested and replaced independently
- an automation-friendly facade (`Softphone`, `Call`) with futures, listeners, interceptors and test media
- documentation that traces each rule to its RFC

## Non-goals

- MCP server, transcription, text-to-speech (possible future clients of the library)
- WebRTC (ICE/DTLS-SRTP) in the library; the browser client talks to the library through a WebSocket gateway
- native audio/video codecs beyond G.711/L16 (video is carried as encoded frames)

## Decision

Replace the legacy modules with the layered Netty implementation described in
`docs/architecture/overview.md`. Keep the numbered spec workflow; earlier draft specs remain for history.

## Consequences

- The library can be embedded by test suites and other clients; both sample clients consume only its public API.
- The web client needs a server process (it is a gateway, not a pure browser phone), which also lets it call
  ordinary SIP devices with plain RTP.
- Features listed as "not implemented" in `docs/references.md` (PRACK, session timers, REFER, SRTP, ICE...) are
  natural follow-up specs.
