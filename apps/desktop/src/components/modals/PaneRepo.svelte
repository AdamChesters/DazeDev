<script lang="ts">
  import RepoPicker from '../../components/RepoPicker.svelte';
  import type { Device, Pane, Repo } from '../../lib/types';
  import type { Task } from './types';
  let {
    busy,
    connections,
    device,
    pasteRepoPrompt,
    promptFor,
    refreshRepoChoices,
    repoChoices,
    repoHost = $bindable(),
    repoPath = $bindable(),
    repoTarget,
    repoWarnings,
    task,
  }: {
    busy: boolean;
    connections: Record<string, string>;
    device: Device;
    pasteRepoPrompt: () => Promise<void>;
    promptFor: (p: Pane) => string;
    refreshRepoChoices: () => Promise<void>;
    repoChoices: Repo[];
    repoHost: string;
    repoPath: string;
    repoTarget: Pane;
    repoWarnings: string[];
    task: Task;
  } = $props();
</script>

<label
  >Repository host<select
    bind:value={repoHost}
    disabled={busy}
    onchange={() => {
      repoPath = '';
      void task(refreshRepoChoices);
    }}
  >
    {#each device.hosts as h}<option value={h.id} disabled={connections[h.id] !== 'Connected'}
        >{h.name}</option
      >{/each}
  </select></label
>
<p class="hint">
  Paste the configured orientation prompt into this existing terminal. Choose a matching host if you
  have manually opened a nested SSH connection.
</p>
<RepoPicker
  repos={repoChoices}
  hostId={repoHost}
  bind:value={repoPath}
  warnings={repoWarnings}
  disabled={busy}
/>
{#if repoPath}<label
    >Prompt preview<textarea
      readonly
      rows="7"
      value={promptFor({ ...repoTarget, hostId: repoHost, repo: repoPath })}></textarea></label
  >{/if}
<div class="form-row">
  <button disabled={busy} onclick={() => void task(refreshRepoChoices)}>Refresh repositories</button
  >
  <button class="primary" disabled={busy || !repoPath} onclick={() => void task(pasteRepoPrompt)}
    >Paste for review</button
  >
</div>
