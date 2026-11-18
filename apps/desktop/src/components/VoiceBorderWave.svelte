<script lang="ts">
  import { onMount } from 'svelte';
  import { borderPoint } from '../lib/energy-motion';
  import type { RecordingHighlight } from '../lib/highlights';
  import {
    Meter,
    measure,
    triggerOffset,
    waveDensity,
    waveShape,
    type LevelReader,
    type WaveReader,
  } from '../lib/mic-level';
  import { MoteField, moteCapacity } from '../lib/voice-motes';
  // The live microphone waveform shown around the pane being dictated into, in the colours of
  // the chosen recording highlight. The voice throws small glowing motes off the border, more
  // of them where the wave is tall, and in silence there are none, so it is obvious whether the
  // microphone hears anything.
  // A terminal window passes received samples at a lower rate, with a matching `density` and
  // the level measured before they were packed (`readLevel`).
  let {
    read,
    kind,
    readLevel,
    density = waveDensity,
  }: {
    read: WaveReader;
    kind: RecordingHighlight;
    readLevel?: LevelReader;
    density?: number;
  } = $props();
  let canvas: HTMLCanvasElement;
  const spacing = 3;
  /** Pixels between the still dots shown when motion is off. */
  const stillSpacing = 15;
  const rainbow = ['#ff58d6', '#965cff', '#428bff', '#44ecff', '#55f4a5', '#f9ed65', '#ff9951'];

  /** The lighter centre of a mote in the rainbow and plain colours. */
  const pale = 'rgba(255,255,255,0.7)';

  /** A soft round dot of one colour with a lighter centre, drawn once and stamped for every mote. */
  function sprite(color: string, core: string) {
    const dot = document.createElement('canvas');
    dot.width = dot.height = 32;
    const context = dot.getContext('2d');
    if (!context) return dot;
    const soft = context.createRadialGradient(16, 16, 0, 16, 16, 16);
    soft.addColorStop(0, 'rgba(0,0,0,1)');
    soft.addColorStop(0.3, 'rgba(0,0,0,0.95)');
    soft.addColorStop(0.55, 'rgba(0,0,0,0.4)');
    soft.addColorStop(1, 'rgba(0,0,0,0)');
    context.fillStyle = soft;
    context.fillRect(0, 0, 32, 32);
    context.globalCompositeOperation = 'source-in';
    context.fillStyle = color;
    context.fillRect(0, 0, 32, 32);
    const centre = context.createRadialGradient(16, 16, 0, 16, 16, 6);
    centre.addColorStop(0, core);
    centre.addColorStop(1, 'rgba(255,255,255,0)');
    context.globalCompositeOperation = 'source-atop';
    context.fillStyle = centre;
    context.fillRect(0, 0, 32, 32);
    return dot;
  }

  onMount(() => {
    const context = canvas.getContext('2d');
    if (!context) return;
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    const meter = new Meter();
    let width = 0;
    let height = 0;
    /** How far the fastest mote of the loudest wave travels into the pane. */
    let reach = 0;
    let perimeter = 0;
    let x = new Float32Array(0);
    let y = new Float32Array(0);
    let nx = new Float32Array(0);
    let ny = new Float32Array(0);
    /** Thins the motes out towards each corner, where those of two edges would overlap. */
    let fade = new Float32Array(0);
    /** Where each point sits round the pane centre (0..1), for the rainbow colours. */
    let turn = new Float32Array(0);
    let shape = new Float32Array(0);
    let next = new Float32Array(0);
    let tall = new Float32Array(0);
    let motes = new MoteField(0);
    let dots: HTMLCanvasElement[] = [];
    let dotsFor: RecordingHighlight | undefined;
    let frame = 0;
    let last = 0;
    let steadyAt = 0;

    const colour = () => {
      dotsFor = kind;
      dots =
        kind === 'energy'
          ? [sprite('#46c2ff', '#edfbff')]
          : kind === 'static'
            ? [sprite(getComputedStyle(canvas).color, pale)]
            : rainbow.map((color) => sprite(color, pale));
    };

    const size = () => {
      const ratio = window.devicePixelRatio || 1;
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      if (width < 40 || height < 40) return;
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      const style = getComputedStyle(canvas);
      colour();
      reach = Math.max(8, Math.min(18, Math.min(width, height) * 0.028));
      const radius = parseFloat(style.borderTopLeftRadius) || 7;
      perimeter = 2 * (width + height);
      const count = Math.max(8, Math.round(perimeter / spacing));
      x = new Float32Array(count);
      y = new Float32Array(count);
      nx = new Float32Array(count);
      ny = new Float32Array(count);
      fade = new Float32Array(count);
      turn = new Float32Array(count);
      shape = new Float32Array(count);
      next = new Float32Array(count);
      tall = new Float32Array(count);
      motes = new MoteField(moteCapacity(perimeter));
      for (let i = 0; i < count; i++) {
        const p = borderPoint(width, height, radius, i / count);
        x[i] = p.x;
        y[i] = p.y;
        // The inward normal. Motes only drift into the pane, never over its neighbours.
        nx[i] = -Math.sin(p.angle);
        ny[i] = Math.cos(p.angle);
        const corner = Math.max(Math.min(p.x, width - p.x), Math.min(p.y, height - p.y));
        fade[i] = Math.min(1, corner / (reach * 1.5));
        turn[i] = Math.atan2(p.y - height / 2, p.x - width / 2) / (Math.PI * 2);
      }
    };

    /** The dot for border point `i`. The rainbow turns at the pace of the rainbow border. */
    const dot = (i: number, now: number) => {
      if (dots.length < 2) return dots[0];
      const position = (((turn[i] - now / 3200) % 1) + 1) % 1;
      return dots[Math.floor(position * dots.length) % dots.length];
    };

    const stamp = (i: number, now: number, px: number, py: number, radius: number) => {
      // The dot image is half soft edge: its bright centre is about a third of its width.
      const half = radius * 2.4;
      context.drawImage(dot(i, now), px - half, py - half, half * 2, half * 2);
    };

    const draw = (now: number) => {
      context.clearRect(0, 0, width, height);
      for (let m = 0; m < motes.capacity; m++) {
        const opacity = motes.opacity(m);
        if (opacity <= 0) continue;
        const i = motes.point[m];
        const inward = 1 + motes.depth(m) * reach;
        const along = motes.drift[m] * motes.age[m];
        context.globalAlpha = opacity;
        stamp(
          i,
          now,
          x[i] + nx[i] * inward - ny[i] * along,
          y[i] + ny[i] * inward + nx[i] * along,
          1.1 + 1.6 * motes.size[m],
        );
      }
      context.globalAlpha = 1;
    };

    /** Motion off: fixed dots along the border that only brighten with the voice. */
    const drawStill = (now: number, level: number) => {
      context.clearRect(0, 0, width, height);
      if (level <= 0) return;
      context.globalAlpha = 0.1 + 0.55 * level;
      const step = Math.max(1, Math.round(stillSpacing / spacing));
      for (let i = 0; i < x.length; i += step)
        stamp(i, now, x[i] + nx[i] * 2, y[i] + ny[i] * 2, 1.4);
      context.globalAlpha = 1;
    };

    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const elapsed = last ? Math.min(100, Math.max(0, now - last)) : 0;
      last = now;
      const samples = read();
      meter.update(readLevel ? readLevel() : samples ? measure(samples) : null, now);
      if (!x.length) return;
      if (kind !== dotsFor) colour();
      const choice = document.documentElement.dataset.highlightMotion;
      const moving = choice !== 'static' && (choice === 'animate' || !media.matches);
      if (moving) {
        // Start on a zero crossing so a held note stands still, then use the rest of the buffer.
        const from = samples ? triggerOffset(samples, samples.length >> 2) : 0;
        const window = samples ? samples.length - (samples.length >> 2) : 0;
        // The 0.7 power lifts quiet speech, so any voice at all throws motes off the border.
        const level = meter.level ** 0.7;
        waveShape(samples ? samples.subarray(from, from + window) : [], level, next, density);
        // Height only, blended with the last frame to take the flicker off.
        for (let i = 0; i < shape.length; i++) {
          shape[i] += (Math.abs(next[i]) - shape[i]) * 0.7;
          tall[i] = shape[i] * fade[i];
        }
        motes.step(tall, level, elapsed, perimeter);
        draw(now);
      } else if (now - steadyAt > 200) {
        // Reduced motion: nothing moves.
        steadyAt = now;
        motes.clear();
        drawStill(now, meter.level);
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
