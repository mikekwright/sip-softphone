// Audio (AudioWorklet) and video (WebCodecs) engines of the web softphone.
import { createFakeMic, drawPlaceholder, drawTestPattern } from './fake-media.js';

const TAG = '[softphone]';
const log = (...a) => console.log(TAG, ...a);
const warn = (...a) => console.warn(TAG, ...a);

export const KIND_AUDIO = 0x01;
export const KIND_VIDEO = 0x02;

/* ------------------------------------------------------------------------------------------------ */
/* Audio                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

export class AudioEngine {
  /**
   * @param {object} o
   * @param {(pcm: Int16Array) => void} o.onPcm      captured 20 ms chunk at the negotiated rate
   * @param {(rms: number) => void} [o.onMicLevel]
   * @param {(s: object) => void} [o.onPlaybackStats]
   * @param {(state: string) => void} [o.onContextState]
   */
  constructor(o) {
    this.o = o;
    this.ctx = null;
    this.moduleReady = null;
    this.gen = 0;
    this.rate = 0;
    this.running = false;
    this.muted = false;
    this.fakeMic = false;
    this.capture = null;
    this.playback = null;
    this.sink = null;
    this.source = null;
    this.stream = null;
    this.fakeSource = null;
    this.playbackStats = null;
  }

  /** Create (once) and return the AudioContext. Call from a user gesture when possible. */
  context() {
    if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) throw new Error('Web Audio is not supported by this browser');
      this.ctx = new AC({ latencyHint: 'interactive' });
      this.ctx.onstatechange = () => this.o.onContextState && this.o.onContextState(this.ctx.state);
      this.moduleReady = this.ctx.audioWorklet
        .addModule(new URL('./audio-worklet.js', import.meta.url).href)
        .then(() => true);
      log('AudioContext created, rate', this.ctx.sampleRate, 'state', this.ctx.state);
    }
    return this.ctx;
  }

  get contextState() {
    return this.ctx ? this.ctx.state : 'none';
  }

  /** Resume the context (must be called from a user gesture in browsers with an autoplay policy). */
  unlock() {
    try {
      const ctx = this.context();
      if (ctx.state === 'suspended') return ctx.resume().catch((e) => warn('resume failed', e));
    } catch (e) {
      warn('audio unlock failed', e);
    }
    return Promise.resolve();
  }

  /**
   * Start capture + playback at the negotiated rate.
   * @returns {Promise<{fakeMic: boolean}>}
   */
  async start({ rate, fake, toneHz }) {
    this.stop();
    const gen = ++this.gen;
    const ctx = this.context();
    await this.moduleReady;
    if (gen !== this.gen) return { fakeMic: false };
    this.rate = rate;

    // Playback first: it does not depend on device permissions.
    this.playback = new AudioWorkletNode(ctx, 'playback-processor', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [1],
      processorOptions: { sourceRate: rate, targetMs: 80, maxMs: 200 },
    });
    this.playback.port.onmessage = (e) => {
      if (e.data && e.data.type === 'stats') {
        this.playbackStats = e.data;
        if (this.o.onPlaybackStats) this.o.onPlaybackStats(e.data);
      }
    };
    this.playback.connect(ctx.destination);

    // Capture source: real microphone or synthetic tone.
    let stream = null;
    if (!fake) {
      try {
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('getUserMedia unavailable');
        stream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
          video: false,
        });
      } catch (e) {
        warn('microphone unavailable, using synthetic tone:', e && (e.message || e.name));
        stream = null;
      }
    }
    if (gen !== this.gen) {
      if (stream) stream.getTracks().forEach((t) => t.stop());
      return { fakeMic: false };
    }
    if (stream) {
      this.stream = stream;
      this.source = ctx.createMediaStreamSource(stream);
      this.fakeMic = false;
    } else {
      this.fakeSource = createFakeMic(ctx, toneHz || 440);
      this.source = this.fakeSource.node;
      this.fakeMic = true;
    }

    this.capture = new AudioWorkletNode(ctx, 'capture-processor', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1],
      channelCount: 1,
      channelCountMode: 'explicit',
      channelInterpretation: 'speakers',
      processorOptions: { targetRate: rate, muted: this.muted },
    });
    this.capture.port.onmessage = (e) => {
      const d = e.data;
      if (!d) return;
      if (d.type === 'pcm') this.o.onPcm(d.pcm);
      else if (d.type === 'level' && this.o.onMicLevel) this.o.onMicLevel(this.muted ? 0 : d.rms);
    };
    // The capture node must be pulled by the graph: route it into a muted gain to the destination.
    this.sink = ctx.createGain();
    this.sink.gain.value = 0;
    this.source.connect(this.capture);
    this.capture.connect(this.sink).connect(ctx.destination);
    this.running = true;
    log(`audio started: ${rate} Hz, ${rate / 50} samples/chunk, context ${ctx.sampleRate} Hz (${ctx.state}),`,
      this.fakeMic ? 'synthetic mic' : 'microphone');
    return { fakeMic: this.fakeMic };
  }

  stop() {
    this.gen++;
    const nodes = [this.source, this.capture, this.sink, this.playback];
    if (this.capture) this.capture.port.postMessage({ stop: true });
    if (this.playback) this.playback.port.postMessage({ stop: true });
    for (const n of nodes) {
      if (!n) continue;
      try { n.disconnect(); } catch (_) { /* ignore */ }
    }
    if (this.fakeSource) this.fakeSource.stop();
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
    if (this.running) log('audio stopped');
    this.source = this.capture = this.sink = this.playback = this.stream = this.fakeSource = null;
    this.running = false;
    this.rate = 0;
    this.playbackStats = null;
  }

  setMuted(m) {
    this.muted = !!m;
    if (this.capture) this.capture.port.postMessage({ muted: this.muted });
  }

  /** Queue a received PCM chunk (Int16Array, ownership transferred). */
  play(pcm) {
    if (this.playback) this.playback.port.postMessage({ pcm }, [pcm.buffer]);
  }

  /** Short local feedback tone (DTMF key press, ringing) if the context is running. */
  beep(freqs, durationMs = 120, level = 0.08, when = 0) {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== 'running') return;
    const t0 = ctx.currentTime + when;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t0);
    g.gain.linearRampToValueAtTime(level, t0 + 0.01);
    g.gain.setValueAtTime(level, t0 + durationMs / 1000 - 0.01);
    g.gain.linearRampToValueAtTime(0, t0 + durationMs / 1000);
    g.connect(ctx.destination);
    for (const f of freqs) {
      const o = ctx.createOscillator();
      o.frequency.value = f;
      o.connect(g);
      o.start(t0);
      o.stop(t0 + durationMs / 1000 + 0.02);
      o.onended = () => {
        try { o.disconnect(); g.disconnect(); } catch (_) { /* ignore */ }
      };
    }
  }
}

/* ------------------------------------------------------------------------------------------------ */
/* Video                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

export const VIDEO_WIDTH = 640;
export const VIDEO_HEIGHT = 480;
export const VIDEO_FPS = 15;
const KEYFRAME_INTERVAL_MS = 3000;
const KEYFRAME_REQUEST_MIN_INTERVAL_MS = 1000;

export function webCodecsSupported() {
  return typeof window.VideoEncoder === 'function' && typeof window.VideoDecoder === 'function' &&
    typeof window.VideoFrame === 'function' && typeof window.EncodedVideoChunk === 'function';
}

function normCodec(codec) {
  return String(codec || '').toUpperCase();
}

function encoderConfig(codec) {
  const base = {
    width: VIDEO_WIDTH,
    height: VIDEO_HEIGHT,
    bitrate: 600_000,
    framerate: VIDEO_FPS,
    latencyMode: 'realtime',
  };
  switch (normCodec(codec)) {
    case 'VP8': return { ...base, codec: 'vp8' };
    case 'H264': return { ...base, codec: 'avc1.42e01f', avc: { format: 'annexb' } };
    default: return null;
  }
}

function decoderConfig(codec, h264String) {
  switch (normCodec(codec)) {
    case 'VP8': return { codec: 'vp8', optimizeForLatency: true };
    case 'H264': return { codec: h264String || 'avc1.42e01f', optimizeForLatency: true };
    default: return null;
  }
}

/** Iterate over H.264 Annex-B NAL units: calls fn(type, offsetOfNalHeader). */
function forEachNal(data, fn) {
  const n = data.length;
  for (let i = 0; i + 3 < n; i++) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      const p = i + 3;
      if (p < n && fn(data[p] & 0x1f, p) === false) return;
      i = p;
    }
  }
}

function isKeyFrame(codec, data) {
  if (normCodec(codec) === 'VP8') {
    return data.length >= 6 && (data[0] & 1) === 0 && data[3] === 0x9d && data[4] === 0x01 && data[5] === 0x2a;
  }
  if (normCodec(codec) === 'H264') {
    let key = false;
    forEachNal(data, (type) => {
      if (type === 5 || type === 7) {
        key = true;
        return false;
      }
      return true;
    });
    return key;
  }
  return false;
}

/** Codec string ("avc1.PPCCLL") from the first SPS of an Annex-B frame, or null. */
function h264CodecString(data) {
  let s = null;
  forEachNal(data, (type, p) => {
    if (type === 7 && p + 3 < data.length) {
      const hex = (b) => b.toString(16).padStart(2, '0');
      s = `avc1.${hex(data[p + 1])}${hex(data[p + 2])}${hex(data[p + 3])}`;
      return false;
    }
    return true;
  });
  return s;
}

export class VideoEngine {
  /**
   * @param {object} o
   * @param {HTMLCanvasElement} o.localCanvas   capture canvas (also the local preview)
   * @param {HTMLCanvasElement} o.remoteCanvas
   * @param {(frame: Uint8Array) => boolean} o.sendFrame   returns false when the frame was not sent
   * @param {() => number} o.backlog            bytes queued on the WebSocket
   * @param {() => void} o.requestKeyframe      send {type:'keyframe'} to the server
   * @param {object} o.counters                 shared counters object (window.softphone)
   * @param {(ev: string, detail?: any) => void} [o.onEvent]
   */
  constructor(o) {
    this.o = o;
    this.counters = o.counters;
    this.localCtx = o.localCanvas.getContext('2d', { alpha: false });
    this.remoteCtx = o.remoteCanvas.getContext('2d', { alpha: false });
    o.localCanvas.width = VIDEO_WIDTH;
    o.localCanvas.height = VIDEO_HEIGHT;
    // Sending
    this.sendGen = 0;
    this.encoder = null;
    this.sendCodec = null;
    this.timer = null;
    this.cam = null;
    this.fake = false;
    this.cameraEnabled = true;
    this.userName = '';
    this.frameNo = 0;
    this.forceKey = true;
    this.lastKey = 0;
    this.t0 = 0;
    this.lastTs = -1;
    this.skippedEncode = 0;
    // Receiving
    this.decoder = null;
    this.rxCodec = null;
    this.rxCodecString = null;
    this.waitKey = true;
    this.lastKeyReq = -Infinity;
    this.rxSize = null;
    this.skippedDecode = 0;
  }

  event(name, detail) {
    try {
      if (this.o.onEvent) this.o.onEvent(name, detail);
    } catch (e) {
      warn('video event handler failed', e);
    }
  }

  get sending() {
    return !!this.encoder;
  }

  get receiving() {
    return !!this.decoder;
  }

  get usingCamera() {
    return !!this.cam;
  }

  /* ---------------------------------------------------------------- send */

  async startSend({ codec, fake, userName, cameraEnabled }) {
    this.stopSend();
    const gen = ++this.sendGen;
    const config = encoderConfig(codec);
    if (!config) throw new Error(`unsupported video codec ${codec}`);
    let support;
    try {
      support = await VideoEncoder.isConfigSupported(config);
    } catch (e) {
      support = { supported: false };
    }
    if (gen !== this.sendGen) return;
    if (!support || !support.supported) throw new Error(`${codec} encoding is not supported by this browser`);

    this.fake = !!fake;
    this.userName = userName || '';
    this.cameraEnabled = cameraEnabled !== false;
    this.sendCodec = normCodec(codec);
    this.encoder = new VideoEncoder({
      output: (chunk) => this.onEncoded(chunk),
      error: (e) => {
        warn('video encoder error', e && (e.message || e));
        this.recreateEncoder(config);
      },
    });
    this.encoder.configure(config);
    this.encoderConfig = config;
    this.forceKey = true;
    this.lastKey = 0;
    this.t0 = performance.now();
    this.lastTs = -1;
    this.frameNo = 0;
    if (this.cameraEnabled && !this.fake) await this.openCamera(gen);
    if (gen !== this.sendGen) return;
    this.timer = setInterval(() => this.tick(), Math.round(1000 / VIDEO_FPS));
    log(`video send started: ${this.sendCodec} ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}`,
      this.fake ? '(test pattern)' : this.cam ? '(camera)' : '(camera off)');
    this.event('send-started', { fake: this.fake });
  }

  recreateEncoder(config) {
    if (!this.encoder) return;
    try {
      if (this.encoder.state !== 'closed') this.encoder.close();
    } catch (_) { /* ignore */ }
    try {
      this.encoder = new VideoEncoder({
        output: (chunk) => this.onEncoded(chunk),
        error: (e) => {
          warn('video encoder error (giving up)', e && (e.message || e));
          this.stopSend();
          this.event('send-failed', e);
        },
      });
      this.encoder.configure(config);
      this.forceKey = true;
    } catch (e) {
      warn('cannot recreate encoder', e);
      this.stopSend();
      this.event('send-failed', e);
    }
  }

  async openCamera(gen) {
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('getUserMedia unavailable');
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { width: { ideal: VIDEO_WIDTH }, height: { ideal: VIDEO_HEIGHT }, frameRate: { ideal: VIDEO_FPS } },
      });
      if (gen !== this.sendGen || !this.cameraEnabled) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      const video = document.createElement('video');
      video.muted = true;
      video.playsInline = true;
      video.autoplay = true;
      video.srcObject = stream;
      try {
        await video.play();
      } catch (e) {
        warn('camera preview play() failed', e && e.message);
      }
      this.cam = { stream, video };
      this.event('camera', true);
    } catch (e) {
      warn('camera unavailable, using test pattern:', e && (e.message || e.name));
      this.fake = true;
      this.event('fallback', 'camera');
    }
  }

  closeCamera() {
    if (!this.cam) return;
    this.cam.stream.getTracks().forEach((t) => t.stop());
    this.cam.video.srcObject = null;
    this.cam = null;
    this.event('camera', false);
  }

  setCameraEnabled(on) {
    this.cameraEnabled = !!on;
    if (!this.encoder) return;
    if (!on) this.closeCamera();
    else if (!this.fake && !this.cam) this.openCamera(this.sendGen);
    this.forceKey = true;
  }

  /** The remote party asked for a key frame. */
  requestKeyFrame() {
    this.forceKey = true;
  }

  draw() {
    const g = this.localCtx;
    const w = VIDEO_WIDTH;
    const h = VIDEO_HEIGHT;
    if (!this.cameraEnabled) {
      drawPlaceholder(g, w, h, 'Camera off', this.userName);
    } else if (this.fake) {
      drawTestPattern(g, w, h, { name: this.userName, frame: this.frameNo });
    } else if (this.cam && this.cam.video.readyState >= 2 && this.cam.video.videoWidth > 0) {
      const v = this.cam.video;
      // "cover" crop to 4:3
      const vr = v.videoWidth / v.videoHeight;
      const cr = w / h;
      let sw = v.videoWidth;
      let sh = v.videoHeight;
      if (vr > cr) sw = sh * cr;
      else sh = sw / cr;
      g.drawImage(v, (v.videoWidth - sw) / 2, (v.videoHeight - sh) / 2, sw, sh, 0, 0, w, h);
    } else {
      drawPlaceholder(g, w, h, 'Starting camera…', this.userName);
    }
  }

  tick() {
    try {
      this.draw();
      this.frameNo++;
      const enc = this.encoder;
      if (!enc || enc.state !== 'configured') return;
      if (enc.encodeQueueSize > 2 || this.o.backlog() > 2_000_000) {
        this.skippedEncode++;
        return;
      }
      const now = performance.now();
      let ts = Math.round((now - this.t0) * 1000);
      if (ts <= this.lastTs) ts = this.lastTs + 1;
      this.lastTs = ts;
      const key = this.forceKey || now - this.lastKey >= KEYFRAME_INTERVAL_MS;
      const frame = new VideoFrame(this.o.localCanvas, { timestamp: ts });
      try {
        enc.encode(frame, { keyFrame: key });
        if (key) {
          this.forceKey = false;
          this.lastKey = now;
        }
      } finally {
        frame.close();
      }
    } catch (e) {
      warn('video tick failed', e && (e.message || e));
    }
  }

  onEncoded(chunk) {
    const out = new Uint8Array(10 + chunk.byteLength);
    out[0] = KIND_VIDEO;
    out[1] = chunk.type === 'key' ? 1 : 0;
    new DataView(out.buffer).setBigUint64(2, BigInt(Math.max(0, Math.round(chunk.timestamp))));
    chunk.copyTo(out.subarray(10));
    this.counters.framesEncoded++;
    if (chunk.type === 'key') this.counters.keyFramesEncoded++;
    if (this.o.sendFrame(out)) this.counters.videoFramesSent++;
  }

  stopSend() {
    this.sendGen++;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.closeCamera();
    if (this.encoder) {
      try {
        if (this.encoder.state !== 'closed') this.encoder.close();
      } catch (_) { /* ignore */ }
      log('video send stopped');
    }
    this.encoder = null;
    this.sendCodec = null;
  }

  /* ------------------------------------------------------------- receive */

  startReceive(codec) {
    this.stopReceive();
    if (!decoderConfig(codec)) throw new Error(`unsupported video codec ${codec}`);
    this.rxCodec = normCodec(codec);
    this.rxCodecString = null;
    this.newDecoder();
    log('video receive started:', this.rxCodec);
  }

  newDecoder() {
    if (this.decoder) {
      try {
        if (this.decoder.state !== 'closed') this.decoder.close();
      } catch (_) { /* ignore */ }
    }
    const dec = new VideoDecoder({
      output: (frame) => this.onDecoded(frame),
      error: (e) => {
        if (dec === this.decoder) this.decodeFailed(e);
      },
    });
    this.decoder = dec;
    this.decoder.configure(decoderConfig(this.rxCodec, this.rxCodecString));
    this.waitKey = true;
  }

  decodeFailed(e) {
    if (!this.rxCodec) return;
    warn('video decode error, resetting decoder:', e && (e.message || e));
    this.counters.decoderResets++;
    try {
      this.newDecoder();
    } catch (err) {
      warn('cannot recreate decoder', err);
      this.decoder = null;
    }
    this.askKeyFrame();
  }

  askKeyFrame() {
    const now = performance.now();
    if (now - this.lastKeyReq < KEYFRAME_REQUEST_MIN_INTERVAL_MS) return;
    this.lastKeyReq = now;
    this.counters.keyframeRequestsSent++;
    this.o.requestKeyframe();
  }

  /** Binary 0x02 frame from the server. */
  onFrame(buf) {
    if (!this.rxCodec || buf.byteLength <= 10) return;
    if (!this.decoder) {
      try {
        this.newDecoder();
      } catch (e) {
        return;
      }
    }
    const dv = new DataView(buf);
    const flags = dv.getUint8(1);
    const ts = Number(dv.getBigUint64(2));
    const data = new Uint8Array(buf, 10);
    const key = (flags & 1) === 1 || isKeyFrame(this.rxCodec, data);
    if (key && this.rxCodec === 'H264') {
      const cs = h264CodecString(data);
      if (cs && cs !== this.rxCodecString) {
        this.rxCodecString = cs;
        log('H264 stream profile/level', cs);
        try {
          this.newDecoder();
        } catch (e) {
          warn('decoder reconfigure failed', e);
        }
      }
    }
    if (this.waitKey && !key) {
      this.skippedDecode++;
      this.askKeyFrame();
      return;
    }
    if (this.decoder.state === 'closed') {
      this.decodeFailed(new Error('decoder closed'));
      return;
    }
    if (this.decoder.decodeQueueSize > 30) {
      // Hopelessly behind: start over at the next key frame.
      this.decodeFailed(new Error('decoder backlog'));
      return;
    }
    try {
      this.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data }));
      this.waitKey = false;
    } catch (e) {
      this.decodeFailed(e);
    }
  }

  onDecoded(frame) {
    try {
      const c = this.o.remoteCanvas;
      const w = frame.displayWidth;
      const h = frame.displayHeight;
      if (c.width !== w || c.height !== h) {
        c.width = w;
        c.height = h;
      }
      this.remoteCtx.drawImage(frame, 0, 0, w, h);
      this.counters.framesDecoded++;
      if (!this.rxSize || this.rxSize.w !== w || this.rxSize.h !== h) {
        this.rxSize = { w, h };
        this.event('remote-size', this.rxSize);
      }
      if (this.counters.framesDecoded === 1 || !this.hadFrame) {
        this.hadFrame = true;
        this.event('remote-first-frame');
      }
    } catch (e) {
      warn('draw failed', e);
    } finally {
      frame.close();
    }
  }

  stopReceive() {
    if (this.decoder) {
      try {
        if (this.decoder.state !== 'closed') this.decoder.close();
      } catch (_) { /* ignore */ }
      log('video receive stopped');
    }
    this.decoder = null;
    this.rxCodec = null;
    this.rxSize = null;
    this.hadFrame = false;
    const c = this.o.remoteCanvas;
    this.remoteCtx.fillStyle = '#000';
    this.remoteCtx.fillRect(0, 0, c.width, c.height);
  }

  stop() {
    this.stopSend();
    this.stopReceive();
  }
}
