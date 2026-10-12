<script lang="ts">
  import { onMount, tick, untrack } from 'svelte';
  import { X, Search, ChevronLeft, ChevronRight } from '@lucide/svelte';
  import { terminals } from '../lib/terminals';
  import {
    searchUpdates,
    scopeTargets,
    searchPattern,
    type SearchTarget,
    type SearchHit,
    type SearchScope,
    type SearchNavigation,
  } from '../lib/terminal-search';
  let {
    targets,
    origin,
    onjump,
    onclose,
    onnavigation,
  }: {
    targets: SearchTarget[];
    origin: SearchTarget;
    onjump: (hit: SearchHit) => Promise<boolean>;
    onclose: () => void;
    onnavigation: (value: SearchNavigation | null, hit?: SearchHit) => void;
  } = $props();
  let query = $state('');
  let matchCase = $state(false),
    regex = $state(false);
  let scope = $state<SearchScope>('terminal');
  let hits = $state<SearchHit[]>([]),
    active = $state(-1);
  let compact = $state(false),
    error = $state(''),
    limited = $state(false),
    busy = $state(false);
  let input: HTMLInputElement;
  let debounce: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;
  let revision = 0;
  let refreshTimer: ReturnType<typeof setTimeout> | undefined;
  const scopes: [SearchScope, string][] = [
    ['terminal', 'Terminal'],
    ['workspace', 'Workspace'],
    ['host', 'All on host'],
    ['global', 'Global'],
  ];
  const count = $derived(hits.length + (limited ? '+' : '') + ' matches');
  function prune(keep: (hit: SearchHit) => boolean) {
    const selected = hits[active]?.id;
    hits = hits.filter(keep);
    active = hits.findIndex((hit) => hit.id === selected);
    if (compact && active < 0) {
      compact = false;
      onnavigation(null);
    }
  }
  $effect(() => {
    const live = new Map(targets.map((target) => [target.paneId, target]));
    untrack(() => {
      prune((hit) => live.has(hit.paneId));
      hits = hits.map((hit) => ({ ...hit, ...live.get(hit.paneId) }));
    });
  });
  function release() {
    for (const controls of terminals.values()) controls.releaseSearch?.();
  }
  async function collect() {
    const requestRevision = ++revision;
    clearTimeout(debounce);
    debounce = undefined;
    release();
    hits = [];
    active = -1;
    error = '';
    limited = false;
    onnavigation(null);
    if (!query) return;
    try {
      searchPattern({ query, matchCase, regex });
      const result: SearchHit[] = [];
      for (const target of scopeTargets(targets, scope, origin)) {
        const matches =
          (await terminals
            .get(target.paneId)
            ?.findMatches?.({ query, matchCase, regex }, 1001 - result.length)) || [];
        if (disposed || requestRevision !== revision) return;
        result.push(...matches.map((match) => ({ ...target, ...match })));
        if (result.length > 1000) {
          limited = true;
          break;
        }
      }
      hits = result.slice(0, 1000);
    } catch (e) {
      if (disposed || requestRevision !== revision) return;
      error = e instanceof Error ? e.message : 'Search failed.';
    }
  }
  function edited() {
    revision++;
    compact = false;
    release();
    hits = [];
    active = -1;
    error = '';
    onnavigation(null);
    clearTimeout(debounce);
    debounce = setTimeout(collect, 150);
  }
  export function showResults() {
    compact = false;
    onnavigation(null);
    collect();
    void tick().then(() => input?.focus());
  }
  export async function step(direction = 1) {
    if (busy || disposed) return;
    if (debounce) await collect();
    if (!hits.length) {
      await collect();
      if (!hits.length) return;
    }
    const next =
      active < 0
        ? direction < 0
          ? hits.length - 1
          : 0
        : (active + direction + hits.length) % hits.length;
    await jump(next, direction);
  }
  async function jump(index: number, direction = 1) {
    if (busy || disposed) return;
    busy = true;
    const requestRevision = revision;
    try {
      for (let remaining = hits.length; remaining > 0 && hits.length; remaining--) {
        const hit = hits[index];
        const found = targets.some((target) => target.paneId === hit.paneId) && (await onjump(hit));
        if (disposed || requestRevision !== revision) return;
        if (found) {
          active = index;
          compact = true;
          error = '';
          onnavigation(
            {
              query,
              position: `${index + 1} / ${hits.length}${limited ? '+' : ''}`,
              destination: `${hit.host} / ${hit.workspace} / ${hit.terminal}`,
            },
            hit,
          );
          await tick();
          input?.focus();
          return;
        }
        hits = hits.filter((_, position) => position !== index);
        index = direction < 0 ? (index - 1 + hits.length) % hits.length : index % hits.length;
      }
      active = -1;
      compact = false;
      onnavigation(null);
      error = 'These matches are no longer in live output. Search again to refresh.';
    } catch (e) {
      if (disposed || requestRevision !== revision) return;
      error = String(e);
      compact = false;
    } finally {
      busy = false;
    }
  }
  onMount(() => {
    input.focus();
    const changed = (event: Event) => {
      const { paneId, invalidated } = (
        event as CustomEvent<{ paneId: string; invalidated: boolean }>
      ).detail;
      if (invalidated) prune((hit) => hit.paneId !== paneId);
      if (query && !compact && !busy && !debounce && !refreshTimer) {
        refreshTimer = setTimeout(() => {
          refreshTimer = undefined;
          if (!compact && !busy) collect();
        }, 350);
      }
    };
    searchUpdates.addEventListener('change', changed);
    return () => {
      searchUpdates.removeEventListener('change', changed);
      clearTimeout(refreshTimer);
      disposed = true;
      clearTimeout(debounce);
      release();
      onnavigation(null);
    };
  });
</script>

<div
  class="search-popup"
  class:compact
  role="dialog"
  aria-label="Search live terminals"
  aria-busy={busy}
  tabindex="-1"
  onkeydown={(event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onclose();
    }
  }}
>
  {#if !compact}<div class="heading">
      <span>Search terminals</span><span class="hint">Live output only</span>
    </div>{/if}
  <div class="query-row">
    <label class="query"
      ><Search size={15} /><input
        bind:this={input}
        bind:value={query}
        aria-label="Search live terminal output"
        placeholder="Word, phrase or pattern"
        autocomplete="off"
        oninput={edited}
        onkeydown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            event.stopPropagation();
            void step(event.shiftKey ? -1 : 1);
          }
          if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            onclose();
          }
        }}
      /></label
    >
    {#if compact}<div class="navigation">
        <button
          disabled={busy}
          aria-label="Previous match"
          title="Previous match (Shift+Enter)"
          onclick={() => void step(-1)}><ChevronLeft size={15} /></button
        >
        <span aria-live="polite">{active + 1} / {hits.length}{limited ? '+' : ''}</span>
        <button
          disabled={busy}
          aria-label="Next match"
          title="Next match (Enter)"
          onclick={() => void step(1)}><ChevronRight size={15} /></button
        >
        <button onclick={showResults}>Results</button>
      </div>{:else}<button onclick={collect}>Search</button>{/if}
    <button aria-label="Close search" title="Close search (Escape)" onclick={onclose}
      ><X size={15} /></button
    >
  </div>
  {#if compact && hits[active]}<div class="location" aria-live="polite">
      {hits[active].host} / {hits[active].workspace} / {hits[active].terminal}<span
        >{hits[active].session}</span
      >
    </div>{/if}
  {#if !compact}
    <div class="controls">
      <div class="scopes" role="group" aria-label="Search scope">
        {#each scopes as [value, label]}<button
            aria-pressed={scope === value}
            onclick={() => {
              scope = value;
              collect();
            }}>{label}</button
          >{/each}
      </div>
      <label><input type="checkbox" bind:checked={matchCase} onchange={collect} />Match case</label>
      <label
        ><input type="checkbox" bind:checked={regex} onchange={collect} />Regular expression</label
      >
    </div>
    <div class="context">
      <span
        >{scope === 'terminal'
          ? origin.terminal
          : scope === 'workspace'
            ? origin.workspace
            : scope === 'host'
              ? origin.host
              : 'All hosts'} · Live buffers</span
      ><span aria-live="polite">{count}</span>
    </div>
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <div class="results" role="region" aria-label="Scrollable search results">
      <table>
        <thead
          ><tr
            ><th>Host / workspace</th><th>Terminal / session</th><th>Matching output</th><th
              ><span class="sr-only">Action</span></th
            ></tr
          ></thead
        >
        <tbody
          >{#each hits as hit, index (hit.id)}<tr
              ><td>{hit.host}<span>{hit.workspace}</span></td><td
                >{hit.terminal}<span>{hit.session}</span></td
              ><td class="snippet"
                >{hit.text.slice(0, hit.from)}<mark>{hit.text.slice(hit.from, hit.to)}</mark
                >{hit.text.slice(hit.to)}</td
              ><td
                ><button
                  disabled={busy}
                  aria-label={`Go to ${hit.terminal} in ${hit.workspace}`}
                  onclick={() => void jump(index)}>Go to</button
                ></td
              ></tr
            >{/each}</tbody
        >
      </table>
    </div>
    {#if !hits.length && !error}<p class="hint" aria-live="polite">
        {query
          ? 'No matches. Try a shorter phrase or a wider scope.'
          : 'Enter a word or phrase to search.'}
      </p>{/if}
    <div class="hint footer">
      Enter: next · Shift+Enter: previous · No archive or query history{limited
        ? ' · First 1,000 matches, narrow your search'
        : ''}
    </div>
  {/if}
</div>

<style>
  .search-popup {
    position: fixed;
    z-index: 90;
    top: 56px;
    right: 20px;
    width: min(680px, calc(100vw - 24px));
    padding: 12px;
    background: var(--panel);
    color: inherit;
    border: 1px solid var(--border);
    border-radius: 8px;
    box-shadow: 0 10px 32px #0005;
    font-size: 12px;
    box-sizing: border-box;
    max-height: calc(100vh - 76px);
    overflow: auto;
  }
  .search-popup.compact {
    width: min(520px, calc(100vw - 24px));
  }
  .heading,
  .query-row,
  .navigation,
  .context,
  .controls,
  .scopes {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .heading,
  .context {
    justify-content: space-between;
  }
  .heading {
    font-size: 14px;
    margin-bottom: 8px;
  }
  .query-row {
    flex-wrap: wrap;
  }
  .query {
    flex-direction: row;
    margin: 0;
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 1;
    min-width: 130px;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0 8px;
    background: var(--bg);
  }
  .query input {
    border: 0;
    background: transparent;
    width: 100%;
    min-width: 0;
    padding: 7px 0;
    color: inherit;
    font: inherit;
  }
  button {
    font: inherit;
    padding: 4px 7px;
    min-height: 30px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    white-space: nowrap;
  }
  button[aria-pressed='true'] {
    background: var(--accent);
    color: var(--bg);
  }
  .controls {
    flex-wrap: wrap;
    margin: 8px 0;
    gap: 8px;
  }
  .controls label {
    flex-direction: row;
    margin: 0;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    min-height: 30px;
    white-space: nowrap;
  }
  .controls input {
    margin: 0;
    accent-color: var(--accent);
  }
  .scopes {
    gap: 3px;
    flex-wrap: wrap;
  }
  .context,
  .hint,
  .location,
  td span {
    color: var(--muted);
    font-size: 12px;
  }
  .context {
    margin: 8px 0 6px;
    flex-wrap: wrap;
  }
  .results {
    max-height: 174px;
    overflow: auto;
    overscroll-behavior: contain;
    border: 1px solid var(--border);
    border-radius: 4px;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font: inherit;
  }
  th,
  td {
    padding: 6px 8px;
    text-align: left;
    border-bottom: 1px solid var(--border);
    vertical-align: top;
  }
  th {
    position: sticky;
    top: 0;
    background: var(--panel);
    font-weight: 500;
    color: var(--muted);
    z-index: 1;
  }
  td span {
    display: block;
    margin-top: 3px;
  }
  .snippet {
    min-width: 160px;
    max-width: 250px;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
  mark {
    background: var(--raised);
    color: var(--accent);
    border-radius: 2px;
  }
  .hint {
    margin: 0;
    line-height: 1.4;
  }
  .footer,
  .location {
    margin-top: 8px;
  }
  .location {
    overflow-wrap: anywhere;
  }
  .location span {
    display: block;
    margin-top: 3px;
  }
  .navigation {
    white-space: nowrap;
  }
  .error {
    color: var(--red);
    margin: 8px 0;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
  }
  @media (pointer: coarse) {
    button,
    .controls label {
      flex-direction: row;
      margin: 0;
      min-height: 44px;
    }
    .query input {
      font-size: 16px;
    }
  }
</style>
