<script lang="ts">
  import { localImageSource } from '../lib/images';
  import RemoteImage from './RemoteImage.svelte';
  let { src, path }: { src: string; path: string } = $props();
  let viewport = $state<HTMLDivElement>();
  let naturalWidth = $state(0),
    naturalHeight = $state(0),
    width = $state(1),
    height = $state(1);
  let zoom = $state(1),
    x = $state(0),
    y = $state(0),
    error = $state('');
  let dragging = $state(false),
    lastX = 0,
    lastY = 0;
  const fitScale = $derived(
    naturalWidth ? Math.min(1, width / naturalWidth, height / naturalHeight) : 1,
  );
  const scale = $derived(fitScale * zoom);
  function fit() {
    zoom = 1;
    x = 0;
    y = 0;
  }
  function changeZoom(factor: number) {
    zoom = Math.max(0.1, Math.min(32, zoom * factor));
  }
  $effect(() => {
    src;
    naturalWidth = 0;
    naturalHeight = 0;
    error = '';
    fit();
  });
  function down(event: PointerEvent) {
    if (event.button !== 0) return;
    dragging = true;
    lastX = event.clientX;
    lastY = event.clientY;
    viewport?.setPointerCapture(event.pointerId);
  }
  function move(event: PointerEvent) {
    if (!dragging) return;
    x += event.clientX - lastX;
    y += event.clientY - lastY;
    lastX = event.clientX;
    lastY = event.clientY;
  }
</script>

{#if !localImageSource(src)}<RemoteImage url={src} />{:else}
{#if error}<p role="alert">{error}</p>{/if}
<!-- The image surface supports wheel zoom, pointer pan and keyboard controls. -->
<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
<div
  class="image-viewport"
  class:dragging
  bind:this={viewport}
  bind:clientWidth={width}
  bind:clientHeight={height}
  role="application"
  aria-label={`Image preview: ${path}`}
  tabindex="0"
  ondblclick={fit}
  onpointerdown={down}
  onpointermove={move}
  onpointerup={() => (dragging = false)}
  onlostpointercapture={() => (dragging = false)}
  onwheel={(event) => {
    event.preventDefault();
    changeZoom(event.deltaY < 0 ? 1.15 : 1 / 1.15);
  }}
  onkeydown={(event) => {
    if (
      !['+', '=', '-', '0', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)
    )
      return;
    event.preventDefault();
    if (event.key === '+' || event.key === '=') changeZoom(1.25);
    else if (event.key === '-') changeZoom(1 / 1.25);
    else if (event.key === '0') fit();
    else {
      x += event.key === 'ArrowLeft' ? 40 : event.key === 'ArrowRight' ? -40 : 0;
      y += event.key === 'ArrowUp' ? 40 : event.key === 'ArrowDown' ? -40 : 0;
    }
  }}
>
  <img
    {src}
    alt={path}
    draggable="false"
    style:width={`${naturalWidth}px`}
    style:height={`${naturalHeight}px`}
    style:transform={`translate(-50%, -50%) translate(${x}px, ${y}px) scale(${scale})`}
    onload={(event) => {
      naturalWidth = (event.currentTarget as HTMLImageElement).naturalWidth;
      naturalHeight = (event.currentTarget as HTMLImageElement).naturalHeight;
    }}
    onerror={() =>
      (error = 'The image could not be displayed. Check the file or link and try again.')}
  />
</div>
{/if}

<style>
  .image-viewport {
    position: relative;
    height: min(65vh, 640px);
    min-height: 240px;
    overflow: hidden;
    background: #16181c;
    border: 1px solid var(--border);
    border-radius: 6px;
    cursor: grab;
    touch-action: none;
  }
  .image-viewport.dragging {
    cursor: grabbing;
  }
  .image-viewport:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  img {
    position: absolute;
    top: 50%;
    left: 50%;
    max-width: none;
    max-height: none;
    pointer-events: none;
    transform-origin: center;
  }
</style>
