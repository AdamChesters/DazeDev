<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { energyFrames, energyCount, makeEnergyMotes } from '../lib/energy-motion';
  let root: HTMLDivElement;
  let sprites = $state(
    makeEnergyMotes(24).flatMap((mote) => [0, 1, 2].map((kind) => ({ ...mote, kind }))),
  );
  onMount(() => {
    let animations: Animation[] = [];
    let width = 0,
      height = 0;
    let visible = true;
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => {
      const choice = document.documentElement.dataset.highlightMotion;
      const moving = choice !== 'static' && (choice === 'animate' || !media.matches);
      animations.forEach((a) => {
        if (moving && visible && !document.hidden) a.play();
        else a.pause();
      });
    };
    let revision = 0;
    const resize = new ResizeObserver(async ([entry]) => {
      const { width: w, height: h } = entry.contentRect;
      if (w === width && h === height) return;
      width = w;
      height = h;
      const current = ++revision;
      animations.forEach((a) => a.cancel());
      const count = energyCount(w, h);
      sprites = makeEnergyMotes(count).flatMap((mote) =>
        [0, 1, 2].map((kind) => ({
          ...mote,
          speed: mote.speed * Math.max(1, (2 * (w + h)) / 720),
          kind,
        })),
      );
      await tick();
      if (current !== revision) return;
      const radius = parseFloat(getComputedStyle(root).borderTopLeftRadius) || 8;
      animations = [...root.querySelectorAll('i')].map((node, i) => {
        const animation = node.animate(
          energyFrames(w, h, sprites[i].seed, radius, sprites[i].kind),
          {
            duration: sprites[i].speed * 1000,
            iterations: Infinity,
            easing: 'linear',
          },
        );
        animation.currentTime = (Math.floor(i / 3) / count) * sprites[i].speed * 1000;
        return animation;
      });
      update();
    });
    resize.observe(root);
    const observer = new MutationObserver(update);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-highlight-motion'],
    });
    const intersection = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      update();
    });
    intersection.observe(root);
    media.addEventListener('change', update);
    document.addEventListener('visibilitychange', update);
    return () => {
      revision++;
      animations.forEach((a) => a.cancel());
      resize.disconnect();
      observer.disconnect();
      intersection.disconnect();
      media.removeEventListener('change', update);
      document.removeEventListener('visibilitychange', update);
    };
  });
</script>

<div bind:this={root} class="energy-border" aria-hidden="true">
  {#each sprites as sprite}<i
      class:smoke={sprite.kind > 0}
      class:mote={sprite.kind === 0}
      style={`--speed:${sprite.speed}s`}
    ></i>{/each}
</div>

<style>
  .energy-border {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    pointer-events: none;
    z-index: 8;
  }
  i {
    position: absolute;
    left: 0;
    top: 0;
    width: 20px;
    height: 20px;
    transform-origin: center;
    will-change: transform, opacity;
    background: radial-gradient(circle, #edfbff 0%, #84deffcc 12%, #30b9ff66 30%, transparent 70%);
  }
  .smoke {
    background:
      radial-gradient(ellipse at 35% 40%, #70cfff88, #469eff22 35%, transparent 65%),
      radial-gradient(ellipse at 65% 65%, #8fdcff66, transparent 60%);
  }
</style>
