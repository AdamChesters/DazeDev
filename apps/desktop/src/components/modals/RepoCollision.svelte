<script lang="ts">
  let {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    useSharedWorkingCopy,
  }: {
    modal: string;
    useSharedWorkingCopy: () => void;
  } = $props();
</script>

<p>
  Another agent is using this working copy. Concurrent edits can conflict. Separate
  worktrees still share external ports and databases.
</p>
<div class="form-row">
  <button onclick={() => (modal = 'new-session')}>Choose another working copy</button
  ><button
    onclick={useSharedWorkingCopy}>Use shared working copy</button
  >
</div>
