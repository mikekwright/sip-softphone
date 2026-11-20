# RTP, RTCP and media

Packages: `rtp`, `media`, `media.audio`, `media.dtmf`, `media.video`.

```
          AudioSource ──► AudioStream ──encode──► RtpPacket ──► RtpSession (UDP RTP port)  ──► network
 (tone, WAV, mic, browser)   │  DtmfSender ─────────┘             │  RTCP port (+1) / rtcp-mux
                             │                                    │  SR/RR + SDES every ~5 s, BYE on close
          AudioSink   ◄──decode── JitterBuffer ◄── RtpPacket ◄────┘
 (speaker, WAV, recorder)    └─► DtmfReceiver ──► onDtmf(digit)

 EncodedVideoFrame ──► VideoStream ──► Vp8/H264 Packetizer ──► RtpSession
 VideoSink ◄── Vp8/H264 Depacketizer ◄── RtpSession      PLI / FIR ⇄ key frame requests
```

## RTP session (`rtp.RtpSession`)

* Binds an even RTP port and RTP+1 for RTCP from a `PortAllocator` range, both on one Netty event loop, so all
  per-stream state is single threaded.
* RFC 5761 demultiplexing: RTCP arriving on the RTP port is recognized by its packet type range.
* Symmetric RTP / latching (RFC 4961 style): when enabled (the softphone enables it by default), the remote
  address follows the actual source of incoming media, which helps behind NAT.
* RTCP (RFC 3550 §6): compound SR or RR + SDES CNAME at a randomized 0.5-1.5 x interval; BYE on close. Reception
  statistics follow appendix A.1 (sequence/cycles), A.3 (loss) and A.8 (interarrival jitter).
* Feedback (RFC 4585): `sendFeedback` wraps PLI / FIR / Generic NACK into a compliant compound packet.

## Audio (`media.AudioStream`)

* A fixed-rate media clock ticks every ptime (20 ms); each tick pulls 160 samples (8 kHz) from the
  `AudioSource`, encodes and sends one packet. Initial sequence number and timestamp are random (RFC 3550 §5.1);
  the first packet of a talkspurt carries the marker bit (RFC 3551 §4.1).
* The clock keeps running while not sending (hold) so timestamps stay continuous.
* Receive path: a small reordering `JitterBuffer` (3 packets), decode by payload type, then `AudioSink.write`.
* Codecs: G.711 μ-law/A-law (table driven, ITU-T G.711), L16; add more through `AudioCodecRegistry.register`.

### Built-in sources and sinks (great for automated tests)

| Class | Purpose |
|-------|---------|
| `ToneSource(freq, amplitude)`, `ToneSource.mixed(...)` | continuous sine tone(s) |
| `WavFileSource(path, loop)` | play a WAV (any rate/channels, resampled to the codec rate) |
| `SilenceSource` | default source |
| `RecordingSink` | in-memory recording with `awaitSamples`, `rms`, `lastSamples` |
| `WavFileSink(path)` | record to a WAV file |
| `NullSink` | default sink |
| `ToneDetector` | Goertzel power / dominant frequency, to assert what was heard |

## DTMF (`media.dtmf`, RFC 4733)

* `DtmfSender` produces the RFC 4733 §2.5 packet sequence per digit: first packet with marker, one update every
  ptime with growing duration, then three end packets with the final duration, all with the timestamp of the
  event start. Events replace audio packets while in flight and share the sequence number space.
* `DtmfReceiver` reports each event exactly once (keyed by RTP timestamp), tolerating redundant end packets.
* Without negotiated telephone-event, digits are sent in-band as dual tones (`DtmfToneSource`).
* Inbound SIP INFO `application/dtmf-relay` is handled at the call level and reported through the same callback.

## Video (`media.video`)

The library does not encode or decode video; it transports **encoded frames** (`EncodedVideoFrame`). This keeps
the jar pure Java and lets the application choose the codec implementation (WebCodecs in the browser client, a
native encoder, or canned frames in tests).

* VP8 (RFC 7741): minimal 1-byte payload descriptor (S bit on the first packet, PID 0) on send; the depacketizer
  accepts the extended descriptor (X, I/M PictureID, L, T/K) and detects key frames from the payload header.
* H.264 (RFC 6184, packetization-mode 1): Annex-B input is split into NAL units, sent as single NAL unit packets or
  FU-A fragments; the depacketizer handles single NAL, STAP-A and FU-A and outputs Annex-B.
* Loss handling: a sequence gap discards the partial frame; the depacketizer waits for the next key frame and the
  stream sends an RTCP PLI (rate limited to one per 500 ms). PLI or FIR from the remote side invokes
  `CallListener.onKeyFrameRequested`.
* Max RTP payload 1200 bytes, RTP clock 90 kHz derived from `timestampMicros`.
