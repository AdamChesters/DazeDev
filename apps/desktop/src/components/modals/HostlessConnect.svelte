<script lang="ts">
  import type { Host, Workspace } from '../../lib/types';
  import type { Task } from './types';
  import { workspaceHosts } from '../../lib/workspace-groups';
  let {
    addHost,
    connect,
    hostlessConnect = $bindable(),
    hostlessNeeds,
    task,
  }: {
    addHost: () => void;
    connect: (h: Host) => Promise<void>;
    hostlessConnect: Workspace | null;
    hostlessNeeds: ReturnType<typeof workspaceHosts>;
    task: Task;
  } = $props();
</script>

<h2>{hostlessConnect?.name}</h2>
<p>
  {hostlessNeeds.required.length
    ? `Connect to ${hostlessNeeds.required.length} hosts to open this group's terminals?`
    : 'Some hosts need to be set up on this device.'}
</p>
{#each hostlessNeeds.required as required}<p>{required.name}</p>{/each}
{#if hostlessNeeds.missing.length}<p class="hint">
    Add these hosts with their original user and tmux socket: {hostlessNeeds.missing.join(', ')}.
  </p>{/if}
<div class="row">
  {#if hostlessNeeds.required.length}<button
      class="primary"
      onclick={() => {
        const hosts = hostlessNeeds!.required;
        hostlessConnect = null;
        void task(async () => {
          await Promise.all(hosts.map((h) => connect(h)));
        });
      }}>Connect to {hostlessNeeds.required.length} hosts</button
    >{/if}
  {#if hostlessNeeds.missing.length}<button
      onclick={() => {
        hostlessConnect = null;
        addHost();
      }}>Add host</button
    >{/if}
  <button onclick={() => (hostlessConnect = null)}>Keep disconnected</button>
</div>
