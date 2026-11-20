# NAT traversal

Package `com.github.mikekwright.sip.softphone.nat` plus small hooks in the transport, flows and RTP sessions.

## The problem

Behind a NAT the phone only knows its private address. Via, Contact and the SDP `c=`/`m=` lines would advertise
`192.168.x.y`, which the provider and remote phones cannot reach. In addition, the router forgets idle UDP
mappings after 30 s to a few minutes, after which inbound requests (calls, BYE) are dropped.

## What the library does

| Mechanism | Spec | Where |
|-----------|------|-------|
| `rport` in every Via; responses sent to the observed source | RFC 3581 | `TransactionLayer.createVia`, `TransportLayer.responseDestination` |
| Symmetric RTP: send from the receive port, latch onto the observed media source | RFC 4961 | `RtpSession` (enabled by default) |
| **STUN Binding** from the SIP socket: public IP:port used in Via and Contact | RFC 8489 | `SipStack.start` → `StunClient` |
| **STUN Binding** from each RTP and RTCP socket: public ports used in `m=`, `c=` and `a=rtcp` | RFC 8489, RFC 3605 | `Softphone.bindRtp`, `Call.withNatRtcp` |
| STUN, SIP and RTP share sockets; packets are told apart by their first byte | RFC 7983 | `StunHandler` (first in each UDP pipeline; also answers Binding requests from peers) |
| CRLF keep-alives towards the registrar and active call peers | RFC 5626 §3.5.1, §4.4 | `SipStack.keepAlive`, `Registration`, `InviteSession` |
| Zero-length RTP keep-alives while no media is sent (hold, receive-only) | RFC 6263 §4.1 | `RtpSession.enableKeepAlive` |
| Manual override when the public address is known | | `SoftphoneConfig.advertisedHost` / `mediaHost` |

```
  phone (10.0.0.5:5060)          NAT (203.0.113.1)                 STUN server / provider
     │── Binding request ───────────►│── 203.0.113.1:5060 ───────────────►│
     │◄─ XOR-MAPPED-ADDRESS 203.0.113.1:5060 ────────────────────────────│
     │── REGISTER  Contact: <sip:alice@203.0.113.1:5060> ───────────────►│
     │── "\r\n\r\n" every 25 s (keeps the mapping) ──────────────────────►│
     │◄─────────────────────────── INVITE to 203.0.113.1:5060 (inbound call reaches us)
```

## Configuration

```java
SoftphoneConfig.builder()
        .stunServer("stun.example.org:3478")             // enables discovery (default: off)
        .keepAliveInterval(Duration.ofSeconds(25))       // default 25 s; Duration.ZERO disables
        .build();
```

Terminal client: `--stun host[:port] --keepalive SECONDS`. Web client: `--stun` server default, or the
*STUN server* field per account. A failed STUN lookup is logged and the phone falls back to local addresses.

## Limits

STUN finds the mapping a socket gets towards the STUN server. With **endpoint-independent mapping** NATs (most home
routers), the same public port is then used towards every peer, which is what this relies on. **Symmetric NATs**
(some carrier-grade and corporate NATs) allocate a different port per destination, so the advertised port is
wrong. There, only a relay helps: a provider-side media relay (very common with SIP providers), or ICE with TURN
(RFC 8445 / RFC 8656), which is the next step on the roadmap. The NAT lab
([interop/nat](../../interop/nat/README.md)) also shows how unsolicited early media can steal a port mapping on
permissive routers.
