import { checked, checkModels } from '../ipc';
import { recommendVoice, type VoiceHardware } from '../hardware';
import type { Device, ModelInfo } from '../types';
import { listMicrophones, requiredVoiceModels, voiceStackReady } from '../voice-setup';

export interface VoiceModelsDeps {
  desktop: boolean;
  call: (name: string, args?: Record<string, unknown>) => Promise<unknown>;
  device: () => Device;
  /** Current recording phase; models change only while 'idle'. */
  voiceStatus: () => string;
  notice: (message: string) => void;
  fail: (e: unknown) => void;
  mediaDevices: () => MediaDevices | undefined;
}

// Voice models, the preloaded model cache, hardware advice and microphones.
// Recording itself stays in App.svelte.
export class VoiceModels {
  models = $state<ModelInfo[]>([]);
  modelsLoaded = $state(false);
  downloadProgress = $state<Record<string, string>>({});
  switching = $state(false);
  cacheLoading = $state(false);
  hardware = $state<VoiceHardware | null>(null);
  hardwareError = $state('');
  microphones = $state<MediaDeviceInfo[]>([]);
  microphoneError = $state('');
  microphoneLoading = $state(false);
  recommendation = $derived(this.hardware ? recommendVoice(this.hardware) : null);
  ready = $derived.by(
    () => this.modelsLoaded && voiceStackReady(this.#deps.device().voice, this.models),
  );
  /** Pending model preload; a recording waits for it. */
  cacheTask: Promise<void> | undefined;
  #deps: VoiceModelsDeps;
  #cacheSignature = '';

  constructor(deps: VoiceModelsDeps) {
    this.#deps = deps;
  }

  #installed(id: string) {
    return this.models.some((m) => m.id === id && m.installed);
  }

  loadModels = async () => {
    this.models = checked('model_list', checkModels, await this.#deps.call('model_list'));
    this.modelsLoaded = true;
    await this.configureCache().catch((e) => {
      this.#deps.notice(`Voice preload failed; recording can retry: ${e}`);
    });
  };

  async configureCache() {
    const deps = this.#deps;
    const device = deps.device();
    if (!deps.desktop || deps.voiceStatus() !== 'idle') return;
    if (this.cacheTask) return this.cacheTask;
    // Preload by default so the first dictation does not wait for model loads.
    const retain = device.voice.keepLoaded ?? true;
    if (retain && (!this.#installed(device.voice.model) || !this.#installed('silero'))) return;
    const options = {
      model: device.voice.model,
      backend: device.voice.backend,
      cleanup: device.voice.cleanup && this.#installed('s1-mini'),
      retain,
    };
    const signature = JSON.stringify(options);
    if (signature === this.#cacheSignature) return;
    this.cacheLoading = true;
    try {
      this.cacheTask = deps.call('voice_cache', options) as Promise<void>;
      await this.cacheTask;
      this.#cacheSignature = signature;
    } finally {
      this.cacheTask = undefined;
      this.cacheLoading = false;
    }
  }

  changeModel = async () => {
    const deps = this.#deps;
    this.switching = true;
    try {
      await deps.call('voice_release');
      this.#cacheSignature = '';
      await this.configureCache();
    } catch (e) {
      deps.fail(e);
    } finally {
      this.switching = false;
    }
  };

  selectModel = async (id: string) => {
    const deps = this.#deps;
    if (this.switching || this.cacheLoading || deps.voiceStatus() !== 'idle') return;
    this.switching = true;
    try {
      if (!this.models.find((m) => m.id === id)?.installed) {
        this.downloadProgress[id] = 'Starting';
        await deps.call('model_download', { id });
        await this.loadModels();
      }
      await deps.call('voice_release');
      this.#cacheSignature = '';
      deps.device().voice.model = id;
      await this.configureCache();
    } finally {
      delete this.downloadProgress[id];
      this.switching = false;
    }
  };

  removeModel = async (id: string, release: boolean) => {
    if (release) {
      await this.#deps.call('voice_release');
      this.#cacheSignature = '';
    }
    await this.#deps.call('model_remove', { id });
    await this.loadModels();
  };

  downloadModel = async (id: string) => {
    this.downloadProgress[id] = 'Starting';
    try {
      await this.#deps.call('model_download', { id });
      await this.loadModels();
    } finally {
      delete this.downloadProgress[id];
    }
  };

  /** Downloads every missing model of the selected stack. */
  setup = async () => {
    const deps = this.#deps;
    for (const id of requiredVoiceModels(deps.device().voice)) {
      if (this.models.find((m) => m.id === id)?.installed) continue;
      this.downloadProgress[id] = 'Starting';
      try {
        await deps.call('model_download', { id });
      } finally {
        delete this.downloadProgress[id];
        await this.loadModels();
      }
    }
    deps.device().voiceSetupDismissed = true;
    deps.notice('Voice stack ready to go.');
  };

  /** Reads hardware once for model advice. */
  detectHardware() {
    if (this.#deps.desktop && !this.hardware)
      void (this.#deps.call('voice_hardware') as Promise<VoiceHardware>)
        .then((v) => {
          this.hardware = v;
          this.hardwareError = '';
        })
        .catch((e) => {
          this.hardwareError = String(e);
        });
  }

  refreshMicrophones = async (requestPermission = true) => {
    this.microphoneError = '';
    this.microphoneLoading = true;
    try {
      const media = this.#deps.mediaDevices();
      if (!media) throw new Error('Microphone access is unavailable in this window.');
      this.microphones = await listMicrophones(media, requestPermission);
    } catch (e) {
      this.microphoneError = `Microphone access: ${String(e)}. Allow microphone access in Windows privacy settings, then refresh.`;
    } finally {
      this.microphoneLoading = false;
    }
  };
}
