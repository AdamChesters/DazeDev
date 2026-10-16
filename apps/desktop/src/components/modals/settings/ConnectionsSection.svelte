<script lang="ts">
  import ClipboardOptions from './ClipboardOptions.svelte';
  import type { Device, Host } from '../../../lib/types';
  import { Plus } from '@lucide/svelte';
  import type { Task } from '../types';
  import type { TerminalSettings } from '../../../lib/terminals';
  let {
    addHost,
    connections = $bindable(),
    device = $bindable(),
    disconnectHost,
    modal = $bindable(),
    newHost = $bindable(),
    persistDevice,
    settingsHost,
    task,
  }: {
    addHost: () => void;
    connections: Record<string, string>;
    device: Device;
    disconnectHost: (hostId: string) => Promise<void>;
    modal: string;
    newHost: Host;
    persistDevice: () => void;
    settingsHost: string;
    task: Task;
  } = $props();
</script>

<details class="settings-section settings-disclosure">
  <summary>Connections <span>{device.hosts.length} hosts</span></summary>
  <p class="hint">
    Windows local terminals can use native PowerShell or WSL. Native sessions support
    background persistence and same-process handoff to a PowerShell window. Remote
    terminals use the host login shell.
  </p>
  {#if !device.hosts.length}<p class="hint">No hosts configured.</p>{/if}
  <button onclick={addHost}><Plus size={14} /> Add host</button>
  {#each device.hosts as h}<div class="item-row">
      <div><strong>{h.name}</strong><small>{h.address || h.kind}</small></div>
      <button
        onclick={() => {
          newHost = { ...h, nativeShell: h.nativeShell || 'powershell' };
          modal = 'host';
        }}>Edit</button
      ><button
        onclick={() =>
          void task(async () => {
            await disconnectHost(h.id);
            connections[h.id] = 'Disconnected';
          })}>Disconnect</button
      >
    </div>{/each}
  {#if settingsHost}<label
      >Clipboard access for selected host<select
        value={device.clipboardByHost?.[settingsHost] ||
          device.terminal?.clipboard ||
          'notify'}
        onchange={(e) => {
          (device.clipboardByHost ||= {})[settingsHost] = e.currentTarget
            .value as TerminalSettings['clipboard'];
          persistDevice();
        }}
        ><ClipboardOptions /></select
      ></label
    >{/if}
</details>
