<script lang="ts">
  import { onDestroy } from 'svelte';
  import HostConnection from './HostConnection.svelte';
  import HighlightEffect from './HighlightEffect.svelte';
  import type { RecordingHighlight } from '../lib/highlights';
  import {
    ChevronDown,
    ChevronRight,
    Server,
    Plus,
    MoreHorizontal,
    LayoutGrid,
    Trash2,
    Settings,
  } from '@lucide/svelte';
  import type { Host, Workspace, Profile } from '../lib/types';
  import { tabColors } from '../lib/colors';
  import { focusDialog } from '../lib/focus';
  let {
    dragHover = '',
    reorderDisabled = false,
    onreorder = () => {},
    onhostreorder = () => {},
    highlight = 'energy',
    highlightColor = '#39bdd7',
    hosts,
    profiles,
    connections,
    logs = {},
    attempts = new Map<string, string>(),
    onauth,
    oncancel,
    activeHost,
    activeWorkspace,
    collapsed,
    ontoggle,
    onhost,
    onreconnect,
    onedit,
    oncolors,
    ontmux,
    ondocuments,
    onnew,
    onselect,
    onoptions,
    ondelete,
    ondeletehost,
  }: {
    dragHover?: string;
    reorderDisabled?: boolean;
    onreorder?: (source: Workspace, target: Workspace, after: boolean) => void;
    onhostreorder?: (source: Host, target: Host, after: boolean) => void;
    highlight?: RecordingHighlight;
    highlightColor?: string;
    hosts: Host[];
    profiles: Record<string, Profile>;
    connections: Record<string, string>;
    logs?: Record<string, string>;
    attempts?: ReadonlyMap<string, string>;
    onauth?: (hostId: string, value: string) => Promise<void>;
    oncancel?: (host: Host) => Promise<void>;
    activeHost: string;
    activeWorkspace: string;
    collapsed: Record<string, boolean>;
    ontoggle: (h: Host) => void;
    onhost: (h: Host) => void;
    onreconnect: (h: Host) => void;
    onedit: (h: Host) => void;
    oncolors: (h: Host) => void;
    ontmux: (h: Host) => void;
    ondocuments: (h: Host) => void;
    onnew: (h: Host) => void;
    onselect: (w: Workspace) => void;
    onoptions: (w: Workspace) => void;
    ondelete: (w: Workspace) => void;
    ondeletehost: (h: Host) => void;
  } = $props();
  let menu = $state<{ workspace?: Workspace; host?: Host; x: number; y: number } | null>(null);
  let draggingWorkspace = $state('');
  let reorderTarget = $state('');
  let reorderAfter = $state(false);
  let suppressSelect = false;
  let cancelReorder: (() => void) | null = null;
  let draggingHost = $state('');
  let hostTarget = $state('');
  let hostAfter = $state(false);
  let suppressHostSelect = false;
  function stepHost(h: Host, direction: number) {
    const target = hosts[hosts.findIndex((item) => item.id === h.id) + direction];
    if (target && !reorderDisabled) onhostreorder(h, target, direction > 0);
  }
  function startHostDrag(e: PointerEvent, h: Host) {
    if (e.button !== 0 || reorderDisabled) return;
    cancelReorder?.();
    suppressHostSelect = false;
    const button = e.currentTarget as HTMLButtonElement;
    const x = e.clientX,
      y = e.clientY;
    let moved = false;
    const locate = (event: PointerEvent) => {
      const row = document
        .elementFromPoint(event.clientX, event.clientY)
        ?.closest<HTMLElement>('[data-host-row]');
      hostTarget = row?.dataset.hostRow !== h.id ? row?.dataset.hostRow || '' : '';
      if (row)
        hostAfter =
          event.clientY >= row.getBoundingClientRect().top + row.getBoundingClientRect().height / 2;
    };
    const move = (event: PointerEvent) => {
      if (
        event.pointerId !== e.pointerId ||
        (!moved && Math.hypot(event.clientX - x, event.clientY - y) < 6)
      )
        return;
      moved = true;
      draggingHost = h.id;
      menu = null;
      const nav = button.closest('nav')!;
      const box = nav.getBoundingClientRect();
      if (event.clientY < box.top + 24) nav.scrollTop -= 12;
      else if (event.clientY > box.bottom - 24) nav.scrollTop += 12;
      locate(event);
    };
    const cleanup = () => {
      suppressHostSelect = moved;
      draggingHost = hostTarget = '';
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', cleanup);
      window.removeEventListener('blur', cleanup);
      window.removeEventListener('keydown', key, true);
      cancelReorder = null;
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        cleanup();
      }
    };
    const finish = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      if (moved) locate(event);
      const target = hosts.find((item) => item.id === hostTarget),
        after = hostAfter;
      cleanup();
      if (moved && target && !reorderDisabled) onhostreorder(h, target, after);
    };
    cancelReorder = cleanup;
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', cleanup);
    window.addEventListener('blur', cleanup);
    window.addEventListener('keydown', key, true);
  }
  function stepWorkspace(w: Workspace, direction: number) {
    const list = profiles[w.hostId]?.workspaces || [];
    const target = list[list.findIndex((item) => item.id === w.id) + direction];
    if (target && !reorderDisabled) onreorder(w, target, direction > 0);
  }
  function startWorkspaceDrag(e: PointerEvent, w: Workspace) {
    if (e.button !== 0 || reorderDisabled) return;
    e.preventDefault();
    cancelReorder?.();
    suppressSelect = false;
    const button = e.currentTarget as HTMLButtonElement;
    button.focus({ preventScroll: true });
    const x = e.clientX,
      y = e.clientY;
    let moved = false;
    const locate = (event: PointerEvent) => {
      const row = document
        .elementFromPoint(event.clientX, event.clientY)
        ?.closest<HTMLElement>('[data-workspace-row]');
      const target = profiles[w.hostId]?.workspaces.find(
        (item) => item.id === row?.dataset.workspaceRow,
      );
      reorderTarget = target && target.id !== w.id ? target.id : '';
      if (row)
        reorderAfter =
          event.clientY >= row.getBoundingClientRect().top + row.getBoundingClientRect().height / 2;
    };
    const move = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      if (!moved && Math.hypot(event.clientX - x, event.clientY - y) < 6) return;
      moved = true;
      draggingWorkspace = w.id;
      menu = null;
      const nav = button.closest('nav')!;
      const box = nav.getBoundingClientRect();
      if (event.clientY < box.top + 24) nav.scrollTop -= 12;
      else if (event.clientY > box.bottom - 24) nav.scrollTop += 12;
      locate(event);
    };
    const cleanup = () => {
      suppressSelect = moved;
      draggingWorkspace = '';
      reorderTarget = '';
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', abort);
      window.removeEventListener('blur', abort);
      window.removeEventListener('keydown', key, true);
      cancelReorder = null;
    };
    const abort = () => cleanup();
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        abort();
      }
    };
    const finish = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      if (moved) locate(event);
      const target = profiles[w.hostId]?.workspaces.find((item) => item.id === reorderTarget);
      const after = reorderAfter;
      cleanup();
      if (moved && target && !reorderDisabled) onreorder(w, target, after);
    };
    cancelReorder = abort;
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', abort);
    window.addEventListener('blur', abort);
    window.addEventListener('keydown', key, true);
  }
  onDestroy(() => cancelReorder?.());
  function openMenu(e: MouseEvent, target: { workspace?: Workspace; host?: Host }) {
    e.preventDefault();
    (e.currentTarget as HTMLElement).focus();
    const box = (e.currentTarget as HTMLElement).getBoundingClientRect();
    menu = {
      ...target,
      x: Math.max(8, Math.min(e.clientX || box.left, innerWidth - 244)),
      y: Math.max(8, Math.min(e.clientY || box.bottom, innerHeight - 250)),
    };
  }
  function menuKeys(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.stopPropagation();
      menu = null;
    }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) {
      e.preventDefault();
      const items = Array.from(
        (e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="menuitem"]'),
      );
      const i = items.indexOf(document.activeElement as HTMLButtonElement);
      items[
        e.key === 'Home'
          ? 0
          : e.key === 'End'
            ? items.length - 1
            : (i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
      ]?.focus();
    }
  }
</script>

<nav class="host-tree" aria-label="Hosts and workspaces">
  {#each hosts as h (h.id)}
    <section
      class="host-group"
      aria-label={h.name}
      data-host-row={h.id}
      class:workspace-dragging={draggingHost === h.id}
      class:reorder-before={hostTarget === h.id && !hostAfter}
      class:reorder-after={hostTarget === h.id && hostAfter}
    >
      <div
        class="host-heading"
        class:chosen={activeHost === h.id}
        style:background-color={tabColors(h.colors)?.background}
        style:color={tabColors(h.colors)?.foreground}
      >
        <button
          class="icon disclosure"
          aria-label={`${collapsed[h.id] ? 'Expand' : 'Collapse'} ${h.name}`}
          aria-expanded={!collapsed[h.id]}
          aria-controls={`host-workspaces-${h.id}`}
          onclick={() => ontoggle(h)}
        >
          {#if collapsed[h.id]}<ChevronRight size={15} />{:else}<ChevronDown size={15} />{/if}
        </button>
        <button
          class="host-select"
          style:color={tabColors(h.colors)?.foreground}
          title={`${h.name}: ${connections[h.id] || 'Disconnected'}. Click to expand or collapse; drag to reorder.`}
          aria-expanded={!collapsed[h.id]}
          aria-controls={`host-workspaces-${h.id}`}
          onpointerdown={(e) => startHostDrag(e, h)}
          onclick={(e) => {
            if (!suppressHostSelect || e.detail === 0) {
              onhost(h);
              ontoggle(h);
            }
            suppressHostSelect = false;
          }}
          oncontextmenu={(e) => openMenu(e, { host: h })}
          onkeydown={(e) => {
            if (e.altKey && ['ArrowUp', 'ArrowDown'].includes(e.key)) {
              e.preventDefault();
              stepHost(h, e.key === 'ArrowUp' ? -1 : 1);
            } else if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) {
              e.preventDefault();
              const box = e.currentTarget.getBoundingClientRect();
              menu = {
                host: h,
                x: box.left,
                y: Math.max(8, Math.min(box.bottom, innerHeight - 250)),
              };
            }
          }}
        >
          <Server size={15} /><span
            >{h.name}<small
              >{h.kind === 'ssh' || h.kind === 'tailscale'
                ? h.address
                : h.kind === 'wsl'
                  ? 'WSL · ' + (h.distro || 'default')
                  : h.kind === 'powershell'
                    ? 'Native Windows · ' +
                      (h.nativeShell === 'pwsh' ? 'PowerShell 7' : 'PowerShell')
                    : 'Local machine'}</small
            ></span
          >
          <span
            class="connection-dot"
            class:online={connections[h.id] === 'Connected'}
            aria-label={connections[h.id] || 'Disconnected'}
          ></span>
        </button>
        <button
          class="icon"
          aria-label={`Manage host ${h.name}`}
          title="Host options"
          onclick={(e) => openMenu(e, { host: h })}
          oncontextmenu={(e) => openMenu(e, { host: h })}><MoreHorizontal size={13} /></button
        >
      </div>
      {#if connections[h.id] === 'Connecting' || (connections[h.id] === 'Disconnected' && logs[h.id])}
        {#key attempts.get(h.id)}
          <HostConnection
            host={h}
            output={logs[h.id] || ''}
            closed={connections[h.id] === 'Disconnected'}
            onauth={(value) => onauth?.(h.id, value) || Promise.resolve()}
            oncancel={() => oncancel?.(h) || Promise.resolve()}
            onretry={() => onreconnect(h)}
            onedit={() => onedit(h)}
          />
        {/key}
      {/if}
      <div id={`host-workspaces-${h.id}`} class="host-workspaces" hidden={!!collapsed[h.id]}>
        {#each profiles[h.id]?.workspaces || [] as w (w.id)}
          <div
            class="workspace-row"
            data-workspace-row={w.id}
            class:workspace-dragging={draggingWorkspace === w.id}
            class:reorder-before={reorderTarget === w.id && !reorderAfter}
            class:reorder-after={reorderTarget === w.id && reorderAfter}
            class:active={activeWorkspace === w.id}
            class:recording-highlight={activeWorkspace === w.id}
            data-highlight={highlight}
            style:--voice-highlight-color={highlightColor}
            style:background-color={tabColors(w.colors, h.colors)?.background}
            style:color={tabColors(w.colors, h.colors)?.foreground}
          >
            {#if activeWorkspace === w.id && highlight !== 'static'}<HighlightEffect
                kind={highlight}
              />{/if}
            <button
              class="workspace-select"
              data-workspace-tab={w.id}
              class:drag-hover={dragHover === w.id}
              style:color={tabColors(w.colors, h.colors)?.foreground}
              aria-current={activeWorkspace === w.id ? 'page' : undefined}
              title="Drag to reorder. Alt+Up/Down moves this workspace."
              onpointerdown={(e) => startWorkspaceDrag(e, w)}
              onclick={(e) => {
                if (!suppressSelect || e.detail === 0) onselect(w);
                suppressSelect = false;
              }}
              oncontextmenu={(e) => openMenu(e, { workspace: w })}
              onkeydown={(e) => {
                if (e.altKey && ['ArrowUp', 'ArrowDown'].includes(e.key)) {
                  e.preventDefault();
                  stepWorkspace(w, e.key === 'ArrowUp' ? -1 : 1);
                } else if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) {
                  e.preventDefault();
                  const box = e.currentTarget.getBoundingClientRect();
                  menu = {
                    workspace: w,
                    x: box.left,
                    y: Math.max(8, Math.min(box.bottom, innerHeight - 250)),
                  };
                }
              }}><LayoutGrid size={14} /><span>{w.name}</span></button
            >
            <button
              class="icon workspace-close"
              style:color={tabColors(w.colors, h.colors)?.foreground}
              aria-label={`Manage ${w.name}`}
              title="Workspace options"
              onclick={(e) => openMenu(e, { workspace: w })}
              oncontextmenu={(e) => openMenu(e, { workspace: w })}
              ><MoreHorizontal size={14} /></button
            >
          </div>
        {/each}
        {#if !profiles[h.id]?.workspaces.length}
          {#if connections[h.id] === 'Connected'}<p class="empty-host">No workspaces yet</p>
          {:else}<button class="host-new" onclick={() => onreconnect(h)}
              >Connect to load workspaces</button
            >{/if}
        {/if}
        <button class="host-new new-workspace" onclick={() => onnew(h)}
          ><Plus size={13} /> New workspace</button
        >
      </div>
    </section>
  {/each}
  {#if !hosts.length}<p class="empty-host">Add a host with the + button above.</p>{/if}
</nav>
{#if menu}
  <button
    class="menu-dismiss"
    aria-label="Close menu"
    tabindex="-1"
    onclick={() => (menu = null)}
    oncontextmenu={(e) => {
      e.preventDefault();
      menu = null;
    }}
  ></button>
  <div
    class="workspace-menu"
    role="menu"
    aria-label={`${(menu.workspace || menu.host)?.name} options`}
    tabindex="-1"
    style:left={`${menu.x}px`}
    style:top={`${menu.y}px`}
    style:max-height={`calc(100vh - ${menu.y + 8}px)`}
    style:overflow-y="auto"
    use:focusDialog
    onkeydown={menuKeys}
  >
    {#if menu.host}
      <button
        role="menuitem"
        disabled={reorderDisabled || hosts[0]?.id === menu.host.id}
        onclick={() => {
          stepHost(menu!.host!, -1);
          menu = null;
        }}>Move host up</button
      >
      <button
        role="menuitem"
        disabled={reorderDisabled || hosts.at(-1)?.id === menu.host.id}
        onclick={() => {
          stepHost(menu!.host!, 1);
          menu = null;
        }}>Move host down</button
      >
      <button
        role="menuitem"
        onclick={() => {
          const h = menu!.host!;
          menu = null;
          onreconnect(h);
        }}>{connections[menu.host.id] === 'Connected' ? 'Reconnect host' : 'Connect host'}</button
      >
      <button
        role="menuitem"
        onclick={() => {
          const h = menu!.host!;
          menu = null;
          oncolors(h);
        }}>Host colors</button
      >
      {#if menu.host.kind !== 'powershell'}
        <button
          role="menuitem"
          disabled={connections[menu.host.id] !== 'Connected'}
          onclick={() => {
            const h = menu!.host!;
            menu = null;
            ontmux(h);
          }}>tmux settings</button
        >
      {/if}
      <button
        role="menuitem"
        disabled={connections[menu.host.id] !== 'Connected'}
        onclick={() => {
          const h = menu!.host!;
          menu = null;
          ondocuments(h);
        }}>Markdown and rules</button
      >
      <button
        role="menuitem"
        onclick={() => {
          const h = menu!.host!;
          menu = null;
          onedit(h);
        }}><Settings size={15} /> Edit host</button
      >
      <button
        role="menuitem"
        onclick={() => {
          const h = menu!.host!;
          menu = null;
          ondeletehost(h);
        }}><Trash2 size={15} /> Delete host</button
      >
    {:else}
      <button
        role="menuitem"
        disabled={reorderDisabled ||
          profiles[menu.workspace!.hostId]?.workspaces[0]?.id === menu.workspace!.id}
        onclick={() => {
          stepWorkspace(menu!.workspace!, -1);
          menu = null;
        }}>Move up</button
      >
      <button
        role="menuitem"
        disabled={reorderDisabled ||
          profiles[menu.workspace!.hostId]?.workspaces.at(-1)?.id === menu.workspace!.id}
        onclick={() => {
          stepWorkspace(menu!.workspace!, 1);
          menu = null;
        }}>Move down</button
      >
      <button
        role="menuitem"
        onclick={() => {
          const w = menu!.workspace!;
          menu = null;
          onoptions(w);
        }}><Settings size={15} /> Workspace settings</button
      >
      <button
        role="menuitem"
        onclick={() => {
          const w = menu!.workspace!;
          menu = null;
          ondelete(w);
        }}><Trash2 size={15} /> Delete workspace</button
      >
    {/if}
    <p>Sessions keep running until you end them.</p>
  </div>
{/if}

<style>
  .workspace-row {
    position: relative;
  }
  .workspace-select {
    touch-action: none;
    min-height: 30px;
    padding: 5px 7px;
    gap: 7px;
  }
  .workspace-dragging {
    opacity: 0.5;
  }
  .reorder-before::before,
  .reorder-after::after {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    height: 3px;
    background: var(--accent);
    z-index: 10;
    pointer-events: none;
  }
  .reorder-before::before {
    top: -2px;
  }
  .reorder-after::after {
    bottom: -2px;
  }
  .host-tree {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 8px 4px 0;
  }
  .host-group {
    position: relative;
    margin-bottom: 5px;
  }
  .host-heading {
    display: flex;
    align-items: center;
    border: 1px solid transparent;
    border-radius: 6px;
  }
  .host-heading.chosen {
    background: var(--tint-surface, #242822);
    border-color: var(--tint-border, #414d39);
  }
  .host-heading .icon {
    width: 26px;
    min-width: 26px;
    padding: 4px;
  }
  .host-select {
    flex: 1;
    min-width: 0;
    padding: 5px 2px;
    text-align: left;
    justify-content: flex-start;
    gap: 7px;
    background: transparent;
    border: 0;
    touch-action: none;
  }
  .host-select > span:first-of-type {
    flex: 1;
    min-width: 0;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  small {
    font-size: 10px;
    margin-top: 3px;
  }
  .host-workspaces {
    margin: 3px 0 0 11px;
    padding-left: 6px;
    border-left: 1px solid var(--border);
  }
  .host-workspaces[hidden] {
    display: none;
  }
  .host-new {
    width: calc(100% - 6px);
    font-size: 13px;
    font-weight: 600;
    min-height: 30px;
    padding: 5px 7px;
    margin: 5px 0;
  }
  .workspace-row {
    margin-bottom: 2px;
    border-radius: 3px;
  }
  @media (pointer: coarse) {
    .workspace-select,
    .host-select,
    .host-new {
      min-height: 44px;
    }
  }
  .empty-host {
    color: var(--muted);
    font-size: 11px;
    padding: 5px 7px;
  }
  .menu-dismiss,
  .menu-dismiss:hover:not(:disabled),
  .menu-dismiss:active,
  .menu-dismiss:focus-visible {
    position: fixed;
    inset: 0;
    width: 100%;
    height: 100%;
    z-index: 80;
    border: 0;
    border-radius: 0;
    background: transparent;
    cursor: default;
    outline: none;
    box-shadow: none;
  }
  .workspace-menu {
    position: fixed;
    z-index: 81;
    width: 236px;
    padding: 6px;
    background: var(--tint-surface, #242822);
    border: 1px solid var(--border);
    border-radius: 8px;
    box-shadow: 0 8px 30px #0008;
  }
  .workspace-menu button {
    width: 100%;
    justify-content: flex-start;
    background: transparent;
    border: 0;
    padding: 10px;
  }
  .workspace-menu p {
    margin: 5px 10px;
    font-size: 10px;
    color: var(--muted);
  }
</style>
