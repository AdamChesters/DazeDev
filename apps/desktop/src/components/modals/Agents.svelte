<script lang="ts">
  import type { Device, Host, Inventory } from '../../lib/types';
  import { Download, RefreshCw } from '@lucide/svelte';
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
</script>

<p class="hint">
  Detected on {host?.name || 'the selected host'}. Authentication stays in each agent’s own
  CLI.
</p>
{#each inventories[device.activeHost]?.agents || [] as a}<div class="agent-card">
    <div>
      <strong>{a.id}</strong><span class="badge">{a.path ? 'Installed' : 'Not found'}</span>
    </div>
    <small>{a.path || a.install}</small>{#if a.path}<small>{a.version}</small>{/if}
    <button disabled={busy} onclick={() => void task(() => startAgentFromPicker(a.id))}>
      {#if !a.path}<Download size={14} />{/if}{a.path
        ? host?.kind === 'powershell'
          ? 'Start in new PowerShell session'
          : 'Start in new tmux session'
        : 'Install and start'}</button
    >
  </div>{/each}<button onclick={() => void task(() => refresh())}
  ><RefreshCw size={14} /> Refresh discovery</button
>
