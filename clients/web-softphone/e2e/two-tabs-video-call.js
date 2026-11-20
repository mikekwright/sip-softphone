// End-to-end check of the web softphone: two headless Chromium tabs, each with its own Java softphone behind
// the gateway, place a VP8 video call to each other over real SIP/RTP sockets, exchange audio, send DTMF and
// hang up. Requires Playwright with Chromium and a running server:
//   ./gradlew :clients:web-softphone:run --args="--http-port 8089 --http-bind 127.0.0.1 --sip-bind 127.0.0.1 --sip-host 127.0.0.1"
//   node clients/web-softphone/e2e/two-tabs-video-call.js
// Exits non-zero when a check fails.
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
  const errors = [];
  const mk = async (name, url) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const p = await ctx.newPage();
    p.on('pageerror', e => errors.push(name + ' pageerror ' + e.message));
    p.on('console', m => { if (m.type() === 'error') errors.push(name + ' console ' + m.text()); });
    await p.goto(url);
    return p;
  };
  const base = process.env.BASE_URL || 'http://127.0.0.1:8089/';
  const bob = await mk('bob', base + '?user=bob&sipPort=5090&autoAnswer=1&video=1&fake=1');
  await bob.waitForFunction(() => window.softphone && window.softphone.ready, null, { timeout: 10000 });
  const alice = await mk('alice', base + '?user=alice&sipPort=0&autocall=1&video=1&fake=1&target=sip:bob@127.0.0.1:5090');
  await alice.waitForFunction(() => window.softphone.state === 'CONNECTED', null, { timeout: 15000 });
  await alice.waitForTimeout(4000);
  await alice.evaluate(() => window.softphone.send({ type: 'dtmf', digits: '159' }));
  await alice.waitForTimeout(1500);
  const pick = s => ({ state: s.state, media: s.media, enc: s.framesEncoded, dec: s.framesDecoded, aSent: s.audioChunksSent, aRecv: s.audioChunksReceived, dtmf: s.dtmfReceived, stats: s.stats });
  const a = await alice.evaluate(() => JSON.parse(JSON.stringify(window.softphone, (k, v) => typeof v === 'function' || k === 'sipMessages' ? undefined : v)));
  const b = await bob.evaluate(() => JSON.parse(JSON.stringify(window.softphone, (k, v) => typeof v === 'function' || k === 'sipMessages' ? undefined : v)));
  console.log('alice', JSON.stringify(pick(a)));
  console.log('bob  ', JSON.stringify(pick(b)));
  await alice.screenshot({ path: (process.env.OUT || '.') + '/alice.png' });
  await bob.screenshot({ path: (process.env.OUT || '.') + '/bob.png' });
  await alice.evaluate(() => window.softphone.hangup());
  await bob.waitForFunction(() => window.softphone.state === 'ENDED', null, { timeout: 5000 });
  console.log('bob ended:', await bob.evaluate(() => JSON.stringify(window.softphone.lastCallState)));
  console.log('errors', JSON.stringify(errors));
  const ok = a.framesDecoded > 10 && b.framesDecoded > 10 && a.audioChunksReceived > 50 && b.audioChunksReceived > 50
      && b.dtmfReceived === '159' && errors.length === 0;
  console.log(ok ? 'PASS' : 'FAIL');
  if (!ok) process.exitCode = 1;
  await browser.close();
})().catch(e => { console.error('FAILED', e); process.exit(1); });
