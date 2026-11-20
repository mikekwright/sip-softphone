# Web softphone browser protocol

The web softphone is a Java process (built on `softphone-lib`) that acts as the SIP user agent, plus a
browser UI that provides the screen, microphone, camera and speaker. They talk over one WebSocket at
`ws(s)://<host>:<port>/ws`. Every WebSocket connection gets its **own** `Softphone` instance, so two browser
tabs are two independent phones that can call each other.

```
 Browser (UI, WebCodecs, AudioWorklet)  <-- WebSocket /ws -->  web-softphone (Java)  <-- SIP/RTP -->  peer
```

* **Text frames** carry JSON control messages (`type` field selects the message).
* **Binary frames** carry media. The first byte is the kind.

## Binary media frames

All multi-byte header fields are big-endian. PCM samples are signed 16-bit **little-endian** (the native
layout of `Int16Array` in browsers).

| Kind | Direction | Layout |
|------|-----------|--------|
| `0x01` audio | both | `[0x01][PCM16LE mono samples ...]` at `audioRate` (see `media` message), normally 20 ms chunks |
| `0x02` video | both | `[0x02][flags:1][timestampMicros:8][encoded frame ...]`; flags bit 0 = key frame |

Video payloads are encoded frames of the negotiated codec: `VP8` raw frames, or `H264` Annex-B byte stream
(start codes). The browser encodes/decodes them with WebCodecs.

## Browser -> server (JSON)

| type | fields | meaning |
|------|--------|---------|
| `configure` | `account: {displayName, user, domain, password, authUser, registrar, outboundProxy, transport ("udp"/"tcp"), sipPort, register}` | (re)create the phone; `registrar`, `outboundProxy`, `password` optional; `sipPort` 0 = ephemeral; `register` true sends REGISTER |
| `register` / `unregister` | | registration control |
| `call` | `target` (SIP URI or `user@host:port`), `video` (bool) | place a call |
| `answer` | `video` (bool) | answer the ringing incoming call |
| `reject` | | reject the ringing call (603) |
| `hangup` | | cancel / reject / bye depending on state |
| `hold` / `resume` | | re-INVITE with `a=sendonly` / `a=sendrecv` |
| `dtmf` | `digits` | send RFC 4733 DTMF |
| `message` | `target`, `text` | SIP MESSAGE (RFC 3428) |
| `keyframe` | | ask the remote party for a key frame (RTCP PLI) |

## Server -> browser (JSON)

| type | fields | meaning |
|------|--------|---------|
| `ready` | `localUri`, `sipPort`, `transport` | phone created and listening |
| `registration` | `state` (`REGISTERING`, `REGISTERED`, `UNREGISTERED`, `FAILED`), `status` | registration changes |
| `incoming` | `callId`, `from`, `video` (bool: offer contains video) | incoming call ringing |
| `callState` | `callId`, `state` (`CALLING`, `RINGING`, `EARLY_MEDIA`, `CONNECTED`, `HOLD`, `REMOTE_HOLD`, `ENDED`), `remote`, `reason` (on ENDED), `direction` (`outgoing`/`incoming`) | call progress |
| `media` | `audioRate` (Hz), `audioCodec` (e.g. `PCMU/8000`), `videoCodec` (`VP8`, `H264` or null), `sendVideo`, `receiveVideo` | negotiated media; the browser starts capture/encoders accordingly |
| `keyframeRequest` | | the remote party asked for a key frame: encode the next frame as key frame |
| `dtmf` | `digit` | DTMF received |
| `message` | `from`, `text` | SIP MESSAGE received |
| `sip` | `direction` (`in`/`out`), `remote`, `text` | SIP trace of every message on the wire |
| `stats` | `audio: {sent, received, lost}`, `video: {...}` | periodic RTP statistics (every 2 s during a call) |
| `error` | `message` | a command failed |
