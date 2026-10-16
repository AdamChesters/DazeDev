<script lang="ts">
  import type { Task } from './types';
  let {
    closeAnyway,
    closeFailure = $bindable(),
    task,
  }: {
    closeAnyway: () => Promise<void>;
    closeFailure: string;
    task: Task;
  } = $props();
</script>

<h2 id="close-failure-title">DazeDev could not check everything before closing</h2>
<p class="inline-error">{closeFailure}</p>
<p class="hint">
  Close anyway ends this window now. Remote tmux sessions keep running; local PowerShell sessions
  may be left in the background.
</p>
<div class="row">
  <button class="danger" onclick={() => void task(closeAnyway)}>Close anyway</button>
  <button onclick={() => (closeFailure = '')}>Cancel</button>
</div>
