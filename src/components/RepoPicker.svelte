<script lang="ts">
  import { rpc } from '../lib/bridge';
  import { repositoryGroups, type WorktreeGroup } from '../lib/repositories';
  import type { Repo } from '../lib/types';
  let {
    repos,
    hostId,
    value = $bindable(''),
    warnings = [],
    disabled = false,
    onchoose,
  }: {
    repos: Repo[];
    hostId: string;
    value?: string;
    warnings?: string[];
    disabled?: boolean;
    onchoose?: (repo: Repo) => void;
  } = $props();
  let query = $state('');
  let groups = $state<WorktreeGroup[]>([]);
  let worktreeWarnings = $state<string[]>([]);
  let loading = $state(false);
  let refresh = $state(0);
  const visible = $derived(repositoryGroups(repos, query));
  $effect(() => {
    const host = hostId;
    const paths = repos.map((r) => r.path);
    refresh;
    let canceled = false;
    groups = [];
    worktreeWarnings = [];
    loading = !!host && !!paths.length;
    if (host && paths.length)
      void rpc<{ groups: WorktreeGroup[]; warnings: string[] }>(host, 'repository_worktrees', {
        paths,
      })
        .then((result) => {
          if (!canceled) {
            groups = result.groups;
            worktreeWarnings = result.warnings;
          }
        })
        .catch((e) => {
          if (!canceled) worktreeWarnings = [String(e)];
        })
        .finally(() => {
          if (!canceled) loading = false;
        });
    return () => {
      canceled = true;
    };
  });
</script>

<div class="repository-browser">
  <section aria-label="Repositories">
    <label
      >Find repository<input
        type="search"
        bind:value={query}
        placeholder="Name, owner or path"
      /></label
    >
    <div class="repository-scroll">
      {#each visible as group}
        <h3>{group.label}</h3>
        {#each group.repos as repo}
          <button
            type="button"
            class="repository-choice"
            class:selected={value === repo.path}
            {disabled}
            aria-pressed={value === repo.path}
            onclick={() => {
              value = repo.path;
              onchoose?.(repo);
            }}
          >
            <strong>{repo.name}</strong><span
              >{repo.branch || 'Detached HEAD'}{repo.dirty ? ' · modified' : ''}</span
            ><small>{repo.path}</small>
          </button>
        {/each}
      {/each}
      {#if !visible.length}<p class="hint">
          {repos.length
            ? 'No matching repositories.'
            : 'No repositories found. Check repository roots in host settings.'}
        </p>{/if}
    </div>
    {#if warnings.length}<details>
        <summary>Repository scan notes ({new Set(warnings).size})</summary>
        {#each [...new Set(warnings)] as warning}<p class="hint">{warning}</p>{/each}
      </details>{/if}
  </section>
  <section aria-label="Local worktrees">
    <div class="form-row">
      <h3>Local worktrees</h3>
      <button type="button" disabled={loading || !repos.length} onclick={() => refresh++}
        >Refresh worktrees</button
      >
    </div>
    <p class="hint">
      On the selected host. Last modified shows the latest working-file timestamp, excluding ignored
      files.
    </p>
    <div class="repository-scroll" aria-busy={loading}>
      {#if loading}<p role="status">Loading worktrees…</p>{/if}
      {#each [...groups].sort( (a, b) => a.name.localeCompare( b.name, undefined, { sensitivity: 'base' } ) ) as group}
        <h4>{group.name}</h4>
        {#each [...group.worktrees].sort((a, b) => a.path.localeCompare(b.path)) as tree}
          <article class="worktree-entry">
            <strong>{tree.branch || 'Detached HEAD'}{tree.locked ? ' · locked' : ''}</strong><small
              >{tree.path}</small
            >
            <span
              >Last modified: {#if tree.modifiedAt !== null}<time
                  datetime={new Date(tree.modifiedAt * 1000).toISOString()}
                  >{new Date(tree.modifiedAt * 1000).toLocaleString()}</time
                >{:else}Unavailable{/if}{tree.partial ? ' (partial scan)' : ''}</span
            >
          </article>
        {/each}
      {/each}
      {#if !loading && !groups.length && !worktreeWarnings.length}<p class="hint">
          No local worktrees found.
        </p>{/if}
    </div>
    {#each worktreeWarnings as warning}<p class="hint" role="status">{warning}</p>{/each}
  </section>
</div>

<style>
  .repository-browser {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 20px;
  }
  section {
    min-width: 0;
  }
  .repository-scroll {
    max-height: 340px;
    overflow: auto;
  }
  h3,
  h4 {
    margin: 14px 0 8px;
  }
  .repository-choice {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 3px;
    width: 100%;
    text-align: left;
    margin: 4px 0;
    padding: 10px;
  }
  .repository-choice.selected {
    outline: 2px solid var(--accent, #6aafff);
    outline-offset: -2px;
  }
  small {
    display: block;
    overflow-wrap: anywhere;
  }
  .repository-choice span,
  .worktree-entry span {
    font-size: 12px;
  }
  .worktree-entry {
    padding: 8px 0;
  }
  details {
    margin-top: 8px;
  }
  @media (max-width: 700px) {
    .repository-browser {
      grid-template-columns: minmax(0, 1fr);
    }
    .repository-scroll {
      max-height: 240px;
    }
  }
</style>
