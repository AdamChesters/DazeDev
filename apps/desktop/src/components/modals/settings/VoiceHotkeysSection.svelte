<script lang="ts">
  import type { Device } from '../../../lib/types';
  import type { Shortcut } from '../../../lib/shortcut';
  import type { Task, VoiceMode } from '../types';
  let {
    bindingFor,
    captureShortcut,
    capturingShortcut,
    changeVoiceModel,
    device = $bindable(),
    microphoneError,
    microphoneLoading,
    microphones,
    persistDevice,
    refreshMicrophones,
    saveShortcut,
    shortcutStatus,
    switchingVoice,
    task,
    voiceCacheLoading,
    voiceStatus,
  }: {
    bindingFor: (mode: VoiceMode) => Shortcut;
    captureShortcut: (mode: VoiceMode) => void;
    capturingShortcut: VoiceMode | null;
    changeVoiceModel: () => Promise<void>;
    device: Device;
    microphoneError: string;
    microphoneLoading: boolean;
    microphones: MediaDeviceInfo[];
    persistDevice: () => void;
    refreshMicrophones: (requestPermission?: boolean) => Promise<void>;
    saveShortcut: (binding: Shortcut, mode?: VoiceMode) => Promise<void>;
    shortcutStatus: string;
    switchingVoice: boolean;
    task: Task;
    voiceCacheLoading: boolean;
    voiceStatus: string;
  } = $props();
</script>

<details class="settings-section settings-disclosure" id="voice-shortcuts">
  <summary
    >Voice hotkeys and input <span
      >{bindingFor('dictation').label} · {bindingFor('command').label}</span
    ></summary
  >
  <p class="hint">
    Choose separate shortcuts for terminal dictation and IDE commands. Capture stops if
    DazeDev loses focus.
  </p>
  <p>
    In toggle mode: tap to listen, tap to stop, then double-tap to submit the reviewed
    text. The terminal must still be unchanged. Hold-to-talk keeps its press and release
    behavior.
  </p>
  {#each ['dictation', 'command'] as mode}
    {@const voiceInput = mode as VoiceMode}
    <h4>{voiceInput === 'dictation' ? 'Terminal dictation' : 'IDE voice command'}</h4>
    <div class="hotkey-assignments">
      <div class="hotkey-record">
        <label
          >Keyboard / device<input readonly value={bindingFor(voiceInput).label} /></label
        >
        <button onclick={() => captureShortcut(voiceInput)}
          >{capturingShortcut === voiceInput ? 'Press hotkey…' : 'Record hotkey'}</button
        >
      </div>
      <label
        >Mouse / device<select
          value=""
          onchange={(e) => {
            const key = Number(e.currentTarget.value);
            if (key)
              void task(() =>
                saveShortcut(
                  {
                    key,
                    ctrl: false,
                    alt: false,
                    shift: false,
                    meta: false,
                    label:
                      e.currentTarget.selectedOptions[0].textContent || 'Device button',
                  },
                  voiceInput,
                ),
              );
          }}
        >
          <option value="">Choose a button</option><option value="4"
            >Middle mouse button</option
          ><option value="5">Mouse button 4</option><option value="6"
            >Mouse button 5</option
          ><option value="179">Media play/pause</option><option value="176"
            >Media next</option
          ><option value="177">Media previous</option><option value="173"
            >Volume mute</option
          >
        </select></label
      >
    </div>
  {/each}
  {#if shortcutStatus}<p role="status" class="hint">{shortcutStatus}</p>{/if}
  <details class="settings-advanced">
    <summary>Recording behavior and model memory</summary>
    <p class="hint">
      While listening, Enter finishes transcription and leaves dictated text for review,
      even when automatic submission is enabled. Escape abandons capture. These keys never
      submit a terminal prompt or implement an IDE command while recording.
    </p>
    <label
      >Activation<select bind:value={device.inputMode} onchange={persistDevice}
        ><option value="toggle">Press to start / stop</option><option value="hold"
          >Hold to talk</option
        ></select
      ></label
    >
    <div class="settings-checks">
      <label class="check"
        ><input
          type="checkbox"
          bind:checked={device.voice.cleanup}
          disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
          onchange={() => void changeVoiceModel()}
        /> S1-mini tidy-up</label
      ><label
        >Voice model memory<select
          value={device.voice.keepLoaded === false ? 'release' : 'ready'}
          disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
          onchange={(e) => {
            device.voice.keepLoaded = e.currentTarget.value === 'ready';
            void changeVoiceModel();
          }}
        >
          <option value="ready">Preload and keep all voice models ready</option>
          <option value="release">Release models after each recording</option>
        </select></label
      ><label class="check"
        ><input
          type="checkbox"
          bind:checked={device.voice.automatic}
          onchange={persistDevice}
        /> Automatically press Enter after paste</label
      >
    </div>
    <p class="hint">
      {voiceCacheLoading ? 'Preparing voice models… ' : ''}Preload uses more RAM to
      prepare installed models before recording and retain the cleanup service. Release
      mode saves RAM between recordings. The current behavior keeps speech recognition
      after its first use and reloads cleanup when needed. None of these options keeps the
      microphone on.
    </p>
    <p class="hint">
      Text is pasted when ready. Press Enter to send unless automatic entry is enabled.
    </p>
  </details>
  <div class="microphone-settings">
    <label
      >Microphone<select bind:value={device.voice.microphone} onchange={persistDevice}
        ><option value="">System default</option
        >{#if device.voice.microphone && !microphones.some((m) => m.deviceId === device.voice.microphone)}<option
            value={device.voice.microphone}
            >Selected input unavailable - refresh devices</option
          >{/if}{#each microphones as m}<option value={m.deviceId}
            >{m.label || `Audio input ${microphones.indexOf(m) + 1}`}</option
          >{/each}</select
      ></label
    ><button disabled={microphoneLoading} onclick={() => void refreshMicrophones()}
      >{microphoneLoading ? 'Checking microphones…' : 'Refresh microphones'}</button
    >
    {#if microphoneError}<p class="inline-error" role="alert">{microphoneError}</p>{/if}
  </div>
</details>
