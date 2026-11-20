#!/usr/bin/env bash
# Interop scenarios between the terminal softphone and a local Asterisk configured with the files in this
# directory (see README.md). Exits non-zero on the first failed check.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/interop/asterisk"
AST="${ASTERISK_HOST:-127.0.0.1}:${ASTERISK_PORT:-5160}"
OUT="${OUT:-$(mktemp -d)}"
mkdir -p "$OUT"
T="$ROOT/clients/terminal-softphone/build/install/terminal-softphone/bin/terminal-softphone"
LOCAL="--domain ${ASTERISK_HOST:-127.0.0.1} --port 0 --bind ${BIND:-127.0.0.1} --host ${BIND:-127.0.0.1}"
ALICE="--user alice --password alice-secret --registrar sip:$AST --register $LOCAL"
BOB="--user bob --password bob-secret --registrar sip:$AST --register $LOCAL"

[ -x "$T" ] || (cd "$ROOT" && ./gradlew -q :clients:terminal-softphone:installDist)
expect_tone() { # file frequency
  local share; share=$(python3 - "$1" "$2" <<'PY'
import sys,wave,struct,math
w=wave.open(sys.argv[1]); n=w.getnframes(); r=w.getframerate()
d=struct.unpack('<%dh'%n,w.readframes(n)); seg=d[n//3:n//3+4000]
def g(f):
    k=2*math.cos(2*math.pi*f/r);s1=s2=0
    for x in seg: s0=x+k*s1-s2; s2=s1; s1=s0
    return s1*s1+s2*s2-k*s1*s2
p={f:g(f) for f in (440,600,1000)}; print(round(p[int(sys.argv[2])]/(sum(p.values()) or 1),3))
PY
)
  echo "  $(basename "$1"): share of ${2} Hz = $share"
  python3 -c "import sys; sys.exit(0 if $share > 0.9 else 1)"
}

echo "== 1. REGISTER (digest) + echo test (600) over UDP"
"$T" $ALICE --audio tone:600 --record "$OUT/echo-%n.wav" --batch \
  --exec "await registered 5; call sip:600@$AST; await connected 10; wait 3; hangup; await ended 5; unregister"
expect_tone "$OUT/echo-1.wav" 600

echo "== 2. RFC 4733 DTMF into Asterisk Read() (601)"
rm -f /tmp/asterisk-dtmf.txt
"$T" $ALICE --batch --exec "call sip:601@$AST; await connected 10; wait 1; dtmf 1234#; await ended 15"
got="$(cat /tmp/asterisk-dtmf.txt 2>/dev/null || true)"
echo "  Asterisk received: '$got'"; [ "$got" = "1234" ]

echo "== 3. Tone from Asterisk (602) over TCP"
"$T" $ALICE --transport tcp --record "$OUT/tcp-%n.wav" --batch \
  --exec "await registered 5; call sip:602@$AST; await connected 10; wait 3; hangup; await ended 5; unregister"
expect_tone "$OUT/tcp-1.wav" 1000

echo "== 4. alice -> Asterisk (B2BUA) -> bob: audio both ways, hold/resume, DTMF relay"
"$T" $BOB --auto-answer=0.3 --audio tone:440 --record "$OUT/bob-%n.wav" --batch \
  --exec "await registered 5; await incoming 20; await connected 10; expect-dtmf 987 15; await ended 30; unregister" &
BOB_PID=$!
sleep 4
"$T" $ALICE --audio tone:600 --record "$OUT/alice-%n.wav" --batch \
  --exec "call sip:bob@$AST; await connected 15; wait 2; hold; wait 1; resume; dtmf 987; wait 2; hangup; await ended 5"
wait "$BOB_PID"
expect_tone "$OUT/alice-1.wav" 440
expect_tone "$OUT/bob-1.wav" 600

echo "All interop scenarios passed (recordings in $OUT)"
