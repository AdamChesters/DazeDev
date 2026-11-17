<script lang="ts">
  import { onMount } from 'svelte';
  import { borderPoint } from '../lib/energy-motion';
  import type { RecordingHighlight } from '../lib/highlights';
  import { Meter, measure, triggerOffset, waveShape, type WaveReader } from '../lib/mic-level';
  // The live microphone waveform drawn around the pane being dictated into, in the colours of
  // the chosen recording highlight. A glowing band grows inward from the border with the voice
  // and lies flat in silence, so it is obvious whether the microphone hears anything.
  let { read, kind }: { read: WaveReader; kind: RecordingHighlight } = $props();
  let canvas: HTMLCanvasElement;
  const spacing = 3;
  /** Samples per point: a 150 Hz voice then makes a wave about 140px long on any pane. */
  const density = 2.3;
  const rainbow = [
    '#ff58d6',
    '#965cff',
    '#428bff',
    '#44ecff',
    '#55f4a5',
    '#f9ed65',
    '#ff9951',
    '#ff58d6',
  ];

  onMount(() => {
    const context = canvas.getContext('2d');
    if (!context) return;
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    const meter = new Meter();
    let width = 0;
    let height = 0;
    /** How far the loudest wave reaches into the pane. */
    let reach = 0;
    let x = new Float32Array(0);
    let y = new Float32Array(0);
    let nx = new Float32Array(0);
    let ny = new Float32Array(0);
    /** Fades the wave out towards each corner, where the bands of two edges would overlap. */
    let fade = new Float32Array(0);
    let shape = new Float32Array(0);
    let next = new Float32Array(0);
    let plain = '';
    let frame = 0;
    let steadyAt = 0;

    const size = () => {
      const ratio = window.devicePixelRatio || 1;
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      if (width < 40 || height < 40) return;
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      const style = getComputedStyle(canvas);
      plain = style.color;
      reach = Math.max(12, Math.min(30, Math.min(width, height) * 0.045));
      const radius = parseFloat(style.borderTopLeftRadius) || 7;
      const count = Math.max(8, Math.round((2 * (width + height)) / spacing));
      x = new Float32Array(count);
      y = new Float32Array(count);
      nx = new Float32Array(count);
      ny = new Float32Array(count);
      fade = new Float32Array(count);
      shape = new Float32Array(count);
      next = new Float32Array(count);
      for (let i = 0; i < count; i++) {
        const p = borderPoint(width, height, radius, i / count);
        x[i] = p.x;
        y[i] = p.y;
        // The inward normal. The wave only grows into the pane, never over its neighbours.
        nx[i] = -Math.sin(p.angle);
        ny[i] = Math.cos(p.angle);
        const corner = Math.max(Math.min(p.x, width - p.x), Math.min(p.y, height - p.y));
        fade[i] = Math.min(1, corner / (reach * 1.5));
      }
    };

    const colors = (now: number): [string | CanvasGradient, string | CanvasGradient] => {
      if (kind === 'energy') return ['#30b9ff', '#d6f5ff'];
      if (kind === 'static') return [plain, plain];
      const gradient = context.createConicGradient
        ? context.createConicGradient((now / 3200) * Math.PI * 2, width / 2, height / 2)
        : context.createLinearGradient(0, 0, width, height);
      rainbow.forEach((color, i) => gradient.addColorStop(i / (rainbow.length - 1), color));
      return [gradient, gradient];
    };

    const loop = (wave: boolean) => {
      for (let i = 0; i < x.length; i++) {
        const d = wave ? 1 + shape[i] * reach * fade[i] : 0;
        const px = x[i] + nx[i] * d;
        const py = y[i] + ny[i] * d;
        if (i) context.lineTo(px, py);
        else context.moveTo(px, py);
      }
      context.closePath();
    };

    const draw = (now: number, level: number) => {
      context.clearRect(0, 0, width, height);
      if (!x.length) return;
      const [glow, core] = colors(now);
      // The band between the border and the wave.
      context.beginPath();
      loop(false);
      loop(true);
      context.globalAlpha = 0.2 + 0.4 * level;
      context.fillStyle = glow;
      context.fill('evenodd');
      // The bright edge of the wave.
      context.beginPath();
      loop(true);
      context.lineJoin = 'round';
      context.globalAlpha = 0.65 + 0.35 * level;
      context.lineWidth = 1.5 + 1.5 * level;
      context.strokeStyle = core;
      context.stroke();
      context.globalAlpha = 1;
    };

    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const samples = read();
      meter.update(samples ? measure(samples) : null, now);
      const choice = document.documentElement.dataset.highlightMotion;
      const moving = choice !== 'static' && (choice === 'animate' || !media.matches);
      if (moving) {
        // Start on a zero crossing so a held note stands still, then draw the rest of the buffer.
        const from = samples ? triggerOffset(samples, samples.length >> 2) : 0;
        const window = samples ? samples.length - (samples.length >> 2) : 0;
        // The 0.7 power lifts quiet speech, so any voice at all moves the border visibly.
        waveShape(
          samples ? samples.subarray(from, from + window) : [],
          meter.level ** 0.7,
          next,
          density,
        );
        // Height only, blended with the last frame to take the flicker off.
        for (let i = 0; i < shape.length; i++) shape[i] += (Math.abs(next[i]) - shape[i]) * 0.7;
        draw(now, meter.level);
      } else if (now - steadyAt > 200) {
        // Reduced motion: no moving wave. An even band thickens and brightens with the voice.
        steadyAt = now;
        shape.fill(meter.level * 0.5);
        draw(now, meter.level);
      }
    };

    const resize = new ResizeObserver(size);
    resize.observe(canvas);
    size();
    frame = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(frame);
      resize.disconnect();
    };
  });
</script>

<canvas bind:this={canvas} class="voice-border-wave" aria-hidden="true"></canvas>

<style>
  .voice-border-wave {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    border-radius: inherit;
    pointer-events: none;
    z-index: 9;
    color: var(--voice-highlight-color, var(--highlight));
  }
</style>
