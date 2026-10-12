<script lang="ts">
  import { onMount, type Snippet } from 'svelte';

  let {
    name,
    visible,
    children,
    repo,
    session,
  }: {
    name: string;
    visible: boolean;
    children: Snippet;
    repo?: Snippet;
    session?: Snippet;
  } = $props();
  const id = $props.id();
  let root: HTMLDivElement;
  let panel: HTMLDivElement;
  let repoSlot: HTMLSpanElement;
  let sessionSlot: HTMLSpanElement;
  let menuButton = $state<HTMLButtonElement>();
  let compact = $state(true);
  let stage = $state<'repo' | 'session' | 'menu'>('menu');
  let open = $state(false);
  let left = $state(8);
  let top = $state(8);

  function close() {
    if (panel?.matches(':popover-open')) panel.hidePopover();
  }
  function position(event: MouseEvent) {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    left = Math.max(8, Math.min(rect.right - 240, innerWidth - 248));
    top = Math.max(8, Math.min(rect.bottom + 4, innerHeight - 340));
  }
  $effect(() => {
    if (!visible) close();
  });
  onMount(() => {
    const header = root.closest('.pane-header')!;
    let measuredText = '',
      controlsWidth = 0;
    const observer = new ResizeObserver(() => {
      const style = getComputedStyle(header);
      const width =
        header.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      close();
      if (panel.textContent !== measuredText || !controlsWidth) {
        const measuring = panel.cloneNode(true) as HTMLDivElement;
        measuring.removeAttribute('id');
        measuring.removeAttribute('popover');
        measuring.classList.remove('compact');
        measuring.setAttribute('aria-hidden', 'true');
        measuring.inert = true;
        measuring.style.cssText =
          'position:fixed;visibility:hidden;display:flex;width:max-content;inset:auto;max-height:none';
        root.appendChild(measuring);
        controlsWidth = measuring.getBoundingClientRect().width;
        measuring.remove();
        measuredText = panel.textContent || '';
      }
      const hostLabel =
        header.querySelector('.cross-host-note')?.getBoundingClientRect().width || 0;
      const nameWidth = header.querySelector('.terminal-name')?.scrollWidth || 104;
      const titleWidth = Math.max(104, Math.min(180, nameWidth));
      compact = width < controlsWidth + titleWidth + hostLabel + (hostLabel ? 6 : 0) + 6;
      const available =
        width - titleWidth - hostLabel - (hostLabel ? 6 : 0) - (menuButton?.offsetWidth || 52) - 12;
      const sessionWidth = sessionSlot?.offsetWidth || 90;
      const repoWidth = repoSlot?.offsetWidth || 70;
      stage =
        available >= sessionWidth + repoWidth + 6
          ? 'repo'
          : available >= sessionWidth
            ? 'session'
            : 'menu';
    });
    observer.observe(header);
    observer.observe(repoSlot);
    observer.observe(sessionSlot);
    return () => observer.disconnect();
  });
</script>

<svelte:window onresize={close} />
<div class="pane-actions" bind:this={root} data-action-stage={compact ? stage : 'full'}>
  <div class="priority-actions" class:measuring={!compact}>
    <span
      bind:this={repoSlot}
      class:priority-hidden={!compact || stage !== 'repo'}
      aria-hidden={!compact || stage !== 'repo'}
      inert={!compact || stage !== 'repo'}
      >{#if repo}{@render repo()}{/if}</span
    >
    <span
      bind:this={sessionSlot}
      class:priority-hidden={!compact || stage === 'menu'}
      aria-hidden={!compact || stage === 'menu'}
      inert={!compact || stage === 'menu'}
      >{#if session}{@render session()}{/if}</span
    >
  </div>
  {#if compact}
    <button
      bind:this={menuButton}
      class="pane-more"
      title="Terminal menu"
      aria-label={`Menu for ${name}`}
      aria-expanded={open}
      aria-controls={id}
      popovertarget={id}
      onclick={position}>Menu</button
    >
  {/if}
  <div
    bind:this={panel}
    {id}
    class="pane-buttons"
    class:compact
    popover={compact ? 'auto' : undefined}
    role="group"
    aria-label={`Actions for ${name}`}
    style:left={compact ? `${left}px` : undefined}
    style:top={compact ? `${top}px` : undefined}
    style:max-height={compact ? `calc(100dvh - ${top + 8}px)` : undefined}
    ontoggle={(event) => (open = event.newState === 'open')}
    onclickcapture={(event) => {
      if ((event.target as HTMLElement).closest('button')) close();
    }}
  >
    {#if repo}{@render repo()}{/if}
    {#if session}{@render session()}{/if}
    {@render children()}
  </div>
</div>

<style>
  .pane-actions {
    flex: 0 0 auto;
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .priority-actions {
    display: flex;
    gap: 6px;
  }
  .priority-actions span {
    display: flex;
  }
  .priority-hidden,
  .measuring {
    position: absolute;
    visibility: hidden;
    pointer-events: none;
  }
  .pane-more {
    width: auto;
    height: 30px;
    padding: 0 8px;
    white-space: nowrap;
    color: var(--tint-text, #edede8);
  }
  .compact {
    position: fixed;
    inset: auto;
    margin: 0;
    width: min(240px, calc(100vw - 16px));
    padding: 6px;
    border: 1px solid var(--line);
    border-radius: 7px;
    background: var(--bg);
    color: var(--tint-text, #edede8);
    box-shadow: 0 8px 24px #0006;
    overflow: auto;
  }
  .compact:not(:popover-open) {
    display: none;
  }
  .compact:popover-open {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .compact :global(button) {
    width: 100%;
    min-height: 32px;
    justify-content: flex-start;
  }
  .compact :global(.overflow-label) {
    display: inline;
  }
  .compact :global(.pane-progress) {
    width: 100%;
  }
</style>
