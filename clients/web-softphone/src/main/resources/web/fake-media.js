// Synthetic media sources, used with ?fake=1 or when no camera/microphone is available.

/** Tone frequency for the synthetic microphone: 440 Hz for "alice"-like users, a different pitch otherwise. */
export function toneForUser(user) {
  const u = String(user || '').toLowerCase();
  if (!u || u.startsWith('a')) return 440;
  if (u.startsWith('b')) return 660;
  const options = [523.25, 587.33, 698.46, 783.99, 880];
  let h = 0;
  for (const ch of u) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return options[h % options.length];
}

/**
 * Synthetic microphone: a sine tone that beeps (0.8 s on / 0.2 s off) so it is recognisable.
 * Returns {node, stop} where node is an AudioNode to connect to the capture worklet.
 */
export function createFakeMic(ctx, freq) {
  const osc = ctx.createOscillator();
  osc.type = 'sine';
  osc.frequency.value = freq;
  const gain = ctx.createGain();
  gain.gain.value = 0.25;
  // Square LFO (1 Hz) driving the gain gives a beep pattern.
  const lfo = ctx.createOscillator();
  lfo.type = 'square';
  lfo.frequency.value = 1;
  const lfoGain = ctx.createGain();
  lfoGain.gain.value = 0.12;
  lfo.connect(lfoGain).connect(gain.gain);
  osc.connect(gain);
  osc.start();
  lfo.start();
  return {
    node: gain,
    stop() {
      for (const n of [osc, lfo]) {
        try { n.stop(); } catch (_) { /* already stopped */ }
      }
      for (const n of [osc, lfo, lfoGain, gain]) {
        try { n.disconnect(); } catch (_) { /* not connected */ }
      }
    },
  };
}

const BARS = ['#c0c0c0', '#c0c000', '#00c0c0', '#00c000', '#c000c0', '#c00000', '#0000c0'];

function pad(n, w = 2) {
  return String(n).padStart(w, '0');
}

/** Animated test pattern: moving colour bars, a bouncing ball, a clock and the local user name. */
export function drawTestPattern(g, w, h, { name = '', frame = 0, now = Date.now() } = {}) {
  const t = now / 1000;
  // Moving colour bars across the top two thirds.
  const barW = w / BARS.length;
  const offset = (frame * 4) % w;
  const barsH = Math.round(h * 0.62);
  for (let i = 0; i < BARS.length + 1; i++) {
    g.fillStyle = BARS[i % BARS.length];
    const x = ((i * barW + offset) % (w + barW)) - barW;
    g.fillRect(Math.floor(x), 0, Math.ceil(barW) + 1, barsH);
  }
  // Grey ramp.
  const grad = g.createLinearGradient(0, 0, w, 0);
  grad.addColorStop(0, '#000');
  grad.addColorStop(1, '#fff');
  g.fillStyle = grad;
  g.fillRect(0, barsH, w, Math.round(h * 0.08));
  // Info area.
  const infoY = barsH + Math.round(h * 0.08);
  g.fillStyle = '#101822';
  g.fillRect(0, infoY, w, h - infoY);
  // Bouncing ball.
  const bx = w / 2 + Math.sin(t * 1.7) * (w / 2 - 40);
  const by = barsH / 2 + Math.cos(t * 2.3) * (barsH / 2 - 40);
  g.beginPath();
  g.arc(bx, by, 28, 0, Math.PI * 2);
  g.fillStyle = '#ffffff';
  g.fill();
  g.lineWidth = 6;
  g.strokeStyle = '#ff3b6b';
  g.stroke();
  // Clock + name.
  const d = new Date(now);
  const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
  g.fillStyle = '#e8f0ff';
  g.textBaseline = 'middle';
  g.textAlign = 'left';
  g.font = `bold ${Math.round(h * 0.075)}px ui-monospace, Menlo, Consolas, monospace`;
  g.fillText(clock, 20, infoY + (h - infoY) * 0.33);
  g.font = `600 ${Math.round(h * 0.06)}px system-ui, sans-serif`;
  g.fillStyle = '#7dd3fc';
  g.fillText(name || 'softphone', 20, infoY + (h - infoY) * 0.72);
  g.textAlign = 'right';
  g.font = `${Math.round(h * 0.04)}px ui-monospace, Menlo, Consolas, monospace`;
  g.fillStyle = '#94a3b8';
  g.fillText(`TEST PATTERN  #${frame}`, w - 20, infoY + (h - infoY) * 0.72);
  g.textAlign = 'left';
}

/** Placeholder frame (camera off / camera starting). */
export function drawPlaceholder(g, w, h, title, name) {
  g.fillStyle = '#0d131b';
  g.fillRect(0, 0, w, h);
  g.fillStyle = '#1e293b';
  g.beginPath();
  g.arc(w / 2, h * 0.42, h * 0.16, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = '#94a3b8';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.font = `600 ${Math.round(h * 0.14)}px system-ui, sans-serif`;
  g.fillText((name || '?').trim().charAt(0).toUpperCase() || '?', w / 2, h * 0.42);
  g.font = `500 ${Math.round(h * 0.05)}px system-ui, sans-serif`;
  g.fillText(title, w / 2, h * 0.7);
  if (name) {
    g.fillStyle = '#64748b';
    g.font = `${Math.round(h * 0.04)}px system-ui, sans-serif`;
    g.fillText(name, w / 2, h * 0.78);
  }
  g.textAlign = 'left';
}
