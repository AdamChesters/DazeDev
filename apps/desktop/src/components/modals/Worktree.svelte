<script lang="ts">
  import type { Task } from './types';
  let {
    branch = $bindable(),
    busy,
    createWorktree,
    destination = $bindable(),
    sendWorktree,
    task,
    worktreeQuestion = $bindable(),
  }: {
    branch: string;
    busy: boolean;
    createWorktree: () => Promise<void>;
    destination: string;
    sendWorktree: (runScript?: boolean) => Promise<void>;
    task: Task;
    worktreeQuestion: { script: string } | null;
  } = $props();
</script>

{#if worktreeQuestion}<div
    class="worktree-question"
    role="group"
    aria-labelledby="worktree-question-text"
  >
    <p id="worktree-question-text">
      This repository has {worktreeQuestion.script}. Run it to create the worktree?
    </p>
    <div class="row">
      <button class="primary" disabled={busy} onclick={() => void task(() => sendWorktree(true))}
        >Run script</button
      >
      <button disabled={busy} onclick={() => void task(() => sendWorktree(false))}
        >Create without script</button
      >
      <button disabled={busy} onclick={() => (worktreeQuestion = null)}>Cancel</button>
    </div>
  </div>{:else}<form
    onsubmit={(e) => {
      e.preventDefault();
      void task(createWorktree);
    }}
  >
    <label>Branch<input bind:value={branch} required /></label><label
      >Worktree directory<input bind:value={destination} required /></label
    >
    <p class="hint">Asks first when the repository provides scripts/new-worktree.sh.</p>
    <button class="primary" disabled={busy}>Create worktree</button>
  </form>{/if}
