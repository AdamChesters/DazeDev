<script lang="ts">
  import type { BroadcastSnapshot } from '../lib/broadcast';
  let {
    text = $bindable(''),
    allHosts = $bindable(true),
    allWorkspaces = $bindable(true),
    hostName,
    workspaceName,
    phase,
    busy,
    snapshot,
    result,
    onrecord,
    onreview,
    onsend,
    onclose,
  }: {
    text: string;
    allHosts: boolean;
    allWorkspaces: boolean;
    hostName: string;
    workspaceName: string;
    phase: string;
    busy: boolean;
    snapshot: BroadcastSnapshot | null;
    result: string;
    onrecord: () => void;
    onreview: () => void;
    onsend: () => void;
    onclose: () => void;
  } = $props();
</script>

<p>
  Use this when you're returning to long running sessions and want them all to let you know current
  status, want to tell all sessions to wrap up, update docs, and sync to git before you go to bed,
  or anything else you can't be bothered typing/copying across every window.
</p>
<p class="hint">
  <strong
    >Broadcast pastes your message and presses Enter in every matching terminal, including bare
    shells.</strong
  > Shells may execute this text as commands. Review your message and recipients before sending.
</p>
<div class="scope">
  <label
    ><input type="checkbox" bind:checked={allHosts} disabled={busy || !!result} /> All hosts</label
  >
  <span>{allHosts ? 'Every source host' : `Only ${hostName}`}</span>
  <label
    ><input type="checkbox" bind:checked={allWorkspaces} disabled={busy || !!result} /> All workspaces</label
  >
  <span>{allWorkspaces ? 'Every workspace' : `Only ${workspaceName}`}</span>
</div>
<label
  >Message<textarea
    rows="7"
    bind:value={text}
    disabled={phase !== 'idle' || busy || !!result}
    placeholder="Type a message or dictate it…"></textarea></label
>
<p class="hint">
  Either voice hotkey dictates into this message. Enter finishes recording; Escape cancels
  recording. Sending always requires the button below.
</p>
{#if phase !== 'idle'}<p role="status">
    {phase === 'listening' ? 'Listening… press Enter to finish.' : phase}
  </p>{/if}
{#if snapshot}
  <p>
    <strong>{snapshot.recipients.length} terminal sessions</strong>; {snapshot.mirrors} mirrored panes
    deduplicated; {snapshot.skipped.length} disconnected or changed panes skipped.
  </p>
  <ul>
    {#each snapshot.recipients as recipient}<li>{recipient.label}</li>{/each}
  </ul>
  {#if snapshot.skipped.length}<details>
      <summary>Skipped panes</summary>
      <ul>
        {#each snapshot.skipped as label}<li>{label}</li>{/each}
      </ul>
    </details>{/if}
{/if}
{#if result}<p role="status" class="result">{result}</p>{/if}
<div class="actions">
  <button disabled={busy} onclick={onclose}>Close</button>
  {#if !result}
    <button disabled={busy || !['idle', 'listening'].includes(phase)} onclick={onrecord}
      >{phase === 'listening' ? 'Finish recording' : 'Dictate message'}</button
    >
    {#if snapshot}<button
        class="primary"
        disabled={busy || phase !== 'idle' || !snapshot.recipients.length || !text.trim()}
        onclick={onsend}>Send to all {snapshot.recipients.length} terminal sessions</button
      >
    {:else}<button
        class="primary"
        disabled={busy || phase !== 'idle' || !text.trim()}
        onclick={onreview}>Review recipients</button
      >{/if}
  {/if}
</div>

<style>
  .scope {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 0.6rem 1rem;
    margin: 1rem 0;
  }
  .scope label {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .scope input {
    width: auto;
  }
  label {
    display: block;
  }
  textarea {
    display: block;
    width: 100%;
    resize: vertical;
    min-height: 8rem;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    justify-content: flex-end;
    margin-top: 1rem;
  }
  ul {
    max-height: 10rem;
    overflow: auto;
    padding-left: 1.3rem;
  }
  .result {
    white-space: pre-wrap;
  }
  .hint,
  .scope span {
    color: var(--muted);
  }
</style>
