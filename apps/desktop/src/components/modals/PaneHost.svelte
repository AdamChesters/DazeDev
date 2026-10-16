<script lang="ts">
  import type { Device } from '../../lib/types';
  import type { Task } from './types';
  let {
    busy,
    connections,
    device,
    switchHost = $bindable(),
    switchPaneHost,
    task,
    voiceStatus,
  }: {
    busy: boolean;
    connections: Record<string, string>;
    device: Device;
    switchHost: string;
    switchPaneHost: () => Promise<void>;
    task: Task;
    voiceStatus: string;
  } = $props();
</script>

<p>
  This pane stays in its workspace. Its current tmux session stays on the original host; a
  plain shell will close.
</p>
<label
  >Connect this pane to<select bind:value={switchHost} disabled={busy}>
    {#each device.hosts as h}<option
        value={h.id}
        disabled={connections[h.id] !== 'Connected'}
        >{h.name}{connections[h.id] !== 'Connected' ? ' (not connected)' : ''}</option
      >{/each}
  </select></label
>
<p class="hint">
  The new connection starts with a plain shell. Use Tmux to join or create a session on that
  host.
</p>
<button
  class="primary"
  disabled={busy || voiceStatus !== 'idle'}
  onclick={() => void task(switchPaneHost)}>Change connection</button
>
