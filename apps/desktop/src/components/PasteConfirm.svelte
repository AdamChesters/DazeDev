<script lang="ts">
  import { pastePreview } from '../lib/paste';
  let {
    text,
    onpaste,
    oncancel,
  }: {
    text: string;
    onpaste: (dontAsk: boolean) => void;
    oncancel: () => void;
  } = $props();
  const preview = $derived(pastePreview(text));
  let dontAsk = $state(false);
  let pasteButton = $state<HTMLButtonElement>();
  $effect(() => {
    pasteButton?.focus();
  });
</script>

<div
  class="paste-confirm"
  role="alertdialog"
  aria-modal="true"
  aria-labelledby="paste-confirm-title"
  tabindex="-1"
  onkeydown={(event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      oncancel();
    }
  }}
>
  <h3 id="paste-confirm-title">
    Paste {preview.count}
    {preview.count === 1 ? 'line with a line break' : 'lines'}?
  </h3>
  <p class="hint">Line breaks can run commands as soon as they are pasted.</p>
  <pre>{preview.lines.join('\n')}{preview.more ? `\n… ${preview.more} more` : ''}</pre>
  <label class="check"
    ><input type="checkbox" bind:checked={dontAsk} /> Don't ask again</label
  >
  <div class="actions">
    <button bind:this={pasteButton} class="primary" onclick={() => onpaste(dontAsk)}>Paste</button>
    <button onclick={oncancel}>Cancel</button>
  </div>
</div>

<style>
  .paste-confirm {
    position: absolute;
    z-index: 6;
    left: 8px;
    right: 8px;
    bottom: 32px;
    max-height: calc(100% - 40px);
    overflow: auto;
    padding: 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--panel);
    color: var(--text);
    box-shadow: 0 10px 30px #0006;
  }
  h3 {
    margin: 0 0 4px;
    font-size: 13px;
  }
  pre {
    margin: 6px 0;
    padding: 6px;
    max-height: 9em;
    overflow: auto;
    background: var(--bg);
    border: 1px solid var(--border);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-size: 12px;
  }
  .actions {
    display: flex;
    gap: 6px;
    margin-top: 6px;
  }
</style>
