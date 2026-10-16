<script lang="ts">
  import RepoPicker from '../../components/RepoPicker.svelte';
  import { ArrowUpRight } from '@lucide/svelte';
  import type { Device, Inventory } from '../../lib/types';
  import type { SessionForm, Task } from './types';
  let {
    busy,
    connections,
    device,
    form = $bindable(),
    inventories,
    launch,
    launchAxis,
    launchChooseRepo = $bindable(),
    launchFromCommand,
    task,
  }: {
    busy: boolean;
    connections: Record<string, string>;
    device: Device;
    form: SessionForm;
    inventories: Record<string, Inventory>;
    launch: () => Promise<void>;
    launchAxis: 'row' | 'column' | null;
    launchChooseRepo: boolean;
    launchFromCommand: boolean;
    task: Task;
  } = $props();
</script>

<form
  onsubmit={(e) => {
    e.preventDefault();
    void task(launch);
  }}
>
  <div class="form-row">
    <label
      >Workspace name<input
        required={!launchAxis}
        bind:value={form.name}
        placeholder="Core · planning"
      /></label
    ><label>Terminals<input type="number" min="1" step="1" bind:value={form.count} /></label>
  </div>
  <label
    >Host<select
      bind:value={form.hostId}
      onchange={() => {
        form.repo = '';
        form.identity = '';
        form.model = '';
        form.effort = '';
      }}
      ><option value="">Choose a connected host</option>{#each device.hosts as h}<option
          value={h.id}>{h.name} · {connections[h.id] || 'disconnected'}</option
        >{/each}</select
    ></label
  >
  {#if launchFromCommand}
    <p class="hint">
      {launchAxis ? 'Add to the current workspace' : 'Create a new workspace'} · {form.agent ===
      'shell'
        ? form.repo
          ? 'New tmux shells'
          : 'Plain shells'
        : `New ${form.agent} sessions`}. Review these options before starting.
    </p>
    <label
      >Agent<select bind:value={form.agent}
        ><option value="shell">Plain shell</option
        >{#each ['codex', 'claude', 'gemini', 'opencode'] as agent}<option value={agent}
            >{agent}</option
          >{/each}</select
      ></label
    >
    {#if form.agent !== 'shell'}<p class="hint">
        The agent must already be installed on the selected host. Each terminal starts a separate
        session.
      </p>{/if}
    <label class="check"
      ><input type="checkbox" bind:checked={launchChooseRepo} /> Choose repository before starting</label
    >
    {#if launchChooseRepo}
      <RepoPicker
        repos={inventories[form.hostId]?.repos || []}
        hostId={form.hostId}
        bind:value={form.repo}
        warnings={inventories[form.hostId]?.warnings || []}
        disabled={busy}
      />
    {:else if form.repo}<p class="hint">Repository: {form.repo}</p>{/if}
    {#if form.agent !== 'shell' && form.repo}<label class="check"
        ><input type="checkbox" bind:checked={form.brief} /> Read repository instructions and give a concise
        orientation</label
      >{/if}
  {:else}
    <p class="hint">
      Open plain shells in this host’s home directory. Use each terminal’s tmux button to join or
      create a session.
    </p>
  {/if}
  <button class="primary" disabled={busy || !form.hostId}
    >{busy ? 'Starting…' : `Open ${form.count} ${form.count === 1 ? 'terminal' : 'terminals'}`}
    <ArrowUpRight size={16} /></button
  >
</form>
