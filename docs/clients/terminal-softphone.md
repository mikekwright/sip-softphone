# Terminal softphone

An audio-only softphone for the terminal, built on `softphone-lib`. It doubles as a scriptable test driver:
every shell command is one library call, and batch mode turns a command list into a pass/fail test.

## Build and run

```bash
./gradlew :clients:terminal-softphone:installDist
clients/terminal-softphone/build/install/terminal-softphone/bin/terminal-softphone --help
```

Two phones on one machine, calling each other directly (no server needed):

```bash
# terminal 1
terminal-softphone --user bob --port 5070 --auto-answer
# terminal 2
terminal-softphone --user alice --port 5080
softphone(idle)> call sip:bob@127.0.0.1:5070
```

Against a PBX / SIP provider:

```bash
terminal-softphone --user 1001 --domain pbx.example.com --password s3cret --register --port 0
```

## Commands

| Command | What it does (library call) |
|---------|-----------------------------|
| `call <uri>` | `Softphone.call` (accepts `sip:...`, `user@host:port` or a bare user at our domain) |
| `answer`, `reject [code]`, `hangup` | `Call.answer`, `Call.reject`, `Call.hangup` (CANCEL / 603 / BYE as appropriate) |
| `hold`, `resume` | re-INVITE with `sendonly` / `sendrecv` |
| `dtmf <digits>` | RFC 4733 telephone-events |
| `info-dtmf <digit>` | SIP INFO `application/dtmf-relay` |
| `say <text>` / `msg <uri> <text>` | SIP MESSAGE inside / outside the call |
| `options <uri>` | OPTIONS ping |
| `register`, `unregister` | registrar binding with automatic refresh |
| `status`, `calls`, `select <n>` | show phone and call state (codec, RTP counters) |
| `audio device\|silence\|tone[:hz]\|file:<wav>\|echo` | change the audio source |
| `record <file.wav>\|off` | record received audio of new calls (`%n` = call number) |
| `trace short\|full\|off` | print SIP messages as they cross the wire |
| `wait <sec>` | sleep |
| `await connected\|ringing\|incoming\|early\|ended\|registered [timeout]` | block until the state is reached; fails on timeout |
| `expect-dtmf <digits> [timeout]` | block until these digits were received; fails otherwise |
| `quit` | exit |

## Audio

* `device` uses `javax.sound.sampled` (default microphone and speaker) when available; otherwise the phone falls
  back to silence and discards received audio, so it works on headless servers and in containers.
* `tone:<hz>` sends a sine tone, `file:<wav>` plays a WAV file in a loop (any rate / channel count).
* `echo` sends back whatever is received: run one instance with `--auto-answer --audio echo` and you have an echo
  test endpoint.

## Automation / batch mode

`--batch` runs the `--exec` / `--script` commands and exits; the exit code is 1 if any command or check failed.

```bash
# echo endpoint
terminal-softphone --user bob --port 5480 --auto-answer=0.5 --audio echo --batch \
  --exec "await incoming 20; await connected 10; expect-dtmf 42# 10; await ended 20" &

# test driver
terminal-softphone --user alice --port 0 --audio tone:600 --record /tmp/alice-%n.wav --trace --batch \
  --exec "call sip:bob@127.0.0.1:5480; await connected 10; dtmf 42#; wait 1.5; hold; resume; hangup; await ended 5"
echo "exit code: $?"
```

The recording of the second command contains the 600 Hz tone echoed back by bob, which can be analysed with any
audio tool (the library's `ToneDetector` does the same inside Java tests).

## Options

Run `terminal-softphone --help` for the full list: identity (`--user --domain --password --auth-user
--registrar --register --proxy`), network (`--transport --port --bind --host --rtp-ports --codecs --stun
--keepalive`), media
(`--audio --record`) and automation (`--auto-answer --trace --exec --script --batch`).
