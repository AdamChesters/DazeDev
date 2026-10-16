<script lang="ts">
  import type { Task } from './types';
  let {
    busy,
    closeAnyway,
    closingApp,
    finishNativeClose,
    cancelNativeClose,
    task,
  }: {
    busy: boolean;
    closeAnyway: () => Promise<void>;
    closingApp: boolean;
    finishNativeClose: (action: 'end' | 'background' | 'external') => Promise<void>;
    cancelNativeClose: () => void;
    task: Task;
  } = $props();
</script>

<p>These PowerShell sessions may have running work. Choose what happens when this view closes.</p>
<p class="hint">
  Background sessions can be reattached from Sessions. Continuing in a PowerShell window keeps the
  same process and waits for the window to attach.
</p>
<div class="native-close-actions">
  <button disabled={busy} onclick={() => void task(() => finishNativeClose('background'))}
    >Keep in background</button
  >
  <button disabled={busy} onclick={() => void task(() => finishNativeClose('external'))}
    >Continue in PowerShell window</button
  >
  <button class="danger" disabled={busy} onclick={() => void task(() => finishNativeClose('end'))}
    >End sessions</button
  >
  {#if closingApp}<button disabled={busy} onclick={() => void task(closeAnyway)}
      >Close anyway</button
    >{/if}
  <button disabled={busy} onclick={cancelNativeClose}>Cancel</button>
</div>
