# SIP stack layers

`softphone-lib` follows the layering of RFC 3261 §5 literally: every layer is its own package with a narrow
interface to the layer above and below, so each one can be read, tested and replaced on its own.

```
 +-------------------------------------------------------------+
 |  Application API      Softphone, Call (package root, call)   |  automation friendly facade
 +-------------------------------------------------------------+
 |  Sessions / flows     sip.session.InviteSession              |  INVITE, CANCEL, BYE, re-INVITE, INFO
 |                       sip.flow.Registration                  |  REGISTER + refresh
 |                       sip.flow.NonInviteRequestFlow          |  any request + digest retry
 +-------------------------------------------------------------+
 |  UA core (TU)         sip.core.SipStack                      |  §8: request routing, 405/420/481/501,
 |                       sip.core.MessageFactory                |  CANCEL matching, dialog dispatch
 +-------------------------------------------------------------+
 |  Dialogs              sip.dialog.Dialog                      |  §12: ids, route set, CSeq, remote target
 +-------------------------------------------------------------+
 |  Transactions         sip.transaction.*                      |  §17: 4 state machines, timers A-M
 +-------------------------------------------------------------+
 |  Transport            sip.transport.*                        |  §18: UDP, TCP, TLS; rport; resolver
 |  Netty codecs         sip.codec.*                            |  framing: datagram / stream
 +-------------------------------------------------------------+
 |  Message model        sip.message.*, sip.parser.*            |  §7, §19-§20, §25 grammar
 +-------------------------------------------------------------+
```

## Threading model

```
  Netty I/O threads (sip-io-*)          SIP core thread (sip-core)            Softphone events thread
  ----------------------------          --------------------------            -----------------------
  read socket                           TransportLayer.process()              CallListener / SoftphoneListener
  frame + parse (sip.codec)   ------->  interceptors                ------->  callbacks for application code
                              execute   TransactionLayer.onMessage            (never blocks the protocol)
                                        SipStack (TU) / dialogs / sessions
                                        all timers (Timer A..M, 2xx retransmit, refresh)
```

* All protocol state lives on **one** executor (`SipStack.core()`), so transactions, dialogs and sessions use no
  locks. Public methods that may be called from other threads hop onto it (`SipStack.execute/submit`).
* Netty I/O threads only parse/frame and write; they never touch protocol state.
* RTP sessions run on the shared Netty I/O group; each RTP session is pinned to one event loop.
* The high level API dispatches listener callbacks on a dedicated single thread so application code can block
  briefly (e.g. call `call.answer()` from `onIncomingCall`) without stalling SIP processing.

## Request path (UAC)

1. A flow builds a request with `MessageFactory.createRequest` (Max-Forwards, From tag, Call-ID, CSeq) or
   `Dialog.createRequest` (route set, remote target, tags).
2. `SipStack.resolveNextHop` picks the next hop: first `Route`, else the outbound proxy, else the
   Request-URI, resolved with RFC 3263 (`SipResolver`). Large requests switch from UDP to TCP (§18.1.1).
3. `TransactionLayer.sendRequest` pushes a `Via` (advertised sent-by, new `z9hG4bK` branch, `rport`) and
   starts the INVITE or non-INVITE client transaction.
4. `TransportLayer.send` runs interceptors and writes through the `UdpSipTransport` or `StreamSipTransport`.

## Response path (UAS)

1. The transport decodes the message; the transport layer checks mandatory headers and stamps
   `received`/`rport` into the top Via (§18.2.1, RFC 3581).
2. The transaction layer matches it (§17.2.3) - retransmissions are absorbed, new requests create a server
   transaction and are passed to `SipStack.onRequest`.
3. The UA core applies §8.2 checks (`Require` -> 420, unknown dialog -> 481, unknown method -> 405/501),
   handles CANCEL (§9.2) and dispatches to the dialog usage or the method's `RequestHandler`.
4. Responses go back via `TransportLayer.sendResponse`: same connection for TCP/TLS, else `received` + `rport`
   (§18.2.2).

## Extension points

| Hook | Use |
|------|-----|
| `SipStack.registerHandler(method, handler)` | serve new out-of-dialog methods (SUBSCRIBE, REFER, PUBLISH...) |
| `SipStack.registerDialog(id, usage)` | new dialog usages (subscriptions) |
| `SipStack.addInterceptor(interceptor)` | trace, assert, mutate or drop messages; fault injection |
| `SipStackConfig.pipelineCustomizer` | add Netty handlers (logging, traffic shaping, packet loss) |
| `SipResolver` | custom DNS / static routing |
| `CredentialsProvider` | per-realm credentials |
| `SipStack.sendRequest(request, listener)` | arbitrary request flows with full transaction semantics |
