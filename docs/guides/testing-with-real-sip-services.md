# Testing against real SIP services

## Google Voice

Google Voice does not give personal accounts SIP credentials, so a third-party softphone (this one included)
cannot register to a consumer Google Voice number. The only SIP access Google offers is
[SIP Link](https://support.google.com/a/answer/11606486): a Google Workspace feature that requires an admin, a
Google Voice Standard or Premier license, and a certified session border controller connected over TLS with
SRTP media. `softphone-lib` does not implement SRTP yet (see [TODO](../TODO.md)), so SIP Link is not an option
today.

## Free options

| Option | Cost | What you can test | Notes |
|--------|------|-------------------|-------|
| **Local Asterisk** ([interop/asterisk](../../interop/asterisk/README.md)) | free, open source | registration and auth, calls through a B2BUA, DTMF, hold, TCP, video relay | **Recommended.** Fully scripted and verified. |
| Local FreeSWITCH | free, open source | the same kinds of checks; it ships test extensions such as 9196 (echo) | Same approach as Asterisk. Not scripted here yet. |
| [Linphone free SIP service](https://www.linphone.org/freesip) (`sip.linphone.org`) | free account | registration over the public internet, NAT, calls to Linphone apps (audio and video) | Create an account, then use `--domain sip.linphone.org --password ...`. Their service may require TCP/TLS. |
| [sip2sip.info](https://sip2sip.info/) | free account | registration, calls to other SIP users, echo test extension | Older listings mention an echo test at `4444@sip2sip.info`. Check their current documentation. |
| [iptel.org](https://www.iptel.org/) | free account | registration and calls | FreeSWITCH docs list `sip:echo@iptel.org` as an echo test. |
| Low-cost providers (for example VoIP.ms, Callcentric) | pay as you go, a few dollars | real phone numbers: call your mobile, IVRs, carriers | Use these when you need PSTN reachability. |

The public services above have not been verified from this repository's CI: the development sandbox blocks
outbound SIP. Treat the echo-test addresses as hints.

## Using a public provider

Terminal client:

```bash
terminal-softphone --user <user> --domain sip.linphone.org --password '<pw>' --register \
  --transport tcp --port 0 --trace
softphone> call sip:<other-user>@sip.linphone.org
```

Library:

```java
Softphone phone = Softphone.create(SoftphoneConfig.builder()
        .user("<user>").domain("sip.linphone.org").password("<pw>")
        .registrar("sip:sip.linphone.org").transport(TransportType.TCP).sipPort(0)
        .build()).start().join();
phone.register().get(10, TimeUnit.SECONDS);
```

Behind a home router add `--stun <server>` (library: `.stunServer(...)`), e.g. a public STUN server or your
provider's. The phone then advertises its public address and sends keep-alives every 25 s. Also disable "SIP ALG"
on the router if it has one. See [NAT traversal](../protocol/nat-traversal.md) and the
[NAT lab](../../interop/nat/README.md) for how this was verified.
