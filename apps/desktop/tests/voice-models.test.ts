import { describe, expect, it, vi } from 'vitest';
import { VoiceModels, type VoiceModelsDeps } from '../src/lib/state/voice.svelte';
import { defaults, type ModelInfo } from '../src/lib/types';

const model = (id: string, installed: boolean, family = 'asr'): ModelInfo => ({
  id,
  name: id,
  size: 1,
  installed,
  family,
  license: 'MIT',
});

function setup(list: ModelInfo[], overrides: Partial<VoiceModelsDeps> = {}) {
  const device = defaults();
  let installed = list;
  const calls: [string, unknown][] = [];
  const call = vi.fn(async (name: string, args?: Record<string, unknown>) => {
    calls.push([name, args]);
    if (name === 'model_list') return installed;
    if (name === 'model_download')
      installed = installed.map((m) => (m.id === args?.id ? { ...m, installed: true } : m));
    return undefined;
  });
  const deps: VoiceModelsDeps = {
    desktop: true,
    call,
    device: () => device,
    voiceStatus: () => 'idle',
    persist: vi.fn(),
    notice: vi.fn(),
    fail: vi.fn(),
    mediaDevices: () => undefined,
    ...overrides,
  };
  return { voice: new VoiceModels(deps), deps, device, calls, names: () => calls.map(([n]) => n) };
}

const stack = () => [
  model('parakeet', true),
  model('silero', true, 'vad'),
  model('s1-mini', true, 'cleanup'),
];

describe('VoiceModels', () => {
  it('preloads installed models once per configuration', async () => {
    const { voice, names, device } = setup(stack());
    await voice.loadModels();
    expect(voice.modelsLoaded).toBe(true);
    expect(voice.ready).toBe(true);
    expect(names()).toEqual(['model_list', 'voice_cache']);
    await voice.loadModels();
    expect(names()).toEqual(['model_list', 'voice_cache', 'model_list']);
    device.voice.cleanup = false;
    await voice.configureCache();
    expect(names().at(-1)).toBe('voice_cache');
  });

  it('does not preload while recording or before models are installed', async () => {
    const busy = setup(stack(), { voiceStatus: () => 'listening' });
    await busy.voice.loadModels();
    expect(busy.names()).toEqual(['model_list']);
    const missing = setup([model('parakeet', false)]);
    await missing.voice.loadModels();
    expect(missing.names()).toEqual(['model_list']);
    expect(missing.voice.ready).toBe(false);
  });

  it('reports a failed preload as a notice', async () => {
    const { voice, deps } = setup(stack());
    (deps.call as ReturnType<typeof vi.fn>).mockImplementation(async (name: string) => {
      if (name === 'voice_cache') throw new Error('no GPU');
      return stack();
    });
    await voice.loadModels();
    expect(deps.notice).toHaveBeenCalledWith(
      'Voice preload failed; recording can retry: Error: no GPU',
    );
    expect(voice.cacheLoading).toBe(false);
  });

  it('setup downloads only missing models and marks setup done', async () => {
    const { voice, names, device, deps } = setup([
      model('parakeet', true),
      model('silero', false, 'vad'),
      model('s1-mini', false, 'cleanup'),
    ]);
    await voice.loadModels();
    await voice.setup();
    expect(names().filter((n) => n === 'model_download')).toHaveLength(2);
    expect(voice.downloadProgress).toEqual({});
    expect(device.voiceSetupDismissed).toBe(true);
    expect(deps.notice).toHaveBeenCalledWith('Voice stack ready to go.');
  });

  it('selecting a model downloads it, releases the old one and saves the choice', async () => {
    const { voice, names, device, deps } = setup([...stack(), model('whisper', false)]);
    await voice.loadModels();
    await voice.selectModel('whisper');
    expect(names()).toEqual([
      'model_list',
      'voice_cache',
      'model_download',
      'model_list',
      'voice_release',
      'voice_cache',
    ]);
    expect(device.voice.model).toBe('whisper');
    expect(deps.persist).toHaveBeenCalled();
    expect(voice.switching).toBe(false);
  });

  it('ignores a model change while recording', async () => {
    const { voice, names } = setup(stack(), { voiceStatus: () => 'listening' });
    await voice.selectModel('parakeet');
    expect(names()).toEqual([]);
  });

  it('removing the active model resets the preload', async () => {
    const { voice, names } = setup(stack());
    await voice.loadModels();
    await voice.removeModel('parakeet', true);
    expect(names().slice(2, 5)).toEqual(['voice_release', 'model_remove', 'model_list']);
    expect(names().at(-1)).toBe('voice_cache');
  });

  it('reports microphones that cannot be listed', async () => {
    const { voice } = setup(stack());
    await voice.refreshMicrophones();
    expect(voice.microphoneError).toContain('Microphone access is unavailable in this window.');
    expect(voice.microphoneLoading).toBe(false);
  });
});
