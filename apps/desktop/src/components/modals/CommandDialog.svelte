<script lang="ts">
  import { ArrowUpRight, Mic } from '@lucide/svelte';
  import type { CaptureMode } from '../../lib/voice-routing';
  import type { CommandReview } from '../../lib/command-review';
  import type { Task } from './types';
  import { commandCatalogue } from '../../lib/commands';
  let {
    busy,
    commandError = $bindable(),
    commandReview = $bindable(),
    commandSubmit,
    commandText = $bindable(),
    implementCommand,
    record,
    task,
    voiceMode,
    voicePreparing,
    voiceStatus,
  }: {
    busy: boolean;
    commandError: string;
    commandReview: CommandReview | null;
    commandSubmit: () => void;
    commandText: string;
    implementCommand: () => Promise<void>;
    record: (mode: CaptureMode, target?: string) => Promise<void>;
    task: Task;
    voiceMode: CaptureMode;
    voicePreparing: string;
    voiceStatus: string;
  } = $props();
</script>

<form
  onsubmit={(e) => {
    e.preventDefault();
    commandSubmit();
  }}
>
  <p role="note">
    <strong>Early alpha: commands may be interpreted incorrectly. Review before running.</strong>
  </p>
  <p class="hint">
    IDE commands control DazeDev. They are never pasted into a terminal. While listening, Enter
    finishes transcription and Escape abandons it.
  </p>
  <label
    >Command<textarea
      bind:value={commandText}
      rows="3"
      onkeydown={(e) => {
        if (
          e.key === 'Enter' &&
          !e.repeat &&
          !e.isComposing &&
          e.keyCode !== 229 &&
          !e.shiftKey &&
          !e.ctrlKey &&
          !e.altKey &&
          !e.metaKey
        ) {
          e.preventDefault();
          commandSubmit();
        }
      }}
      readonly={voiceStatus !== 'idle'}
      oninput={() => {
        commandReview = null;
        commandError = '';
      }}
      placeholder="Open four new terminals with Codex"></textarea></label
  >
  {#if voiceMode === 'command' && voiceStatus !== 'idle'}<p role="status">
      {voiceStatus === 'finishing-recording'
        ? 'Finishing recording…'
        : voiceStatus === 'listening'
          ? 'Listening…'
          : voiceStatus === 'loading'
            ? voicePreparing
            : 'Finishing transcription…'}
    </p>{/if}
  {#if commandError}<p role="alert">{commandError}</p>{/if}
  {#if commandReview}
    <section class="settings-section" aria-label="Proposed command">
      <h3>Proposed action</h3>
      <p>{commandReview.summary}</p>
    </section>
    <div class="form-row">
      <button
        type="button"
        onclick={() => {
          commandReview = null;
          commandError = '';
        }}>Decline / edit</button
      >
      <button
        type="button"
        class="primary"
        disabled={busy || voiceStatus !== 'idle'}
        onclick={() => void implementCommand()}>Implement</button
      >
    </div>
  {:else}
    <div class="form-row">
      <button
        type="button"
        disabled={voiceStatus !== 'idle' && voiceStatus !== 'listening'}
        onclick={() => void task(() => record('command'))}
        ><Mic size={15} />{voiceMode === 'command' && voiceStatus === 'listening'
          ? 'Stop and review'
          : 'Speak command'}</button
      >
      <button class="primary" disabled={!commandText.trim() || busy || voiceStatus !== 'idle'}
        >Interpret</button
      >
    </div>
    <details>
      <summary>Command library</summary>
      <p class="hint">
        Choose an example, edit it, then interpret. “Codecs” is accepted as Codex here.
      </p>
      <div class="command-examples">
        {#each commandCatalogue as command}<button
            type="button"
            disabled={voiceStatus !== 'idle'}
            title={command.description}
            onclick={() => {
              commandText = command.example;
              commandReview = null;
              commandError = '';
            }}>{command.example}<ArrowUpRight size={13} /></button
          >{/each}
      </div>
    </details>
  {/if}
</form>
