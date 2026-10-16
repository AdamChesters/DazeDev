<script lang="ts">
  let {
    modal = $bindable(),
    confirmPendingClose,
  }: {
    modal: string;
    confirmPendingClose: () => void;
  } = $props();
</script>

<p>
  Closing a plain terminal stops its shell and may stop running work. Attached tmux sessions
  keep running.
</p>
<div class="form-row">
  <button onclick={() => (modal = '')}>Cancel</button><button
    class="danger"
    onclick={confirmPendingClose}>Close terminal</button
  >
</div>
