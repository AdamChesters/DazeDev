<script lang="ts">
  import { openExternal } from '../lib/bridge';
  import { webUrl } from '../lib/terminals';
  let { url }: { url: string } = $props();
  let error = $state('');
  const safe = $derived(webUrl(url));
</script>

<div class="remote-image" role="group" aria-label="Remote image not loaded">
  <p>Remote image not loaded. DazeDev only shows images from your hosts.</p>
  <code>{url}</code>
  {#if safe}<button
      onclick={() =>
        void openExternal(safe).catch((e) => {
          error = String(e);
        })}>Open in browser</button
    >{/if}
  {#if error}<p role="alert">{error}</p>{/if}
</div>

<style>
  .remote-image {
    display: grid;
    gap: 6px;
    justify-items: start;
    padding: 12px;
    border: 1px dashed var(--border);
    border-radius: 6px;
    background: var(--panel);
  }
  .remote-image p {
    margin: 0;
  }
  code {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
</style>
