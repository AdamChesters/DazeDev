<script lang="ts">
  import { pasteLength } from '../lib/paste';
  import TextEditor from './TextEditor.svelte';
  // Editable text over the terminal: the large-paste preview and a file an
  // agent opened with $VISUAL/$EDITOR. Until the user edits, submitting
  // returns the original text unchanged, line endings included.
  let {
    title,
    hint,
    text,
    submitLabel,
    dontAsk,
    busy = false,
    error = '',
    onsubmit,
    oncancel,
  }: {
    title: string;
    hint: string;
    text: string;
    submitLabel: string;
    /** Shown as a "Don't ask again" checkbox when set. */
    dontAsk?: boolean;
    busy?: boolean;
    error?: string;
    onsubmit: (text: string, dontAsk: boolean) => void;
    oncancel: () => void;
  } = $props();
  let edited = $state<string | null>(null);
  let skipNext = $state(false);
  const current = $derived(edited ?? text);
  const characters = $derived(pasteLength(current));
  function submit() {
    if (!busy) onsubmit(current, skipNext);
  }
  function cancel() {
    if (!busy) oncancel();
  }
</script>

<div class="edit-pane" role="dialog" aria-modal="true" aria-label={title}>
  <div class="heading">
    <h3>{title}</h3>
    <span class="count"
      >{characters.toLocaleString()} characters{edited !== null ? ' · edited' : ''}</span
    >
  </div>
  <p class="hint">{hint}</p>
  <TextEditor
    value={text}
    label={title}
    onchange={(value) => (edited = value)}
    onsubmit={submit}
    oncancel={cancel}
  />
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="actions">
    {#if dontAsk !== undefined}<label class="check"
        ><input type="checkbox" bind:checked={skipNext} /> Don't ask again</label
      >{/if}
    <span class="keys">Ctrl+Enter {submitLabel.toLowerCase()} · Esc cancel</span>
    <button class="primary" disabled={busy} onclick={submit}>{submitLabel}</button>
    <button disabled={busy} onclick={cancel}>Cancel</button>
  </div>
</div>

<style>
  .edit-pane {
    position: absolute;
    z-index: 6;
    inset: 8px 8px 32px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--panel);
    color: var(--text);
    box-shadow: 0 10px 30px #0006;
  }
  .heading {
    display: flex;
    align-items: baseline;
    gap: 8px;
  }
  h3 {
    margin: 0;
    font-size: 13px;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .count,
  .keys {
    font-size: 11px;
    opacity: 0.75;
    white-space: nowrap;
  }
  .hint,
  .error {
    margin: 0;
    font-size: 12px;
  }
  .error {
    color: #e8a39b;
  }
  .actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .keys {
    margin-left: auto;
  }
  @container (max-width: 420px) {
    .keys {
      display: none;
    }
  }
</style>
