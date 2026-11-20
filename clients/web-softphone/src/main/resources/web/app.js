// Web softphone browser UI. Protocol: docs/clients/web-softphone-protocol.md
import { AudioEngine, VideoEngine, KIND_AUDIO, KIND_VIDEO, webCodecsSupported } from './media.js';
import { toneForUser } from './fake-media.js';

const TAG = '[softphone]';
const log = (...a) => console.log(TAG, ...a);
const warn = (...a) => console.warn(TAG, ...a);

/* ------------------------------------------------------------------------------------------------ */
/* Parameters & persisted settings                                                                  */
/* ------------------------------------------------------------------------------------------------ */

const query = new URLSearchParams(location.search);
const qBool = (k) => query.has(k) && !['0', 'false', 'no', 'off'].includes((query.get(k) || '').toLowerCase());
const qStr = (k) => (query.has(k) ? query.get(k) : null);

const STORE_ACCOUNT = 'softphone.account.v1';
const STORE_PREFS = 'softphone.prefs.v1';

const DEFAULT_ACCOUNT = {
  displayName: '',
  user: 'alice',
  domain: '127.0.0.1',
  password: '',
  authUser: '',
  registrar: '',
  outboundProxy: '',
  stunServer: '',
  transport: 'udp',
  sipPort: 0,
  register: false,
};

function loadJson(key, fallback) {
  try {
    const v = JSON.parse(localStorage.getItem(key) || 'null');
    return v && typeof v === 'object' ? { ...fallback, ...v } : { ...fallback };
  } catch (_) {
    return { ...fallback };
  }
}

function saveJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (_) { /* storage disabled */ }
}

/** Saved account with URL query overrides applied (overrides are not persisted until "Apply"). */
function initialAccount() {
  const acc = loadJson(STORE_ACCOUNT, DEFAULT_ACCOUNT);
  for (const k of ['displayName', 'user', 'domain', 'password', 'authUser', 'registrar', 'outboundProxy', 'stunServer', 'transport']) {
    const v = qStr(k);
    if (v !== null) acc[k] = v;
  }
  if (qStr('sipPort') !== null) acc.sipPort = parseInt(qStr('sipPort'), 10) || 0;
  if (query.has('register')) acc.register = qBool('register');
  acc.transport = String(acc.transport).toLowerCase() === 'tcp' ? 'tcp' : 'udp';
  return acc;
}

const prefs = loadJson(STORE_PREFS, { target: '', autoAnswer: false, tab: 'trace', accountOpen: false, camera: true });
if (qStr('target') !== null) prefs.target = qStr('target');
if (query.has('autoAnswer')) prefs.autoAnswer = qBool('autoAnswer');

const FAKE = qBool('fake');
const AUTOCALL = qBool('autocall');
const AUTOCALL_VIDEO = qBool('video');

/* ------------------------------------------------------------------------------------------------ */
/* Shared state / test hook                                                                         */
/* ------------------------------------------------------------------------------------------------ */

const ACTIVE_STATES = new Set(['CALLING', 'RINGING', 'EARLY_MEDIA', 'CONNECTED', 'HOLD', 'REMOTE_HOLD']);

/**
 * window.softphone: live state for humans (devtools) and automated tests (Playwright polling).
 * state = current call state ('IDLE' before the first call, then the last callState.state).
 */
const S = {
  state: 'IDLE',
  lastCallState: null,
  connected: false,
  ready: null,
  localUri: null,
  registration: null,
  incoming: null,
  media: null,
  stats: null,
  framesDecoded: 0,
  framesEncoded: 0,
  keyFramesEncoded: 0,
  videoFramesSent: 0,
  videoFramesReceived: 0,
  audioChunksSent: 0,
  audioChunksReceived: 0,
  keyframeRequestsSent: 0,
  keyframeRequestsReceived: 0,
  decoderResets: 0,
  messages: [],
  dtmfReceived: '',
  errors: [],
  sipMessages: 0,
  fakeVideo: false,
  fakeAudio: false,
  muted: false,
  cameraEnabled: prefs.camera !== false,
  audioContextState: 'none',
  send: (obj) => sendJson(obj),
  call: (target, video = false) => placeCall(target, video),
  answer: (video = false) => answer(video),
  hangup: () => hangup(),
};
window.softphone = S;

/* ------------------------------------------------------------------------------------------------ */
/* DOM helpers                                                                                      */
/* ------------------------------------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  return e;
};

/** Wrap an event handler so it never throws out of the event loop. */
const safe = (fn) => (...args) => {
  try {
    const r = fn(...args);
    if (r && typeof r.catch === 'function') r.catch((e) => warn('async handler failed', e));
    return r;
  } catch (e) {
    warn('handler failed', e);
    return undefined;
  }
};

function toast(text, kind = 'info', ms = 4000) {
  const box = $('toasts');
  const t = el('div', `toast ${kind}`, text);
  box.appendChild(t);
  while (box.children.length > 5) box.firstChild.remove();
  setTimeout(() => t.remove(), ms);
}

const fmtTime = (d = new Date()) =>
  `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}.${String(d.getMilliseconds()).padStart(3, '0')}`;

window.addEventListener('error', (e) => {
  warn('uncaught error', e.message);
});
window.addEventListener('unhandledrejection', (e) => {
  warn('unhandled rejection', e.reason);
  e.preventDefault();
});

/* ------------------------------------------------------------------------------------------------ */
/* WebSocket                                                                                        */
/* ------------------------------------------------------------------------------------------------ */

let ws = null;
let backoff = 500;
let reconnectTimer = null;
let readyCount = 0;

function wsUrl() {
  const override = qStr('ws');
  if (override) return override;
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
}

function connect() {
  clearTimeout(reconnectTimer);
  setWsPill('busy', 'Connecting…');
  let sock;
  try {
    sock = new WebSocket(wsUrl());
  } catch (e) {
    warn('cannot create WebSocket', e);
    scheduleReconnect();
    return;
  }
  ws = sock;
  sock.binaryType = 'arraybuffer';
  sock.onopen = safe(() => {
    if (sock !== ws) return;
    log('connected to', wsUrl());
    backoff = 500;
    S.connected = true;
    setWsPill('ok', 'Connected');
    sendConfigure();
  });
  sock.onmessage = (e) => {
    if (sock !== ws) return;
    try {
      if (typeof e.data === 'string') onJson(e.data);
      else onBinary(e.data);
    } catch (err) {
      warn('message handling failed', err);
    }
  };
  sock.onerror = () => { /* onclose follows */ };
  sock.onclose = safe((e) => {
    if (sock !== ws) return;
    log('connection closed', e.code, e.reason || '');
    S.connected = false;
    S.ready = null;
    if (ACTIVE_STATES.has(S.state) || S.incoming) {
      onCallState({ type: 'callState', callId: S.lastCallState && S.lastCallState.callId, state: 'ENDED', reason: 'connection lost' });
    }
    teardownMedia();
    renderReady();
    scheduleReconnect();
  });
}

function scheduleReconnect() {
  const delay = Math.min(backoff, 10000) * (0.8 + Math.random() * 0.4);
  backoff = Math.min(backoff * 2, 10000);
  setWsPill('bad', `Reconnecting in ${Math.ceil(delay / 1000)}s`);
  reconnectTimer = setTimeout(connect, delay);
}

function wsOpen() {
  return ws && ws.readyState === WebSocket.OPEN;
}

function sendJson(obj) {
  if (!wsOpen()) {
    warn('not connected, dropping', obj && obj.type);
    return false;
  }
  ws.send(JSON.stringify(obj));
  return true;
}

function sendBinary(bytes) {
  if (!wsOpen()) return false;
  ws.send(bytes);
  return true;
}

function setWsPill(kind, text) {
  const p = $('wsPill');
  p.className = `pill pill-${kind}`;
  p.textContent = text;
}

/* ------------------------------------------------------------------------------------------------ */
/* Account                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

let account = initialAccount();

function fillAccountForm(acc) {
  const f = $('accountForm');
  for (const [k, v] of Object.entries(acc)) {
    const input = f.elements.namedItem(k);
    if (!input) continue;
    if (input.type === 'checkbox') input.checked = !!v;
    else input.value = v === undefined || v === null ? '' : String(v);
  }
}

function readAccountForm() {
  const f = $('accountForm');
  const val = (k) => (f.elements.namedItem(k).value || '').trim();
  return {
    displayName: val('displayName'),
    user: val('user') || DEFAULT_ACCOUNT.user,
    domain: val('domain') || DEFAULT_ACCOUNT.domain,
    password: f.elements.namedItem('password').value,
    authUser: val('authUser'),
    registrar: val('registrar'),
    outboundProxy: val('outboundProxy'),
    stunServer: val('stunServer'),
    transport: val('transport') === 'tcp' ? 'tcp' : 'udp',
    sipPort: Math.max(0, Math.min(65535, parseInt(val('sipPort'), 10) || 0)),
    register: f.elements.namedItem('register').checked,
  };
}

function renderAccountSummary() {
  const a = account;
  $('accountSummary').textContent =
    `${a.user}@${a.domain} · ${a.transport.toUpperCase()}${a.sipPort ? `:${a.sipPort}` : ''}${a.register ? ' · REGISTER' : ''}`;
}

function sendConfigure() {
  const a = account;
  const acc = {
    user: a.user,
    domain: a.domain,
    transport: a.transport,
    sipPort: a.sipPort,
    register: !!a.register,
  };
  // Optional fields are only sent when set.
  for (const k of ['displayName', 'password', 'authUser', 'registrar', 'outboundProxy', 'stunServer']) if (a[k]) acc[k] = a[k];
  S.ready = null;
  renderReady();
  log('configure', { ...acc, password: acc.password ? '***' : undefined });
  return sendJson({ type: 'configure', account: acc });
}

function renderReady() {
  const r = S.ready;
  const uri = $('localUri');
  if (r && r.localUri) {
    uri.textContent = r.localUri;
    $('copyUri').disabled = false;
    $('readyInfo').textContent = `Listening on ${String(r.transport || account.transport).toUpperCase()} port ${r.sipPort}`;
  } else {
    uri.textContent = S.connected ? 'configuring…' : 'not connected';
    $('copyUri').disabled = true;
    $('readyInfo').textContent = '';
  }
}

function renderRegistration() {
  const r = S.registration;
  const pill = $('regPill');
  let text = 'Not registered';
  let kind = '';
  if (r) {
    text = r.state === 'REGISTERED' ? 'Registered' : r.state === 'REGISTERING' ? 'Registering…' : r.state === 'FAILED' ? 'Registration failed' : 'Unregistered';
    kind = r.state === 'REGISTERED' ? 'pill-ok' : r.state === 'REGISTERING' ? 'pill-busy' : r.state === 'FAILED' ? 'pill-bad' : '';
  }
  pill.className = `pill ${kind}`;
  pill.textContent = text;
  pill.title = r && r.status ? `Registration: ${r.status}` : 'Registration';
  $('stReg').textContent = r ? `${r.state}${r.status ? ` (${r.status})` : ''}` : account.register ? '…' : 'off';
}

/* ------------------------------------------------------------------------------------------------ */
/* Media                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

const webCodecs = webCodecsSupported();
let micLevel = 0;
let spkLevel = 0;

const audio = new AudioEngine({
  onPcm(pcm) {
    const frame = new Uint8Array(1 + pcm.byteLength);
    frame[0] = KIND_AUDIO;
    frame.set(new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength), 1);
    if (sendBinary(frame)) S.audioChunksSent++;
  },
  onMicLevel(rms) {
    micLevel = rms;
  },
  onPlaybackStats(s) {
    spkLevel = s.rms;
  },
  onContextState(state) {
    S.audioContextState = state;
    updateUnlockHint();
  },
});

const video = new VideoEngine({
  localCanvas: $('localVideo'),
  remoteCanvas: $('remoteVideo'),
  sendFrame: sendBinary,
  backlog: () => (ws ? ws.bufferedAmount : 0),
  requestKeyframe: () => sendJson({ type: 'keyframe' }),
  counters: S,
  onEvent: (name, detail) => {
    if (name === 'fallback' && detail === 'camera') {
      S.fakeVideo = true;
      renderFakeBadge();
      toast('No camera available: sending a test pattern', 'info');
    } else if (name === 'camera') {
      $('pip').classList.toggle('mirror', !!detail);
    } else if (name === 'remote-first-frame') {
      renderStage();
    } else if (name === 'send-failed') {
      toast('Video encoder failed; video sending stopped', 'error');
      renderStage();
    }
  },
});

let mediaKey = { audioRate: 0, send: null, recv: null };

function startMedia(m) {
  const rate = m.audioRate || 8000;
  // Audio: (re)start only when the rate changed.
  if (!audio.running || audio.rate !== rate || mediaKey.audioRate !== rate) {
    mediaKey.audioRate = rate;
    audio
      .start({ rate, fake: FAKE, toneHz: Number(qStr('tone')) || toneForUser(account.user) })
      .then((info) => {
        S.fakeAudio = !!info.fakeMic;
        audio.setMuted(S.muted);
        renderFakeBadge();
        updateUnlockHint();
      })
      .catch((e) => {
        warn('audio start failed', e);
        toast(`Audio failed: ${e.message || e}`, 'error');
      });
  }

  const codec = m.videoCodec || null;
  if (codec && !webCodecs) {
    $('webcodecsWarning').hidden = false;
    warn('WebCodecs not available, video disabled');
  }
  const wantSend = !!(codec && m.sendVideo && webCodecs);
  const wantRecv = !!(codec && m.receiveVideo && webCodecs);

  const sendKey = wantSend ? codec.toUpperCase() : null;
  if (sendKey !== mediaKey.send) {
    mediaKey.send = sendKey;
    if (wantSend) {
      S.fakeVideo = FAKE;
      video
        .startSend({ codec, fake: FAKE, userName: account.displayName || account.user, cameraEnabled: S.cameraEnabled })
        .then(() => {
          $('pip').classList.toggle('mirror', video.usingCamera);
          renderFakeBadge();
          renderStage();
        })
        .catch((e) => {
          warn('video send failed', e);
          mediaKey.send = null;
          toast(`Video sending disabled: ${e.message || e}`, 'error');
          renderStage();
        });
    } else {
      video.stopSend();
    }
  }

  const recvKey = wantRecv ? codec.toUpperCase() : null;
  if (recvKey !== mediaKey.recv) {
    mediaKey.recv = recvKey;
    if (wantRecv) {
      try {
        video.startReceive(codec);
      } catch (e) {
        warn('video receive failed', e);
        mediaKey.recv = null;
        toast(`Cannot decode ${codec}: ${e.message || e}`, 'error');
      }
    } else {
      video.stopReceive();
    }
  }
  renderStage();
}

function teardownMedia() {
  audio.stop();
  video.stop();
  mediaKey = { audioRate: 0, send: null, recv: null };
  micLevel = spkLevel = 0;
  S.media = null;
  renderStage();
}

function updateUnlockHint() {
  const needs = audio.running && audio.contextState === 'suspended';
  $('audioUnlock').hidden = !needs;
}

function renderFakeBadge() {
  const b = $('fakeBadge');
  const v = S.fakeVideo && video.sending;
  const a = S.fakeAudio && audio.running;
  b.hidden = !(v || a || FAKE);
  b.textContent = v || (FAKE && !a) ? 'TEST PATTERN' : 'TEST TONE';
  b.title = [FAKE ? 'fake media mode (?fake=1)' : '', a ? 'synthetic microphone tone' : '', v ? 'synthetic camera' : '']
    .filter(Boolean)
    .join(', ');
}

/* ------------------------------------------------------------------------------------------------ */
/* Call control                                                                                     */
/* ------------------------------------------------------------------------------------------------ */

let connectedAt = 0;
let endedTimer = null;
let ringTimer = null;

function callActive() {
  return ACTIVE_STATES.has(S.state);
}

function placeCall(target, withVideo) {
  target = String(target || '').trim();
  if (!target) {
    toast('Enter a target SIP URI first', 'error');
    $('target').focus();
    return false;
  }
  if (callActive()) {
    toast('A call is already in progress', 'error');
    return false;
  }
  audio.unlock();
  prefs.target = target;
  savePrefs();
  log('call', target, withVideo ? '(video)' : '(audio)');
  return sendJson({ type: 'call', target, video: !!withVideo });
}

function answer(withVideo) {
  audio.unlock();
  stopRinging();
  log('answer', withVideo ? '(video)' : '(audio)');
  const ok = sendJson({ type: 'answer', video: !!withVideo });
  S.incoming = null;
  renderCall();
  return ok;
}

function reject() {
  stopRinging();
  const ok = sendJson({ type: 'reject' });
  S.incoming = null;
  renderCall();
  return ok;
}

function hangup() {
  stopRinging();
  return sendJson({ type: 'hangup' });
}

function toggleHold() {
  if (S.state === 'HOLD') sendJson({ type: 'resume' });
  else if (S.state === 'CONNECTED' || S.state === 'REMOTE_HOLD') sendJson({ type: 'hold' });
}

function toggleMute() {
  S.muted = !S.muted;
  audio.setMuted(S.muted);
  renderCall();
}

function toggleCamera() {
  S.cameraEnabled = !S.cameraEnabled;
  prefs.camera = S.cameraEnabled;
  savePrefs();
  video.setCameraEnabled(S.cameraEnabled);
  renderCall();
}

const DTMF_FREQ = {
  1: [697, 1209], 2: [697, 1336], 3: [697, 1477],
  4: [770, 1209], 5: [770, 1336], 6: [770, 1477],
  7: [852, 1209], 8: [852, 1336], 9: [852, 1477],
  '*': [941, 1209], 0: [941, 1336], '#': [941, 1477],
};

function pressKey(d) {
  audio.beep(DTMF_FREQ[d] || [], 120, 0.06);
  if (callActive() && S.state !== 'CALLING' && S.state !== 'RINGING') {
    sendJson({ type: 'dtmf', digits: d });
    const info = $('dtmfInfo');
    info.textContent = `sent ${d}`;
  } else {
    const t = $('target');
    t.value += d;
  }
  const key = document.querySelector(`.key[data-d="${CSS.escape(d)}"]`);
  if (key) {
    key.classList.add('pressed');
    setTimeout(() => key.classList.remove('pressed'), 120);
  }
}

function startRinging() {
  stopRinging();
  const ring = () => {
    audio.beep([440, 480], 400, 0.05, 0);
    audio.beep([440, 480], 400, 0.05, 0.6);
  };
  ring();
  ringTimer = setInterval(ring, 3000);
}

function stopRinging() {
  if (ringTimer) clearInterval(ringTimer);
  ringTimer = null;
}

function onCallState(msg) {
  const prev = S.state;
  S.lastCallState = msg;
  S.state = msg.state;
  clearTimeout(endedTimer);
  endedTimer = null;
  if (msg.state === 'CONNECTED' && !connectedAt) connectedAt = Date.now();
  if (msg.state === 'CONNECTED' || msg.state === 'ENDED') {
    S.incoming = null;
    stopRinging();
  }
  if (msg.state === 'ENDED') {
    log('call ended', msg.reason || '');
    teardownMedia();
    connectedAt = 0;
    if (prev !== 'ENDED') toast(`Call ended${msg.reason ? `: ${msg.reason}` : ''}`);
    endedTimer = setTimeout(() => {
      endedTimer = null;
      renderCall();
    }, 3000);
  }
  renderCall();
}

/* ------------------------------------------------------------------------------------------------ */
/* Incoming JSON                                                                                    */
/* ------------------------------------------------------------------------------------------------ */

function onJson(text) {
  let msg;
  try {
    msg = JSON.parse(text);
  } catch (e) {
    warn('bad JSON from server', text.slice(0, 200));
    return;
  }
  switch (msg.type) {
    case 'ready':
      S.ready = msg;
      S.localUri = msg.localUri;
      readyCount++;
      log('ready', msg.localUri);
      renderReady();
      renderRegistration();
      if (readyCount === 1 && AUTOCALL && prefs.target) {
        setTimeout(safe(() => placeCall(prefs.target, AUTOCALL_VIDEO)), 300);
      }
      break;
    case 'registration':
      S.registration = msg;
      renderRegistration();
      if (msg.state === 'FAILED') toast(`Registration failed${msg.status ? `: ${msg.status}` : ''}`, 'error');
      break;
    case 'incoming':
      S.incoming = msg;
      log('incoming call from', msg.from, msg.video ? '(video offered)' : '');
      startRinging();
      renderCall();
      if (prefs.autoAnswer) {
        const withVideo = msg.video && !(query.has('video') && !qBool('video'));
        setTimeout(safe(() => {
          if (S.incoming === msg) answer(withVideo);
        }), 400);
      }
      break;
    case 'callState':
      onCallState(msg);
      break;
    case 'media':
      S.media = msg;
      log('media', msg);
      startMedia(msg);
      renderCall();
      break;
    case 'keyframeRequest':
      S.keyframeRequestsReceived++;
      video.requestKeyFrame();
      break;
    case 'dtmf':
      S.dtmfReceived += msg.digit;
      $('dtmfInfo').textContent = `received ${S.dtmfReceived.slice(-12)}`;
      toast(`DTMF received: ${msg.digit}`);
      break;
    case 'message':
      addChat({ from: msg.from, text: msg.text, me: false });
      break;
    case 'sip':
      addTrace(msg);
      break;
    case 'stats':
      S.stats = msg;
      renderStats();
      break;
    case 'error':
      S.errors.push(msg.message);
      warn('server error:', msg.message);
      toast(msg.message || 'error', 'error', 6000);
      break;
    default:
      log('unknown message', msg.type);
  }
}

function onBinary(buf) {
  if (!(buf instanceof ArrayBuffer) || buf.byteLength < 1) return;
  const kind = new Uint8Array(buf, 0, 1)[0];
  if (kind === KIND_AUDIO) {
    S.audioChunksReceived++;
    const n = (buf.byteLength - 1) >> 1;
    if (n > 0 && audio.running) audio.play(new Int16Array(buf.slice(1, 1 + n * 2)));
  } else if (kind === KIND_VIDEO) {
    S.videoFramesReceived++;
    video.onFrame(buf);
  }
}

/* ------------------------------------------------------------------------------------------------ */
/* Rendering: call                                                                                  */
/* ------------------------------------------------------------------------------------------------ */

function remoteName() {
  if (S.incoming) return S.incoming.from;
  if (S.lastCallState && S.lastCallState.remote) return S.lastCallState.remote;
  return '';
}

function shortName(uri) {
  const m = /^(?:"?([^"<]*)"?\s*<)?(?:sips?:)?([^@;>]+)/.exec(String(uri || ''));
  if (!m) return uri || '';
  return (m[1] && m[1].trim()) || m[2] || uri;
}

function stateClass(state) {
  if (state === 'CONNECTED') return 'state-connected';
  if (state === 'HOLD' || state === 'REMOTE_HOLD') return 'state-hold';
  if (state === 'ENDED') return 'state-ended';
  if (ACTIVE_STATES.has(state)) return 'state-progress';
  return 'state-idle';
}

function renderCall() {
  const active = callActive();
  const ringingIn = !!S.incoming;
  const st = ringingIn && !active ? 'INCOMING' : S.state;
  const remote = remoteName();
  const recentlyEnded = S.state === 'ENDED' && S.lastCallState && endedTimer && !ringingIn;

  // Incoming banner + answer controls.
  $('incomingBanner').hidden = !ringingIn;
  if (ringingIn) {
    $('incomingFrom').textContent = S.incoming.from || 'unknown';
    $('incomingKind').textContent = S.incoming.video ? 'video call' : 'call';
  }
  $('answerVideoBtn').disabled = !webCodecs;

  $('hangupBtn').disabled = !active;
  $('hangupBtn').hidden = ringingIn;
  $('callBtn').disabled = active || ringingIn;
  $('videoCallBtn').disabled = active || ringingIn || !webCodecs;
  const hold = $('holdBtn');
  hold.disabled = !(S.state === 'CONNECTED' || S.state === 'HOLD' || S.state === 'REMOTE_HOLD');
  hold.querySelector('use').setAttribute('href', S.state === 'HOLD' ? '#i-play' : '#i-pause');
  hold.querySelector('span').textContent = S.state === 'HOLD' ? 'Resume' : 'Hold';
  hold.setAttribute('aria-pressed', S.state === 'HOLD' ? 'true' : 'false');

  const mute = $('muteBtn');
  mute.setAttribute('aria-pressed', S.muted ? 'true' : 'false');
  mute.querySelector('use').setAttribute('href', S.muted ? '#i-mic-off' : '#i-mic');
  mute.querySelector('span').textContent = S.muted ? 'Unmute' : 'Mute';
  const cam = $('cameraBtn');
  cam.setAttribute('aria-pressed', S.cameraEnabled ? 'false' : 'true');
  cam.querySelector('use').setAttribute('href', S.cameraEnabled ? '#i-video' : '#i-video-off');
  cam.querySelector('span').textContent = S.cameraEnabled ? 'Camera' : 'Camera off';
  cam.disabled = !webCodecs;

  // Stage header.
  const chip = $('stageState');
  const shownState = active || ringingIn || recentlyEnded ? st : 'IDLE';
  chip.textContent = shownState.replace('_', ' ');
  chip.className = `state-chip ${ringingIn && !active ? 'state-progress' : stateClass(shownState)}`;
  $('stageRemote').textContent = active || ringingIn || recentlyEnded ? remote : '';
  $('stage').classList.toggle('ringing', ringingIn || S.state === 'RINGING' || S.state === 'CALLING');

  // Status bar.
  $('stCall').textContent = shownState;
  $('stRemote').textContent = remote || '–';
  $('stRemote').title = remote;
  const m = S.media;
  $('stCodecs').textContent = m ? [m.audioCodec || `L16/${m.audioRate}`, m.videoCodec].filter(Boolean).join(' + ') : '–';
  renderStage();
  renderTimer();
}

function renderStage() {
  const active = callActive();
  const ringingIn = !!S.incoming;
  const showRemote = video.receiving && video.hadFrame && active;
  $('remoteVideo').hidden = !showRemote;
  $('stagePlaceholder').hidden = showRemote;
  $('pip').hidden = !(video.sending && active);
  const remote = remoteName();
  const name = shortName(remote);
  $('avatar').textContent = (name || '?').charAt(0).toUpperCase() || '?';
  const recentlyEnded = S.state === 'ENDED' && endedTimer;
  $('placeholderName').textContent = active || ringingIn || recentlyEnded ? name || remote || 'Unknown' : 'No call';
  let text = 'Enter a SIP URI and press Call';
  if (ringingIn && !active) text = S.incoming.video ? 'Incoming video call…' : 'Incoming call…';
  else if (S.state === 'CALLING') text = 'Calling…';
  else if (S.state === 'RINGING') text = 'Ringing…';
  else if (S.state === 'EARLY_MEDIA') text = 'Early media…';
  else if (S.state === 'HOLD') text = 'On hold';
  else if (S.state === 'REMOTE_HOLD') text = 'Held by remote party';
  else if (S.state === 'CONNECTED') text = video.receiving ? 'Waiting for video…' : 'Audio call';
  else if (recentlyEnded) text = `Call ended${S.lastCallState.reason ? ` · ${S.lastCallState.reason}` : ''}`;
  $('placeholderText').textContent = text;
}

function renderTimer() {
  const t = $('callTimer');
  if (!connectedAt || !callActive()) {
    t.textContent = '';
    return;
  }
  const s = Math.floor((Date.now() - connectedAt) / 1000);
  const hh = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  t.textContent = hh ? `${hh}:${mm}:${ss}` : `${mm}:${ss}`;
}

/* ------------------------------------------------------------------------------------------------ */
/* SIP trace                                                                                        */
/* ------------------------------------------------------------------------------------------------ */

const TRACE_MAX = 1500;
let traceFilter = '';
let traceTotal = 0;

function headerValue(text, names) {
  for (const line of text.split(/\r?\n/)) {
    if (!line) break;
    const i = line.indexOf(':');
    if (i < 0) continue;
    const n = line.slice(0, i).trim().toLowerCase();
    if (names.includes(n)) return line.slice(i + 1).trim();
  }
  return '';
}

function addTrace(msg) {
  S.sipMessages++;
  traceTotal++;
  const text = String(msg.text || '');
  const first = text.split(/\r?\n/, 1)[0] || '(empty)';
  const isResp = /^SIP\/2\.0\s+\d{3}/.test(first);
  const code = isResp ? parseInt(first.split(/\s+/)[1], 10) : 0;
  const out = msg.direction === 'out';
  const row = el('div', `trace-row ${out ? 'out' : 'in'} ${isResp ? `r${Math.min(4, Math.floor(code / 100))}` : 'req'}`);
  row._search = `${text}\n${msg.remote || ''}`.toLowerCase();
  const main = el('div', 'row-main');
  main.append(el('span', 't', fmtTime()));
  main.append(el('span', 'lane'));
  const m = el('div', 'msg');
  const label = el('span', 'first', first);
  label.title = first;
  m.append(label, el('span', 'arrow'));
  main.append(m, el('span', 'lane'));
  const cseq = headerValue(text, ['cseq']);
  const sub = el('div', 'sub', `${out ? '→' : '←'} ${msg.remote || ''}${cseq ? `  ·  CSeq ${cseq}` : ''}`);
  const pre = el('pre', null, text);
  row.append(main, sub, pre);
  row.addEventListener('click', (e) => {
    if (e.target === pre && row.classList.contains('open')) return; // allow text selection
    row.classList.toggle('open');
  });
  if (traceFilter && !row._search.includes(traceFilter)) row.hidden = true;

  const list = $('traceList');
  const empty = list.querySelector('.empty');
  if (empty) empty.remove();
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
  list.append(row);
  while (list.children.length > TRACE_MAX) list.firstChild.remove();
  if ($('traceAutoscroll').checked && (atBottom || out)) list.scrollTop = list.scrollHeight;
  $('traceCount').textContent = String(traceTotal);
}

function applyTraceFilter() {
  traceFilter = $('traceFilter').value.trim().toLowerCase();
  for (const row of $('traceList').querySelectorAll('.trace-row')) {
    row.hidden = !!traceFilter && !row._search.includes(traceFilter);
  }
}

function clearTrace() {
  traceTotal = 0;
  $('traceCount').textContent = '';
  const list = $('traceList');
  list.replaceChildren(el('div', 'empty', 'No SIP messages yet.'));
}

/* ------------------------------------------------------------------------------------------------ */
/* Messages (SIP MESSAGE)                                                                           */
/* ------------------------------------------------------------------------------------------------ */

let unread = 0;
let activeTab = prefs.tab || 'trace';

function addChat({ from, to, text, me }) {
  const entry = { from, to, text, me, time: Date.now() };
  if (!me) S.messages.push({ from, text });
  const list = $('chatList');
  const empty = list.querySelector('.empty');
  if (empty) empty.remove();
  const b = el('div', `bubble${me ? ' me' : ''}`);
  b.append(el('div', 'meta', `${me ? `to ${to}` : from} · ${fmtTime(new Date(entry.time)).slice(0, 8)}`));
  b.append(el('div', 'body', text));
  list.append(b);
  list.scrollTop = list.scrollHeight;
  if (!me) {
    if (activeTab !== 'messages') {
      unread++;
      $('msgBadge').textContent = String(unread);
      toast(`Message from ${shortName(from)}: ${text.slice(0, 80)}`);
    }
    if (!$('chatTarget').value) $('chatTarget').value = from;
  }
}

function sendChat() {
  const target = $('chatTarget').value.trim() || remoteName() || $('target').value.trim();
  const text = $('chatText').value;
  if (!target) {
    toast('Enter a message target', 'error');
    return;
  }
  if (!text.trim()) return;
  if (sendJson({ type: 'message', target, text })) {
    $('chatTarget').value = target;
    addChat({ to: target, text, me: true });
    $('chatText').value = '';
  } else {
    toast('Not connected', 'error');
  }
}

/* ------------------------------------------------------------------------------------------------ */
/* Stats                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

function flatten(obj, prefix = '', out = []) {
  if (obj === null || obj === undefined) return out;
  if (typeof obj !== 'object') {
    out.push([prefix, obj]);
    return out;
  }
  for (const [k, v] of Object.entries(obj)) {
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, prefix ? `${prefix}.${k}` : k, out);
    else out.push([prefix ? `${prefix}.${k}` : k, Array.isArray(v) ? v.join(', ') : v]);
  }
  return out;
}

function table(title, rows) {
  const sec = el('section');
  sec.append(el('h3', null, title));
  const t = el('table');
  for (const [k, v] of rows) {
    const tr = el('tr');
    tr.append(el('td', null, k), el('td', null, typeof v === 'number' && !Number.isInteger(v) ? v.toFixed(1) : String(v)));
    t.append(tr);
  }
  if (!rows.length) {
    const tr = el('tr');
    const td = el('td', null, 'no data yet');
    td.colSpan = 2;
    tr.append(td);
    t.append(tr);
  }
  sec.append(t);
  return sec;
}

function renderStats() {
  if (activeTab !== 'stats') return;
  const view = $('statsView');
  const st = S.stats || {};
  const pb = audio.playbackStats;
  view.replaceChildren(
    table('RTP audio (server)', flatten(st.audio)),
    table('RTP video (server)', flatten(st.video)),
    table('Browser audio', [
      ['state', audio.running ? `${audio.rate} Hz · ${audio.fakeMic ? 'test tone' : 'microphone'}${S.muted ? ' · muted' : ''}` : 'stopped'],
      ['AudioContext', `${audio.contextState}${audio.ctx ? ` · ${audio.ctx.sampleRate} Hz` : ''}`],
      ['chunks sent', S.audioChunksSent],
      ['chunks received', S.audioChunksReceived],
      ['playout buffer (ms)', pb ? pb.bufferedMs : '–'],
      ['underruns', pb ? pb.underruns : '–'],
      ['dropped samples', pb ? pb.droppedSamples : '–'],
    ]),
    table('Browser video', [
      ['WebCodecs', webCodecs ? 'available' : 'missing'],
      ['sending', video.sending ? `${video.sendCodec} · ${video.fake ? 'test pattern' : video.usingCamera ? 'camera' : 'camera off'}` : 'no'],
      ['frames encoded', S.framesEncoded],
      ['key frames encoded', S.keyFramesEncoded],
      ['frames sent', S.videoFramesSent],
      ['receiving', video.receiving ? video.rxCodec + (video.rxSize ? ` · ${video.rxSize.w}x${video.rxSize.h}` : '') : 'no'],
      ['frames received', S.videoFramesReceived],
      ['frames decoded', S.framesDecoded],
      ['decoder resets', S.decoderResets],
      ['key frame requests sent', S.keyframeRequestsSent],
      ['key frame requests received', S.keyframeRequestsReceived],
    ]),
    table('Connection', [
      ['WebSocket', S.connected ? 'open' : 'closed'],
      ['socket backlog (bytes)', ws ? ws.bufferedAmount : 0],
      ['SIP messages', S.sipMessages],
    ]),
  );
}

/* ------------------------------------------------------------------------------------------------ */
/* Tabs, prefs & wiring                                                                             */
/* ------------------------------------------------------------------------------------------------ */

function savePrefs() {
  saveJson(STORE_PREFS, prefs);
}

function selectTab(name) {
  activeTab = name;
  prefs.tab = name;
  savePrefs();
  for (const b of document.querySelectorAll('.tab')) b.classList.toggle('active', b.dataset.tab === name);
  for (const p of document.querySelectorAll('.tabpanel')) p.hidden = p.id !== `tab-${name}`;
  if (name === 'messages') {
    unread = 0;
    $('msgBadge').textContent = '';
  }
  if (name === 'stats') renderStats();
}

function buildDialpad() {
  const keys = [
    ['1', ''], ['2', 'ABC'], ['3', 'DEF'], ['4', 'GHI'], ['5', 'JKL'], ['6', 'MNO'],
    ['7', 'PQRS'], ['8', 'TUV'], ['9', 'WXYZ'], ['*', ''], ['0', '+'], ['#', ''],
  ];
  const pad = $('dialpad');
  for (const [d, l] of keys) {
    const b = el('button', 'key');
    b.type = 'button';
    b.dataset.d = d;
    b.append(el('span', 'd', d), el('span', 'l', l));
    b.addEventListener('click', safe(() => pressKey(d)));
    pad.append(b);
  }
}

async function copyUri() {
  const text = S.ready && S.ready.localUri;
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
  } catch (_) {
    const r = document.createRange();
    r.selectNodeContents($('localUri'));
    const sel = getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
    try { document.execCommand('copy'); } catch (__) { /* ignore */ }
  }
  toast('Address copied', 'ok', 1500);
}

function wire() {
  buildDialpad();
  fillAccountForm(account);
  renderAccountSummary();
  $('accountPanel').open = prefs.accountOpen !== false;
  $('accountPanel').addEventListener('toggle', () => {
    prefs.accountOpen = $('accountPanel').open;
    savePrefs();
  });
  $('accountForm').addEventListener('submit', safe((e) => {
    e.preventDefault();
    account = readAccountForm();
    saveJson(STORE_ACCOUNT, account);
    renderAccountSummary();
    if (callActive()) toast('Reconfiguring ends the current call', 'info');
    if (!sendConfigure()) toast('Saved; will apply when connected');
  }));
  $('registerBtn').addEventListener('click', safe(() => sendJson({ type: 'register' })));
  $('unregisterBtn').addEventListener('click', safe(() => sendJson({ type: 'unregister' })));

  $('target').value = prefs.target || '';
  $('target').addEventListener('keydown', safe((e) => {
    if (e.key === 'Enter') placeCall($('target').value, e.shiftKey);
  }));
  $('target').addEventListener('change', () => {
    prefs.target = $('target').value.trim();
    savePrefs();
  });
  $('callBtn').addEventListener('click', safe(() => placeCall($('target').value, false)));
  $('videoCallBtn').addEventListener('click', safe(() => placeCall($('target').value, true)));
  $('autoAnswer').checked = !!prefs.autoAnswer;
  $('autoAnswer').addEventListener('change', () => {
    prefs.autoAnswer = $('autoAnswer').checked;
    savePrefs();
  });

  document.querySelectorAll('.js-answer').forEach((b) => b.addEventListener('click', safe(() => answer(false))));
  document.querySelectorAll('.js-answer-video').forEach((b) => b.addEventListener('click', safe(() => answer(true))));
  document.querySelectorAll('.js-reject').forEach((b) => b.addEventListener('click', safe(reject)));
  $('hangupBtn').addEventListener('click', safe(hangup));
  $('holdBtn').addEventListener('click', safe(toggleHold));
  $('muteBtn').addEventListener('click', safe(toggleMute));
  $('cameraBtn').addEventListener('click', safe(toggleCamera));
  $('copyUri').addEventListener('click', safe(copyUri));
  $('audioUnlock').addEventListener('click', safe(() => audio.unlock()));

  for (const b of document.querySelectorAll('.tab')) b.addEventListener('click', safe(() => selectTab(b.dataset.tab)));
  $('traceFilter').addEventListener('input', safe(applyTraceFilter));
  $('traceClear').addEventListener('click', safe(clearTrace));
  $('chatForm').addEventListener('submit', safe((e) => {
    e.preventDefault();
    sendChat();
  }));

  // First user gesture anywhere resumes the AudioContext (autoplay policy).
  const unlockOnce = () => {
    if (audio.ctx || audio.running) audio.unlock();
  };
  document.addEventListener('pointerdown', unlockOnce, true);
  document.addEventListener('keydown', unlockOnce, true);

  // Keyboard dialpad when not typing in a field.
  document.addEventListener('keydown', safe((e) => {
    const tag = (e.target && e.target.tagName) || '';
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || e.ctrlKey || e.metaKey || e.altKey) return;
    if (/^[0-9*#]$/.test(e.key)) pressKey(e.key);
  }));

  if (!webCodecs) {
    $('webcodecsWarning').hidden = false;
    warn('WebCodecs not available: audio only');
  }
  clearTrace();
  selectTab(activeTab);
  renderReady();
  renderRegistration();
  renderFakeBadge();
  renderCall();

  let tickNo = 0;
  setInterval(() => {
    try {
      tickNo++;
      renderTimer();
      const lvl = (v) => `${Math.min(100, Math.round(Math.sqrt(v) * 160))}%`;
      $('meters').hidden = !audio.running;
      $('micMeter').style.width = audio.running && !S.muted ? lvl(micLevel) : '0%';
      $('spkMeter').style.width = audio.running ? lvl(spkLevel) : '0%';
      if (activeTab === 'stats' && tickNo % 4 === 0) renderStats();
    } catch (e) {
      warn('periodic update failed', e);
    }
  }, 250);
}

try {
  wire();
} catch (e) {
  warn('UI init failed', e);
}
log('starting', { fake: FAKE, autocall: AUTOCALL, video: AUTOCALL_VIDEO, autoAnswer: prefs.autoAnswer, webCodecs });
connect();
