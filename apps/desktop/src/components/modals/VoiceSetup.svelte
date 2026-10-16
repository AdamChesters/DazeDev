<script lang="ts">
  import type { Device, ModelInfo } from '../../lib/types';
  import type { Task } from './types';
  import { modelDescriptions } from '../../lib/voice-setup';
  let {
    busy,
    changeVoiceModel,
    device = $bindable(),
    downloadProgress,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- write-only $bindable prop
    modal = $bindable(),
    models,
    openSettings,
    setupVoice,
    switchingVoice,
    task,
    voiceCacheLoading,
    voiceStatus,
  }: {
    busy: boolean;
    changeVoiceModel: () => Promise<void>;
    device: Device;
    downloadProgress: Record<string, string>;
    modal: string;
    models: ModelInfo[];
    openSettings: () => Promise<void>;
    setupVoice: () => Promise<void>;
    switchingVoice: boolean;
    task: Task;
    voiceCacheLoading: boolean;
    voiceStatus: string;
  } = $props();
</script>

<p>
  Voice models are downloaded separately from DazeDev to keep the app small and let you choose your
  stack. Downloads use the verified versions supported by this release.
</p>
<p class="hint">
  Default stack: Parakeet transcription, Silero silence detection and S1-mini cleanup. Download
  once; everything runs offline on this device with no account or per-use cost.
</p>
<p>
  Selected: <strong
    >{models.find((m) => m.id === device.voice.model)?.name || device.voice.model}</strong
  >
</p>
<button onclick={() => void task(openSettings)}>Choose a model in Settings</button>
<div class="stack-options">
  {#each models.filter((m) => m.family === 'asr') as m}<p>
      <strong>{m.name}</strong>
      {modelDescriptions[m.id]}
    </p>{/each}
</div>
<p><strong>Silero:</strong> {modelDescriptions.silero}</p>
<label class="check"
  ><input
    type="checkbox"
    bind:checked={device.voice.cleanup}
    disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
    onchange={() => void changeVoiceModel()}
  /> S1-mini cleanup</label
>
<p class="hint">
  {modelDescriptions['s1-mini']} Review important names and commands before pressing Enter.
</p>
<div class="form-row">
  <button
    disabled={busy}
    onclick={() => {
      device.voiceSetupDismissed = true;
      modal = '';
    }}>Later</button
  >
  <button
    class="primary"
    disabled={busy}
    onclick={() =>
      void task(async () => {
        await setupVoice();
        modal = '';
      })}>{busy ? 'Setting up…' : 'Download selected voice stack'}</button
  >
</div>
{#each Object.entries(downloadProgress) as [id, progress]}<div
    class="download-progress"
    role="status"
  >
    <span>{id}: {progress}</span><progress
      aria-label={`${id} download`}
      max="100"
      value={progress.endsWith('%') ? parseInt(progress) : undefined}
    ></progress>
  </div>{/each}
