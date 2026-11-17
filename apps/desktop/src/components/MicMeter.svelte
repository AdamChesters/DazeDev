<script lang="ts">
  import { onMount } from 'svelte';
  import { levelAdvice, Meter, type LevelReader } from '../lib/mic-level';
  // A horizontal microphone level meter with a peak marker and a clipping warning.
  let {
    read,
    label = 'Microphone level',
    advice = false,
  }: { read: LevelReader; label?: string; advice?: boolean } = $props();
  let fill: HTMLElement;
  let peak: HTMLElement;
  let percent = $state(0);
  let clipped = $state(false);
  let text = $state('');

  onMount(() => {
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    const meter = new Meter();
    let frame = 0;
    let paintedAt = 0;
    let reportedAt = 0;
    // Loudest recent level, falling 6 dB a second, so the advice does not flicker between words.
    let heard = -Infinity;
    let last = 0;
    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const raw = read();
      meter.update(raw, now);
      const seconds = last ? (now - last) / 1000 : 0;
      last = now;
      heard = Math.max(meter.db, heard - 6 * seconds);
      const choice = document.documentElement.dataset.highlightMotion;
      const moving = choice !== 'static' && (choice === 'animate' || !media.matches);
      if (moving || now - paintedAt > 160) {
        paintedAt = now;
        fill.style.clipPath = `inset(0 ${(100 - meter.level * 100).toFixed(1)}% 0 0)`;
        peak.style.left = `${(meter.peak * 100).toFixed(1)}%`;
      }
      // Text and the accessible value change a few times a second, not every frame.
      if (now - reportedAt > 250) {
        reportedAt = now;
        percent = Math.round(meter.level * 100);
        clipped = meter.clipped;
        text = advice && raw ? levelAdvice(heard, meter.clipped) : '';
      }
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  });
</script>

<div class="mic-meter" class:clipped>
  <div
    class="track"
    role="meter"
    aria-label={label}
    aria-valuemin="0"
    aria-valuemax="100"
    aria-valuenow={percent}
    aria-valuetext={clipped ? 'Clipping' : `${percent} percent`}
  >
    <i class="fill" bind:this={fill}></i><i class="peak" bind:this={peak}></i>
  </div>
  {#if advice}<p class="hint" role="status">{text}</p>{/if}
</div>

<style>
  .mic-meter {
    flex: 1 1 100%;
    min-width: 0;
  }
  .track {
    position: relative;
    height: 10px;
    border: 1px solid var(--line);
    border-radius: 5px;
    overflow: hidden;
    background: var(--shade);
  }
  .fill {
    position: absolute;
    inset: 0;
    clip-path: inset(0 100% 0 0);
    /* The scale is 60 dB wide, so the red end is the last 6 dB before clipping. */
    background: linear-gradient(to right, var(--ok) 0 70%, var(--warning) 85%, var(--held-bg) 95%);
  }
  .peak {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 2px;
    margin-left: -2px;
    background: var(--text, currentColor);
  }
  .clipped .track {
    border-color: var(--held-bg);
    box-shadow: 0 0 0 1px var(--held-bg);
  }
  .hint {
    min-height: 1.4em;
    margin: 4px 0 0;
  }
</style>
