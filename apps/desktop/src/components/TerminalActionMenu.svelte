<script lang="ts">
  import { tick, onDestroy } from 'svelte';
  import { GripVertical, ArrowUp, ArrowDown, ArrowLeft, ArrowRight } from '@lucide/svelte';
  import {
    orderedTerminalMenu,
    moveTerminalMenuItem,
    type TerminalMenuItem,
    type TerminalMenuId,
  } from '../lib/terminal-menu';
  import type { Edge } from '../lib/layout';
  let {
    items,
    order,
    onorder,
    onsplit,
    transfers,
    ontransfer,
    transferDisabled = false,
  }: {
    items: TerminalMenuItem[];
    order?: string[];
    onorder: (order: TerminalMenuId[]) => void;
    onsplit: (edge: Edge) => void;
    transfers: { id: string; label: string }[];
    ontransfer: (id: string, duplicate: boolean) => void;
    transferDisabled?: boolean;
  } = $props();
  const visible = $derived(orderedTerminalMenu(items, order));
  let dragging = $state<TerminalMenuId | null>(null),
    over = $state<TerminalMenuId | null>(null),
    after = $state(false),
    announcement = $state('');
  let cancelDrag = () => {};
  onDestroy(() => cancelDrag());
  function move(source: TerminalMenuId, target: TerminalMenuId, placeAfter: boolean) {
    onorder(moveTerminalMenuItem(order, source, target, placeAfter));
    announcement = `${items.find((i) => i.id === source)?.label} moved ${placeAfter ? 'after' : 'before'} ${items.find((i) => i.id === target)?.label}.`;
    void tick().then(() =>
      document.querySelector<HTMLButtonElement>(`[data-menu-grip="${source}"]`)?.focus(),
    );
  }
  function key(event: KeyboardEvent, id: TerminalMenuId) {
    if (!event.altKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    event.stopPropagation();
    const index = visible.findIndex((item) => item.id === id),
      target = visible[index + (event.key === 'ArrowUp' ? -1 : 1)];
    if (target) move(id, target.id, event.key === 'ArrowDown');
  }
  function start(event: PointerEvent, id: TerminalMenuId) {
    if (event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    (event.currentTarget as HTMLButtonElement).focus();
    cancelDrag();
    const x = event.clientX,
      y = event.clientY,
      pointer = event.pointerId;
    const cleanup = () => {
      dragging = null;
      over = null;
      window.removeEventListener('pointermove', motion);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', abort);
      window.removeEventListener('keydown', escape, true);
      window.removeEventListener('blur', abort);
      cancelDrag = () => {};
    };
    const abort = () => cleanup();
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') cleanup();
    };
    const motion = (e: PointerEvent) => {
      if (e.pointerId !== pointer) return;
      if (!dragging && Math.hypot(e.clientX - x, e.clientY - y) < 5) return;
      dragging = id;
      const row = document
        .elementFromPoint(e.clientX, e.clientY)
        ?.closest<HTMLElement>('[data-menu-item]');
      over = (row?.dataset.menuItem as TerminalMenuId) || null;
      if (row) {
        const rect = row.getBoundingClientRect();
        after = e.clientY > rect.top + rect.height / 2;
      }
      const menu = row?.closest<HTMLElement>('.pane-menu');
      if (menu) {
        const rect = menu.getBoundingClientRect();
        if (e.clientY > rect.bottom - 24) menu.scrollTop += 14;
        else if (e.clientY < rect.top + 24) menu.scrollTop -= 14;
      }
    };
    const finish = (e: PointerEvent) => {
      if (e.pointerId !== pointer) return;
      motion(e);
      const target = over,
        active = dragging,
        placeAfter = after;
      cleanup();
      if (active && target && target !== active) move(id, target, placeAfter);
    };
    cancelDrag = cleanup;
    window.addEventListener('pointermove', motion);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', abort);
    window.addEventListener('keydown', escape, true);
    window.addEventListener('blur', abort);
  }
</script>

{#each visible as item (item.id)}
  <div
    class="action-row"
    class:dragging={dragging === item.id}
    class:drop-before={over === item.id && !after && dragging !== item.id}
    class:drop-after={over === item.id && after && dragging !== item.id}
    data-menu-item={item.id}
  >
    {#if item.id !== 'close'}<button
        class="reorder-grip"
        data-menu-grip={item.id}
        aria-label={`Reorder ${item.label}`}
        title="Drag to reorder. Keyboard: Alt+Up or Alt+Down."
        onpointerdown={(e) => start(e, item.id)}
        onkeydown={(e) => key(e, item.id)}><GripVertical size={13} /></button
      >{/if}
    {#if item.kind === 'split'}
      <details class="action-content split-submenu">
        <summary>Split</summary>
        <div class="split-diamond" aria-label="Split direction">
          <button class="up" onclick={() => onsplit('top')}><ArrowUp size={14} />Up</button>
          <button class="left" onclick={() => onsplit('left')}><ArrowLeft size={14} />Left</button>
          <button class="right" onclick={() => onsplit('right')}
            >Right<ArrowRight size={14} /></button
          >
          <button class="down" onclick={() => onsplit('bottom')}><ArrowDown size={14} />Down</button
          >
        </div>
      </details>
    {:else if item.kind === 'transfer'}
      <details class="action-content pane-transfer-menu">
        <summary>{item.label}</summary>
        {#each transfers as destination (destination.id)}<p>{destination.label}</p>
          <button disabled={transferDisabled} onclick={() => ontransfer(destination.id, false)}
            >Move here</button
          >
          <button disabled={transferDisabled} onclick={() => ontransfer(destination.id, true)}
            >Duplicate here</button
          >
        {/each}
      </details>
    {:else}<button class="action-content" disabled={item.disabled} onclick={item.run}
        >{item.label}</button
      >{/if}
  </div>
{/each}
<small class="reorder-hint">Drag grips to reorder · Alt+↑ / ↓ on a grip</small>
<span class="sr-only" role="status">{announcement}</span>

<style>
  .action-row {
    display: flex;
    align-items: flex-start;
    position: relative;
  }
  .action-content {
    flex: 1;
    min-width: 0;
  }
  .action-row .reorder-grip {
    width: 22px;
    min-width: 22px;
    padding: 8px 3px;
    justify-content: center;
    cursor: grab;
    touch-action: none;
    color: var(--muted);
    background: transparent;
  }
  .action-row button.action-content,
  .action-row summary {
    padding: 7px 8px;
    border: 0;
    border-radius: 6px;
    background: var(--raised);
    color: inherit;
    font: inherit;
    text-align: left;
    transition: background 0.12s;
  }
  .action-row details.action-content {
    padding: 0;
    margin: 0;
  }
  .action-row summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin: 0;
    cursor: pointer;
    list-style: none;
  }
  .action-row summary::-webkit-details-marker {
    display: none;
  }
  .action-row summary::after {
    content: '';
    width: 5px;
    height: 5px;
    border-right: 1px solid currentColor;
    border-bottom: 1px solid currentColor;
    transform: rotate(-45deg);
  }
  .action-row details[open] > summary::after {
    transform: rotate(45deg);
  }
  .action-row summary:hover,
  .action-row button.action-content:hover:not(:disabled) {
    background: var(--tint-surface, #30342e);
  }
  .dragging {
    opacity: 0.55;
  }
  .drop-before::before,
  .drop-after::after {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    height: 2px;
    background: var(--accent);
    pointer-events: none;
  }
  .drop-before::before {
    top: 0;
  }
  .drop-after::after {
    bottom: 0;
  }
  .split-diamond {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr;
    grid-template-rows: repeat(3, auto);
    gap: 2px;
    margin-top: 6px;
  }
  .split-diamond button {
    display: flex;
    align-items: center;
    justify-content: center !important;
    padding: 5px !important;
    gap: 3px;
  }
  .up {
    grid-column: 2;
    grid-row: 1;
  }
  .left {
    grid-column: 1;
    grid-row: 2;
  }
  .right {
    grid-column: 3;
    grid-row: 2;
  }
  .down {
    grid-column: 2;
    grid-row: 3;
  }
  .reorder-hint {
    display: block;
    font-size: 10px;
    color: var(--muted);
    padding: 6px;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
  }
  button:focus-visible,
  summary:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
</style>
