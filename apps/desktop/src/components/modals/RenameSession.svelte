<script lang="ts">
  import type { Session } from '../../lib/types';
  import type { Task } from './types';
  let {
    busy,
    renameSession,
    renameValue = $bindable(),
    task,
  }: {
    busy: boolean;
    renameSession: () => Promise<void>;
    renameValue: string;
    task: Task;
  } = $props();
</script>

<form
  onsubmit={(e) => {
    e.preventDefault();
    void task(renameSession);
  }}
>
  <label
    >Session name<input bind:value={renameValue} required pattern="[A-Za-z0-9_-]+" /></label
  ><button class="primary" disabled={busy}>Rename</button>
</form>
