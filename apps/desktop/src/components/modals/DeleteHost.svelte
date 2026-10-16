<script lang="ts">
  import type { Host } from '../../lib/types';
  import type { Task } from './types';
  let {
    busy,
    deleteHost,
    deleteHostTarget,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    task,
  }: {
    busy: boolean;
    deleteHost: (h: Host, confirmed?: boolean) => Promise<void>;
    deleteHostTarget: Host;
    modal: string;
    task: Task;
  } = $props();
</script>

<p>
  Remove <strong>{deleteHostTarget.name}</strong> and its workspace list from this device?
</p>
<p class="hint">
  Plain shells close and tmux terminals detach. Remote tmux sessions and the host's saved workspace
  configuration stay intact.
</p>
<div class="form-row">
  <button disabled={busy} onclick={() => (modal = '')}>Cancel</button><button
    class="danger"
    disabled={busy}
    onclick={() => void task(() => deleteHost(deleteHostTarget!))}>Delete host</button
  >
</div>
