# NAT traversal lab

Proves that the softphone works from behind a home-router style NAT using STUN (RFC 8489) and keep-alives
(RFC 5626, RFC 6263), against an Asterisk that has **all of its own NAT workarounds disabled**.

```
 [ns home] alice 10.10.0.2 ── 10.10.0.1 [router: MASQUERADE + stateful firewall] 203.0.113.1
                                                     │
                            203.0.113.2 [ns inet] Asterisk :5160 · STUN :3478 · bob
```

The router behaves like a typical home router:
* outbound traffic is masqueraded (source NAT);
* unsolicited inbound traffic is dropped, both forwarded and to the WAN address itself;
* idle UDP mappings expire after 15 s (`CONNTRACK_UDP_TIMEOUT`).

The internet side has no route to `10.10.0.0/24`.

## Run (Linux, root, iproute2 + iptables + Asterisk)

```bash
./gradlew :clients:terminal-softphone:installDist
sudo interop/nat/lab-setup.sh
sudo cp interop/nat/pjsip.conf /etc/asterisk/pjsip.conf
sudo cp interop/asterisk/{extensions,modules,rtp}.conf /etc/asterisk/
sudo ip netns exec inet asterisk -f -U asterisk -G asterisk &
sudo ip netns exec inet java -cp 'clients/terminal-softphone/build/install/terminal-softphone/lib/*' \
     com.github.mikekwright.sip.softphone.nat.StunServer 203.0.113.2 3478 &
sudo interop/nat/run-nat-test.sh
sudo interop/nat/lab-teardown.sh     # afterwards (stop Asterisk / the STUN server first)
```

## Scenarios and results (2026-11-20, Asterisk 20.6, Linux conntrack)

Each scenario has three steps:
1. alice and bob register, then alice stays idle for 20 s, longer than the NAT timeout.
2. bob calls alice, which is inbound through the NAT.
3. alice calls bob, holds/resumes and sends DTMF.

Audio is checked in both directions with tone detection on the recordings. A scenario stops at its first failed
step.

| Scenario | alice options | Result |
|----------|---------------|--------|
| `no-stun` | `--keepalive 0` | fails as expected: alice registers `10.10.0.2`, Asterisk's INVITE cannot reach her |
| `stun-no-keepalive` | `--stun 203.0.113.2:3478 --keepalive 0` | fails as expected: the public Contact is right, but the NAT mapping expired during the idle period |
| `stun+keepalive` | `--stun 203.0.113.2:3478 --keepalive 5` | **passes**: inbound and outbound calls with audio both ways, hold/resume, DTMF |

### Lesson learned

An earlier version of the lab router accepted unsolicited packets addressed to its own WAN IP. Asterisk's first RTP
packets reached the router before alice's first outbound packet. Linux conntrack recorded that unsolicited flow,
and the masquerade then had to choose a *different* public port for alice's media. That port no longer matched the
STUN-discovered port in the SDP, so alice heard nothing. Real home routers drop unsolicited WAN input, and so does
the lab now. Strict or symmetric NATs can still behave this way, which is what ICE/TURN (or a provider-side media
relay) solves; see [docs/protocol/nat-traversal.md](../../docs/protocol/nat-traversal.md).
