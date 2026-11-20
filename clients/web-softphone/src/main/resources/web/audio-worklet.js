/*
 * AudioWorklet processors of the web softphone.
 *
 * capture-processor:  microphone (context rate, mono) -> anti-alias low-pass + averaging decimator
 *                     (or linear interpolation when the target rate is higher) -> Int16 PCM chunks of
 *                     20 ms at the target rate, posted to the main thread as {type:'pcm', pcm: Int16Array}.
 *                     While muted the chunks keep flowing but contain zeros (digital silence).
 *
 * playback-processor: Int16 PCM chunks at the source rate (posted as {pcm: Int16Array}) -> jitter/ring
 *                     buffer with a target latency (default 80 ms) -> linear interpolation up to the
 *                     context rate. Underrun: output silence and re-buffer up to the target latency.
 *                     Overfull (> maxMs): drop the oldest samples down to the target latency.
 */
/* global sampleRate, registerProcessor, AudioWorkletProcessor */

/** RBJ cookbook biquad low-pass (direct form I). */
class LowPass {
  constructor(cutoff, fs, q = Math.SQRT1_2) {
    const w0 = (2 * Math.PI * Math.min(cutoff, fs * 0.49)) / fs;
    const cos = Math.cos(w0);
    const alpha = Math.sin(w0) / (2 * q);
    const a0 = 1 + alpha;
    this.b0 = (1 - cos) / 2 / a0;
    this.b1 = (1 - cos) / a0;
    this.b2 = (1 - cos) / 2 / a0;
    this.a1 = (-2 * cos) / a0;
    this.a2 = (1 - alpha) / a0;
    this.x1 = this.x2 = this.y1 = this.y2 = 0;
  }

  process(x) {
    const y = this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2;
    this.x2 = this.x1;
    this.x1 = x;
    this.y2 = this.y1;
    this.y1 = y;
    return y;
  }
}

const LEVEL_INTERVAL_SEC = 0.1;

class CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const o = (options && options.processorOptions) || {};
    this.targetRate = o.targetRate || 8000;
    this.chunkSize = Math.max(1, Math.round(this.targetRate / 50)); // 20 ms
    this.ratio = this.targetRate / sampleRate; // output samples per input sample
    this.down = this.ratio < 1;
    // Two cascaded 2nd order low-pass sections (4th order) at 45 % of the target rate.
    this.filters = this.down
      ? [new LowPass(this.targetRate * 0.45, sampleRate), new LowPass(this.targetRate * 0.45, sampleRate)]
      : [];
    this.phase = 0;
    this.acc = 0;
    this.accN = 0;
    this.prev = 0;
    this.out = new Int16Array(this.chunkSize);
    this.outPos = 0;
    this.muted = !!o.muted;
    this.running = true;
    this.levelSum = 0;
    this.levelN = 0;
    this.levelEvery = Math.round(sampleRate * LEVEL_INTERVAL_SEC);
    this.port.onmessage = (e) => {
      const d = e.data || {};
      if (d.muted !== undefined) this.muted = !!d.muted;
      if (d.stop) this.running = false;
    };
  }

  emit(v) {
    if (this.muted) v = 0;
    if (v > 1) v = 1;
    else if (v < -1) v = -1;
    this.out[this.outPos++] = v < 0 ? Math.round(v * 0x8000) : Math.round(v * 0x7fff);
    if (this.outPos === this.chunkSize) {
      const pcm = this.out;
      this.port.postMessage({ type: 'pcm', pcm }, [pcm.buffer]);
      this.out = new Int16Array(this.chunkSize);
      this.outPos = 0;
    }
  }

  process(inputs) {
    if (!this.running) return false;
    const input = inputs[0];
    const ch0 = input && input.length ? input[0] : null;
    const ch1 = input && input.length > 1 ? input[1] : null;
    const n = ch0 ? ch0.length : 128;
    for (let i = 0; i < n; i++) {
      let x = ch0 ? (ch1 ? (ch0[i] + ch1[i]) * 0.5 : ch0[i]) : 0;
      this.levelSum += x * x;
      if (++this.levelN >= this.levelEvery) {
        this.port.postMessage({ type: 'level', rms: Math.sqrt(this.levelSum / this.levelN) });
        this.levelSum = 0;
        this.levelN = 0;
      }
      if (this.down) {
        for (let f = 0; f < this.filters.length; f++) x = this.filters[f].process(x);
        this.acc += x;
        this.accN++;
        this.phase += this.ratio;
        if (this.phase >= 1) {
          this.phase -= 1;
          this.emit(this.acc / this.accN);
          this.acc = 0;
          this.accN = 0;
        }
      } else {
        // Upsampling (target rate >= context rate): linear interpolation between prev and x.
        const step = 1 / this.ratio;
        while (this.phase < 1) {
          this.emit(this.prev + (x - this.prev) * this.phase);
          this.phase += step;
        }
        this.phase -= 1;
        this.prev = x;
      }
    }
    return true;
  }
}

class PlaybackProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const o = (options && options.processorOptions) || {};
    this.srcRate = o.sourceRate || 8000;
    this.step = this.srcRate / sampleRate; // source samples per output sample
    let cap = 1;
    while (cap < this.srcRate * 2) cap <<= 1; // >= 2 s of audio
    this.buf = new Float32Array(cap);
    this.mask = cap - 1;
    this.cap = cap;
    this.r = 0; // absolute read index (integer part)
    this.w = 0; // absolute write index
    this.frac = 0;
    this.target = Math.max(2, Math.round((this.srcRate * (o.targetMs || 80)) / 1000));
    this.max = Math.max(this.target * 2, Math.round((this.srcRate * (o.maxMs || 200)) / 1000));
    this.playing = false;
    this.running = true;
    this.underruns = 0;
    this.dropped = 0;
    this.received = 0;
    this.levelSum = 0;
    this.levelN = 0;
    this.statsEvery = Math.round(sampleRate * 0.25);
    this.port.onmessage = (e) => {
      const d = e.data || {};
      if (d.stop) {
        this.running = false;
        return;
      }
      if (d.flush) {
        this.r = this.w;
        this.frac = 0;
        this.playing = false;
      }
      if (d.pcm) this.push(d.pcm);
    };
  }

  push(pcm) {
    const buf = this.buf;
    const mask = this.mask;
    let w = this.w;
    for (let i = 0; i < pcm.length; i++) buf[w++ & mask] = pcm[i] / 32768;
    this.w = w;
    this.received += pcm.length;
    if (this.w - this.r > this.cap) this.r = this.w - this.cap;
    const avail = this.w - this.r;
    if (avail > this.max) {
      const drop = avail - this.target;
      this.r += drop;
      this.dropped += drop;
    }
  }

  process(_inputs, outputs) {
    if (!this.running) return false;
    const output = outputs[0];
    const out = output[0];
    const n = out.length;
    let i = 0;
    if (!this.playing && this.w - this.r >= this.target) this.playing = true;
    if (this.playing) {
      const buf = this.buf;
      const mask = this.mask;
      for (; i < n; i++) {
        if (this.w - this.r < 2) {
          this.playing = false;
          this.underruns++;
          break;
        }
        const a = buf[this.r & mask];
        const b = buf[(this.r + 1) & mask];
        const s = a + (b - a) * this.frac;
        out[i] = s;
        this.levelSum += s * s;
        this.frac += this.step;
        while (this.frac >= 1) {
          this.frac -= 1;
          this.r++;
        }
      }
    }
    for (; i < n; i++) out[i] = 0;
    for (let c = 1; c < output.length; c++) output[c].set(out);
    this.levelN += n;
    if (this.levelN >= this.statsEvery) {
      this.port.postMessage({
        type: 'stats',
        rms: Math.sqrt(this.levelSum / this.levelN),
        bufferedMs: ((this.w - this.r) * 1000) / this.srcRate,
        underruns: this.underruns,
        droppedSamples: this.dropped,
        receivedSamples: this.received,
      });
      this.levelSum = 0;
      this.levelN = 0;
    }
    return true;
  }
}

registerProcessor('capture-processor', CaptureProcessor);
registerProcessor('playback-processor', PlaybackProcessor);
