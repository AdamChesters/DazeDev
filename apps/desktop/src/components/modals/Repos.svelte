<script lang="ts">
  import RepoPicker from '../../components/RepoPicker.svelte';
  import type { Device, Inventory } from '../../lib/types';
  import type { GithubRepo, SessionForm, Task } from './types';
  let {
    busy,
    cloneRepo,
    device,
    form = $bindable(),
    githubRepos,
    inventories,
    loadGithubRepos,
    refresh,
    search,
    showLaunch,
    task,
  }: {
    busy: boolean;
    cloneRepo: (repo: string) => Promise<void>;
    device: Device;
    form: SessionForm;
    githubRepos: GithubRepo[];
    inventories: Record<string, Inventory>;
    loadGithubRepos: () => Promise<void>;
    refresh: (id?: string) => Promise<void>;
    search: string;
    showLaunch: (axis?: 'row' | 'column' | null) => void;
    task: Task;
  } = $props();
</script>

<button disabled={busy} onclick={() => void task(() => refresh())}
  >Refresh repositories</button
>
<RepoPicker
  repos={inventories[device.activeHost]?.repos || []}
  hostId={device.activeHost}
  warnings={inventories[device.activeHost]?.warnings || []}
  disabled={busy}
  onchoose={(repo) => {
    showLaunch();
    form.repo = repo.path;
  }}
/>
<div class="settings-section">
  <h3>GitHub repositories</h3>
  <div class="form-row">
    <label
      >Account<select bind:value={form.identity}
        ><option value="">Select account</option
        >{#each inventories[device.activeHost]?.identities || [] as i}<option
            value={i.login}>{i.login}</option
          >{/each}</select
      ></label
    ><button
      disabled={!form.identity || busy}
      onclick={() => void task(loadGithubRepos)}>Load repositories</button
    >
  </div>
  {#each githubRepos.filter((r) => r.full_name
      .toLowerCase()
      .includes(search.toLowerCase())) as r}<div class="item-row">
      <div>
        <strong>{r.full_name}</strong><small>{r.description || 'GitHub repository'}</small>
      </div>
      <button
        disabled={busy}
        onclick={() => void task(() => cloneRepo(r.full_name))}>Clone</button
      >
    </div>{/each}
</div>
