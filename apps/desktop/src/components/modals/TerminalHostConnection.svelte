<script lang="ts">
  import type { Host } from '../../lib/types';
  import type { Task } from './types';
  let {
    connectHostTerminals,
    connectionTarget,
    connections,
    disconnectHostTerminals,
    task,
  }: {
    connectHostTerminals: (host: Host) => Promise<void>;
    connectionTarget: Host;
    connections: Record<string, string>;
    disconnectHostTerminals: (hostId: string) => Promise<void>;
    task: Task;
  } = $props();
</script>

<p>{connectionTarget.name}: {connections[connectionTarget.id] || 'Disconnected'}</p>
<p>
  This action applies to every terminal connected to this host. Managed PowerShell and tmux
  sessions keep running. Disconnecting ends plain shells; their visible output is retained.
</p>
<div class="form-row">
  <button
    disabled={connections[connectionTarget.id] === 'Connecting'}
    onclick={() => void task(() => connectHostTerminals(connectionTarget!))}
    >Connect host and terminals</button
  >
  <button onclick={() => void task(() => disconnectHostTerminals(connectionTarget!.id))}
    >Disconnect host and terminals</button
  >
</div>
