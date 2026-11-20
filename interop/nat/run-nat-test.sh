#!/usr/bin/env bash
# NAT traversal test (run as root after lab-setup.sh, with Asterisk and the STUN server running in the "inet"
# namespace, see README.md). alice sits behind the NAT, bob on the "internet". For each scenario:
#   1. both register with Asterisk; alice then stays idle for IDLE seconds (longer than the NAT's UDP timeout)
#   2. bob calls alice (inbound through the NAT) - both must hear each other's tone
#   3. alice calls bob, holds/resumes and sends DTMF - audio both ways, bob must receive the digits
# Scenarios: "no-stun" and "stun-no-keepalive" are expected to FAIL, "stun+keepalive" must PASS.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$ROOT/clients/terminal-softphone/build/install/terminal-softphone/bin/terminal-softphone"
OUT="${OUT:-/tmp/nat-lab}"
IDLE="${IDLE:-20}"
REG="--domain 203.0.113.2 --registrar sip:203.0.113.2:5160 --register --port 0"

tone() { python3 - "$1" "$2" <<'PY'
import sys,wave,struct,math
try:
    w=wave.open(sys.argv[1]); n=w.getnframes(); r=w.getframerate()
except Exception:
    print(0.0); sys.exit()
d=struct.unpack('<%dh'%n,w.readframes(n)); seg=d[n//3:n//3+4000]
if len(seg) < 1000: print(0.0); sys.exit()
def g(f):
    k=2*math.cos(2*math.pi*f/r);s1=s2=0
    for x in seg: s0=x+k*s1-s2; s2=s1; s1=s0
    return s1*s1+s2*s2-k*s1*s2
p={f:g(f) for f in (440,600)}
rms=math.sqrt(sum(x*x for x in seg)/len(seg))
print(round(p[int(sys.argv[2])]/(sum(p.values()) or 1),3) if rms > 300 else 0.0)
PY
}

scenario() { # name, alice options
  local name="$1" opts="$2" dir="$OUT/$1"
  rm -rf "$dir"; mkdir -p "$dir"
  ip netns exec home "$T" --user alice --password alice-secret $REG $opts --audio tone:600 \
    --record "$dir/alice-%n.wav" --auto-answer=0.2 --batch --exec "await registered 5; await incoming $((IDLE+15)); \
    await connected 5; wait 3; await ended 10; wait 1; call sip:bob@203.0.113.2:5160; await connected 10; wait 2; \
    hold; resume; dtmf 42; wait 2; hangup; await ended 5" > "$dir/alice.log" 2>&1 &
  local alice=$!
  sleep 3
  ip netns exec inet "$T" --user bob --password bob-secret $REG --audio tone:440 --record "$dir/bob-%n.wav" \
    --auto-answer=0.2 --batch --exec "await registered 5; wait $IDLE; call sip:alice@203.0.113.2:5160; \
    await connected 10; wait 3; hangup; await ended 5; await incoming 30; await connected 10; \
    expect-dtmf 42 15; await ended 15" > "$dir/bob.log" 2>&1
  local bob_rc=$?
  wait $alice; local alice_rc=$?
  local in_a in_b out_a out_b
  in_a=$(tone "$dir/alice-1.wav" 440); in_b=$(tone "$dir/bob-1.wav" 600)
  out_a=$(tone "$dir/alice-2.wav" 440); out_b=$(tone "$dir/bob-2.wav" 600)
  echo "[$name] inbound call: alice heard bob=$in_a, bob heard alice=$in_b | outbound call: alice heard bob=$out_a," \
       "bob heard alice=$out_b | exit alice=$alice_rc bob=$bob_rc"
  python3 -c "import sys; sys.exit(0 if min($in_a,$in_b,$out_a,$out_b) > 0.9 and $alice_rc == 0 and $bob_rc == 0 else 1)"
}

fail=0
scenario no-stun "--keepalive 0" && { echo "  unexpected PASS"; fail=1; } || echo "  failed as expected"
scenario stun-no-keepalive "--stun 203.0.113.2:3478 --keepalive 0" && { echo "  unexpected PASS"; fail=1; } \
  || echo "  failed as expected"
scenario stun+keepalive "--stun 203.0.113.2:3478 --keepalive 5" && echo "  PASS" || { echo "  FAIL"; fail=1; }
exit $fail
