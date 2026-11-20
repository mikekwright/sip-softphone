# Asterisk interop harness

A free, fully local way to test `softphone-lib` and both clients against a real third-party SIP server.
[Asterisk](https://www.asterisk.org/) is open source and available from the Ubuntu/Debian package mirrors.

| File | Purpose |
|------|---------|
| `pjsip.conf` | UDP + TCP transports on port 5160, users `alice` / `alice-secret` and `bob` / `bob-secret` (digest auth), μ-law, A-law, VP8, H.264, RFC 4733 DTMF |
| `extensions.conf` | `600` echo test, `601` DTMF capture (writes the digits to `/tmp/asterisk-dtmf.txt`), `602` 1 kHz tone, `alice` / `bob` call the registered user |
| `modules.conf`, `rtp.conf` | trimmed module list, RTP ports 10000-10999 |
| `run-interop.sh` | automated scenarios with pass/fail checks |
| `tone_check.py` | reports which tone (440 / 600 / 1000 Hz) dominates a WAV recording |

## Setup (Ubuntu / Debian)

```bash
sudo apt-get install -y asterisk
sudo cp interop/asterisk/{pjsip,extensions,modules,rtp}.conf /etc/asterisk/
sudo systemctl restart asterisk        # or: sudo asterisk -f -U asterisk -G asterisk
sudo asterisk -rx "pjsip show endpoints"
```

This config listens on all interfaces and is meant only for a lab or test machine. Change the passwords if
other people can reach it.

## Automated scenarios

```bash
interop/asterisk/run-interop.sh            # ASTERISK_HOST / ASTERISK_PORT / BIND to point elsewhere
```

1. REGISTER with digest auth, call the echo test, and check the echoed 600 Hz tone in the recording.
2. Send RFC 4733 DTMF `1234#` and check that Asterisk's `Read()` captured `1234`.
3. Over TCP, call the 1 kHz tone extension and check the recording.
4. alice calls bob through Asterisk (B2BUA) with hold/resume and DTMF `987`: each side records the other's tone,
   and bob receives the digits relayed by Asterisk.

## Video through Asterisk with the web client

Start `./gradlew :clients:web-softphone:run`, then open two tabs:

```
http://localhost:8080/?user=bob&password=bob-secret&registrar=sip:127.0.0.1:5160&register=1&autoAnswer=1
http://localhost:8080/?user=alice&password=alice-secret&registrar=sip:127.0.0.1:5160&register=1
```

Video-call `sip:bob@127.0.0.1:5160` from alice. Asterisk relays the VP8 stream (`direct_media=no`).

## Results (2026-11-20, Asterisk 20.6.0, Ubuntu 24.04)

All four scripted scenarios passed. Interop checks performed:

- REGISTER with a 401 challenge, followed by refresh and unregister
- INVITE 401 challenge: the 401 was ACKed, then the INVITE was re-sent with credentials
- RFC 4733 DTMF received by Asterisk
- re-INVITE hold/resume
- B2BUA call with audio flowing in both directions
- TCP transport

The web client video call through Asterisk (two headless Chromium tabs) decoded about 80 VP8 frames in each
direction, with 0 audio packets lost.
