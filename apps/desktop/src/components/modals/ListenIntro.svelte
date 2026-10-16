<script lang="ts">
  import type { Device } from '../../lib/types';
  import type { Shortcut } from '../../lib/shortcut';
  import type { Task, VoiceMode } from './types';
  import { tick } from 'svelte';
  let {
    bindingFor,
    device = $bindable(),
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    openSettings,
    startListening,
    task,
    voiceReady,
  }: {
    bindingFor: (mode: VoiceMode) => Shortcut;
    device: Device;
    modal: string;
    openSettings: () => Promise<void>;
    startListening: () => Promise<void>;
    task: Task;
    voiceReady: boolean;
  } = $props();
</script>

<h2>Speak into your terminal</h2>
<p>
  Select a terminal, then tap <strong>{bindingFor('dictation').label}</strong> to listen. In toggle mode,
  tap again to stop and review the text in that terminal. Double-tap the same shortcut to send it with
  Enter.
</p>
<p>
  Keyboard shortcuts and mouse or device buttons work while DazeDev is focused. You can also choose
  hold-to-talk: hold to record, release to stop.
</p>
<p class="hint">
  Transcription and optional tidy-up run locally. {voiceReady
    ? 'Your voice models are ready.'
    : 'The selected voice stack needs a one-time download. Installed models are reused.'}
</p>
{#if device.voice.automatic}<p class="hint">
    Automatic Enter is currently enabled. Dictation will submit as soon as transcription finishes.
    Turn it off in settings to review first.
  </p>{/if}
<div class="row">
  <button
    class="primary"
    onclick={() => {
      device.listenOnboarded = true;
      if (!voiceReady) {
        modal = 'voice-setup';
        return;
      }
      modal = '';
      void task(startListening);
    }}>{voiceReady ? 'Start listening' : 'Set up local voice'}</button
  >
  <button
    onclick={() => {
      device.listenOnboarded = true;
      void task(async () => {
        await openSettings();
        await tick();
        const settings = document.querySelector<HTMLDetailsElement>('#voice-shortcuts');
        if (settings) {
          settings.open = true;
          settings.scrollIntoView({ block: 'start' });
        }
      });
    }}>Change keyboard or mouse shortcut</button
  >
  <button
    onclick={() => {
      device.listenOnboarded = true;
      modal = '';
    }}>Later</button
  >
</div>
