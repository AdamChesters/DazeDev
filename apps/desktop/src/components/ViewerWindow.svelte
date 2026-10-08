<script lang="ts">
  import { onMount, type Snippet } from 'svelte';
  import { X, Maximize2, Minimize2, Plus } from '@lucide/svelte';
  let {
    children,
    locked,
    onclose,
    title = 'Files and previews',
    offset = 0,
    layer = 0,
    onactivate = () => {},
    onnew = () => {},
    tileId = '',
    dockRect,
    hidden = false,
    maximized = false,
    onmaximize = () => {},
    ondrag = () => {},
  }: {
    children: Snippet;
    locked: boolean;
    onclose: () => void;
    title?: string;
    offset?: number;
    layer?: number;
    onactivate?: () => void;
    onnew?: () => void;
    tileId?: string;
    dockRect?: { x: number; y: number; width: number; height: number };
    hidden?: boolean;
    maximized?: boolean;
    onmaximize?: () => void;
    ondrag?: (event: PointerEvent) => void;
  } = $props();
  let expanded = $state(false);
  const docked = $derived(!!dockRect);
  const full = $derived(docked ? maximized : expanded);
  const bounds = $derived(docked && maximized ? { x: 0, y: 0, width: 100, height: 100 } : dockRect);
  let box: HTMLElement;
  let rect = $state({ x: 50, y: 50, width: 1000, height: 740 });
  let dragging: { id: number; x: number; y: number; start: typeof rect } | null = null;
  function clamp() {
    rect.width = Math.min(rect.width, innerWidth - 16);
    rect.height = Math.min(rect.height, innerHeight - 16);
    rect.x = Math.max(8, Math.min(rect.x, innerWidth - rect.width - 8));
    rect.y = Math.max(8, Math.min(rect.y, innerHeight - 48));
  }
  function rememberSize() {
    const r = box.getBoundingClientRect();
    rect = { x: r.x, y: r.y, width: r.width, height: r.height };
  }
  function toggle() {
    if (docked) {
      onmaximize();
      return;
    }
    if (!expanded) rememberSize();
    expanded = !expanded;
    if (!expanded) clamp();
  }
  function start(e: PointerEvent) {
    if (e.button !== 0 || (e.target as HTMLElement).closest('button')) return;
    if (docked) {
      ondrag(e);
      return;
    }
    if (expanded) return;
    rememberSize();
    dragging = { id: e.pointerId, x: e.clientX, y: e.clientY, start: { ...rect } };
    (e.currentTarget as HTMLElement).focus();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    e.preventDefault();
  }
  function move(e: PointerEvent) {
    if (!dragging || dragging.id !== e.pointerId) return;
    rect.x = dragging.start.x + e.clientX - dragging.x;
    rect.y = dragging.start.y + e.clientY - dragging.y;
    clamp();
  }
  function key(e: KeyboardEvent) {
    if (e.key === 'Escape' && dragging) {
      rect = dragging.start;
      dragging = null;
      e.stopPropagation();
    }
    if (e.target !== e.currentTarget) return;
    if (e.key === 'Enter') {
      toggle();
      e.preventDefault();
    }
    if (
      !docked &&
      !expanded &&
      ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)
    ) {
      rect.x += e.key === 'ArrowLeft' ? -20 : e.key === 'ArrowRight' ? 20 : 0;
      rect.y += e.key === 'ArrowUp' ? -20 : e.key === 'ArrowDown' ? 20 : 0;
      clamp();
      e.preventDefault();
    }
  }
  onMount(() => {
    rect = {
      x: Math.max(8, (innerWidth - 1100) / 2) + offset * 24,
      y: 32 + offset * 24,
      width: Math.min(1100, innerWidth - 32),
      height: innerHeight - 64,
    };
    clamp();
    const resized = () => {
      if (!docked && !expanded) {
        rememberSize();
        clamp();
      }
    };
    window.addEventListener('resize', resized);
    return () => window.removeEventListener('resize', resized);
  });
</script>

<div
  bind:this={box}
  class="viewer-window"
  class:expanded={full}
  class:docked
  class:viewer-hidden={hidden}
  data-pane-id={docked ? tileId : undefined}
  role={docked ? 'region' : 'dialog'}
  tabindex="-1"
  aria-label={title}
  onpointerdown={onactivate}
  onfocusin={onactivate}
  style:z-index={docked ? 1 : layer}
  aria-modal={docked ? undefined : false}
  aria-hidden={hidden}
  inert={hidden}
  style:left={bounds ? `calc(${bounds.x}% + 4px)` : expanded ? '8px' : `${rect.x}px`}
  style:top={bounds ? `calc(${bounds.y}% + 2px)` : expanded ? '8px' : `${rect.y}px`}
  style:width={bounds
    ? `calc(${bounds.width}% - 8px)`
    : expanded
      ? 'calc(100vw - 16px)'
      : `${rect.width}px`}
  style:height={bounds
    ? `calc(${bounds.height}% - 4px)`
    : expanded
      ? 'calc(100dvh - 16px)'
      : `${rect.height}px`}
>
  <div
    class="viewer-title pane-header"
    role="toolbar"
    tabindex="0"
    aria-label={docked
      ? 'Drag viewer to dock; Enter or double-click to expand or restore'
      : 'Move viewer with arrow keys; Enter or double-click to expand or restore'}
    onpointerdown={start}
    onpointermove={move}
    onpointerup={() => (dragging = null)}
    onpointercancel={() => {
      if (dragging) rect = dragging.start;
      dragging = null;
    }}
    onkeydown={key}
    ondblclick={(e) => {
      if (!(e.target as HTMLElement).closest('button')) toggle();
    }}
  >
    <h2 {title}>{title}</h2>
    <button class:icon={docked} aria-label="New viewer" title="New viewer" onclick={onnew}
      >{#if docked}<Plus size={16} />{:else}New viewer{/if}</button
    >
    <button class="icon" aria-label={full ? 'Restore viewer' : 'Expand viewer'} onclick={toggle}
      >{#if full}<Minimize2 size={16} />{:else}<Maximize2 size={16} />{/if}</button
    >
    <button class="icon" aria-label="Close viewer" disabled={locked} onclick={onclose}
      ><X size={18} /></button
    >
  </div>
  <div class="viewer-content">{@render children()}</div>
</div>

<style>
  .viewer-window {
    position: fixed;
    z-index: 18;
    display: flex;
    flex-direction: column;
    max-width: calc(100vw - 16px);
    max-height: calc(100dvh - 16px);
    min-width: min(480px, calc(100vw - 16px));
    min-height: min(300px, calc(100dvh - 16px));
    overflow: hidden;
    resize: both;
    background: var(--tint-deep, #1c1f1a);
    border: 1px solid var(--tint-border, #4b5543);
    border-radius: 12px;
    box-shadow: 0 20px 70px #0008;
  }
  .viewer-window.docked {
    position: absolute;
    min-width: 0;
    min-height: 0;
    max-width: none;
    max-height: none;
    resize: none;
    border-radius: 8px;
    box-shadow: none;
  }
  .viewer-window.viewer-hidden {
    display: none;
  }
  .docked .viewer-title {
    gap: 4px;
    padding: 4px 6px;
    min-height: 32px;
    flex-wrap: nowrap;
  }
  .docked h2 {
    font-size: 13px;
  }
  .docked .viewer-title button {
    flex-shrink: 0;
  }
  .docked .viewer-content {
    padding: 6px;
    min-width: 0;
  }
  .viewer-title:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
  .expanded {
    resize: none;
  }
  .viewer-title {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    cursor: move;
    touch-action: none;
    user-select: none;
    border-bottom: 1px solid var(--tint-border);
  }
  h2 {
    margin: 0;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 16px;
    font-weight: 500;
  }
  .viewer-content {
    overflow: auto;
    min-height: 0;
    padding: 16px;
    flex: 1;
  }
</style>
