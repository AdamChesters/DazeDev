<script lang="ts">
  import type { Device, Host, Inventory } from '../../lib/types';
  import { Download, Pencil, Plus, RefreshCw, Trash2 } from '@lucide/svelte';
  import { rpc } from '../../lib/bridge';
  import { agentLabel, startable, upsertCustomAgent, type CustomAgent } from '../../lib/agents';
  import CustomAgentForm from './CustomAgentForm.svelte';
  import type { Task } from './types';
  let {
    busy,
    device,
    host,
    inventories,
    refresh,
    startAgentFromPicker,
    task,
  }: {
    busy: boolean;
    device: Device;
    host: Host | undefined;
    inventories: Record<string, Inventory>;
    refresh: (id?: string) => Promise<void>;
    startAgentFromPicker: (agent: string) => Promise<void>;
    task: Task;
  } = $props();
  type Saved = { agents: CustomAgent[]; revision: string };
  const blank = (): CustomAgent => ({
    id: '',
    name: '',
    command: '',
    install: '',
    version: '',
    binary: '',
  });
  // The host's saved records, read fresh before every edit so the save
  // carries the current revision.
  let saved = $state<Saved | null>(null);
  let editing = $state<{ agent: CustomAgent; id?: string } | null>(null);
  let removing = $state('');
  // Native PowerShell sessions run on this computer, without the host helper.
  let native = $derived(host?.kind === 'powershell');

  const read = async () => (saved = await rpc<Saved>(device.activeHost, 'agents_read', {}));
  async function write(agents: CustomAgent[]) {
    if (!saved) return;
    saved = await rpc<Saved>(device.activeHost, 'agents_save', {
      agents,
      revision: saved.revision,
    });
    editing = null;
    removing = '';
    await refresh(device.activeHost);
  }
  async function edit(id?: string) {
    await read();
    const current = saved?.agents.find((a) => a.id === id);
    editing = { agent: current ? $state.snapshot(current) : blank(), id: current?.id };
  }
</script>

<p class="hint">
  Detected on {host?.name || 'the selected host'}. Authentication stays in each agent’s own CLI.
</p>
{#if editing}
  <CustomAgentForm
    agent={editing.agent}
    {busy}
    cancel={() => (editing = null)}
    others={(saved?.agents || []).filter((a) => a.id !== editing?.id)}
    save={(agent) =>
      void task(() => write(upsertCustomAgent(saved?.agents || [], agent, editing?.id)))}
  />
{:else}
  {#each inventories[device.activeHost]?.agents || [] as a}<div class="agent-card">
      <div>
        <strong>{agentLabel(a)}</strong>{#if a.custom}<span class="badge">Custom</span>{/if}<span
          class="badge">{a.path ? 'Installed' : 'Not found'}</span
        >
      </div>
      <small
        >{a.path || a.install || 'No install command. Install it yourself, then refresh.'}</small
      >{#if a.path}<small>{a.version}</small>{/if}
      <div class="form-row">
        <button
          disabled={busy || !startable(a, true)}
          onclick={() => void task(() => startAgentFromPicker(a.id))}
        >
          {#if !a.path && startable(a, true)}<Download size={14} />{/if}{a.path
            ? native
              ? 'Start in new PowerShell session'
              : 'Start in new tmux session'
            : startable(a, true)
              ? a.custom
                ? 'Run install command and start'
                : 'Install and start'
              : 'Not installed'}</button
        >{#if a.custom}<button
            disabled={busy}
            aria-label={`Edit ${agentLabel(a)}`}
            onclick={() => void task(() => edit(a.id))}><Pencil size={14} /> Edit</button
          >{#if removing === a.id}<button
              disabled={busy}
              onclick={() =>
                void task(async () => {
                  await read();
                  await write((saved?.agents || []).filter((x) => x.id !== a.id));
                })}>Confirm remove</button
            >{:else}<button
              disabled={busy}
              aria-label={`Remove ${agentLabel(a)}`}
              onclick={() => (removing = a.id)}><Trash2 size={14} /> Remove</button
            >{/if}{/if}
      </div>
    </div>{/each}
  {#if native}<p class="hint">
      Native PowerShell hosts list the built-in agents only. Use a WSL or SSH host to add your own.
    </p>{:else}<button disabled={busy} onclick={() => void task(() => edit())}
      ><Plus size={14} /> Add your own agent</button
    >{/if}
  <button onclick={() => void task(() => refresh())}
    ><RefreshCw size={14} /> Refresh discovery</button
  >
{/if}
