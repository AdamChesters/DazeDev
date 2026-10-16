<script lang="ts">
  import { Check, Download, Trash2 } from '@lucide/svelte';
  import type { Device, ModelInfo } from '../../../lib/types';
  import type { Task } from '../types';
  import { type VoiceHardware, accelerationAdvice, recommendVoice } from '../../../lib/hardware';
  import { modelDescriptions } from '../../../lib/voice-setup';
  let {
    busy,
    changeVoiceModel,
    device = $bindable(),
    downloadModel,
    downloadProgress,
    hardware,
    hardwareError,
    models,
    modelsLoaded,
    recommendation,
    removeModel,
    selectVoiceModel,
    setupVoice,
    switchingVoice,
    task,
    voiceCacheLoading,
    voiceReady,
    voiceStatus,
  }: {
    busy: boolean;
    changeVoiceModel: () => Promise<void>;
    device: Device;
    downloadModel: (id: string) => Promise<void>;
    downloadProgress: Record<string, string>;
    hardware: VoiceHardware | null;
    hardwareError: string;
    models: ModelInfo[];
    modelsLoaded: boolean;
    recommendation: ReturnType<typeof recommendVoice> | null;
    removeModel: (id: string, release: boolean) => Promise<void>;
    selectVoiceModel: (id: string) => Promise<void>;
    setupVoice: () => Promise<void>;
    switchingVoice: boolean;
    task: Task;
    voiceCacheLoading: boolean;
    voiceReady: boolean;
    voiceStatus: string;
  } = $props();
</script>

<div class="voice-readiness" role="status">
  <span class="readiness-light" class:ready={voiceReady}></span>
  <strong
    >{voiceReady
      ? 'Voice ready'
      : modelsLoaded
        ? 'Voice setup incomplete'
        : 'Checking voice models…'}</strong
  >
</div>
<details class="settings-section settings-disclosure" open={!voiceReady}>
  <summary
    >Voice models <span
      >{models.find((m) => m.id === device.voice.model)?.name || 'Setup required'}</span
    ></summary
  >
  {#if hardware && recommendation}
    <p class="hint">
      {hardware.logicalCpus} CPU threads · {hardware.memory
        ? `${(hardware.memory / 1024 ** 3).toFixed(0)} GB RAM`
        : 'RAM unknown'} · {hardware.vulkan ? 'Vulkan GPU available' : 'CPU only'}
    </p>
    <p class="hint">{recommendation.reason} Hardware guidance, not a speed benchmark.</p>
  {:else}<p class="hint">{hardwareError || 'Detecting hardware…'}</p>{/if}
  <div class="model-list" role="radiogroup" aria-label="Transcription model">
    {#each models.filter((m) => m.family === 'asr') as m}
      <div class="model-row" class:selected={device.voice.model === m.id}>
        <input
          type="radio"
          name="voice-model"
          aria-label={`Select ${m.name}${m.installed ? '' : ' (download required)'}`}
          checked={device.voice.model === m.id}
          disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading || busy}
          onclick={(e) => {
            e.preventDefault();
            void task(() => selectVoiceModel(m.id));
          }}
        />
        <div>
          <strong>{m.name}</strong>{#if recommendation?.model === m.id}<span class="recommended"
              >Recommended</span
            >{/if}
          <small>{Math.round(m.size / 1e6)} MB · {modelDescriptions[m.id]}</small>
          {#if hardware && device.voice.model === m.id}<small
              >{accelerationAdvice(hardware, m.id, device.voice.backend)}</small
            >{/if}
        </div>
        {#if m.installed}<span class="installed"><Check size={13} /> Installed</span>
          <button
            class="icon"
            aria-label={`Remove ${m.name}`}
            disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading || busy}
            onclick={() => void task(() => removeModel(m.id, true))}><Trash2 size={14} /></button
          >
        {:else}<button
            disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading || busy}
            onclick={() => void task(() => selectVoiceModel(m.id))}
            ><Download size={14} />{downloadProgress[m.id] || 'Download & select'}</button
          >{/if}
        {#if downloadProgress[m.id]}<progress
            aria-label={`${m.name} download`}
            max="100"
            value={downloadProgress[m.id].endsWith('%')
              ? parseInt(downloadProgress[m.id])
              : undefined}
          ></progress>{/if}
      </div>
    {/each}
  </div>
  {#if switchingVoice}<p role="status">
      {Object.keys(downloadProgress).length ? 'Downloading model…' : 'Switching model…'}
    </p>{/if}
  <label
    >Acceleration<select
      bind:value={device.voice.backend}
      disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
      onchange={() => void changeVoiceModel()}
    >
      <option value="auto">Automatic</option><option value="cpu">CPU</option><option
        value="vulkan"
        disabled={!!hardware && !hardware.vulkan}>GPU · Vulkan</option
      >
    </select></label
  >
  <details>
    <summary>Supporting models · Silero / S1-mini</summary>
    <div class="model-list">
      {#each models.filter((m) => m.family !== 'asr') as m}<div class="model-row">
          <div>
            <strong>{m.name}</strong><small>{Math.round(m.size / 1e6)} MB · {m.license}</small>
          </div>
          {#if m.installed}<span class="installed"><Check size={13} /> Installed</span><button
              class="icon"
              aria-label={`Remove ${m.name}`}
              disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading || busy}
              onclick={() => void task(() => removeModel(m.id, false))}><Trash2 size={14} /></button
            >{:else}<button
              disabled={!!downloadProgress[m.id]}
              onclick={() => void task(() => downloadModel(m.id))}
              ><Download size={14} />{downloadProgress[m.id] || 'Download'}</button
            >{/if}
          {#if downloadProgress[m.id]}<progress
              aria-label={`${m.name} download`}
              max="100"
              value={downloadProgress[m.id].endsWith('%')
                ? parseInt(downloadProgress[m.id])
                : undefined}
            ></progress>{/if}
        </div>{/each}
    </div>
  </details>
  {#if !voiceReady}<button
      disabled={busy || switchingVoice || voiceStatus !== 'idle'}
      onclick={() => void task(setupVoice)}
      ><Download size={14} /> Install missing stack components</button
    >{/if}
</details>
