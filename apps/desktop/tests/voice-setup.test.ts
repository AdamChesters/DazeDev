import { describe, expect, it, vi } from 'vitest';
import { listMicrophones, voiceStackReady } from '../src/lib/voice-setup';
import { defaults, type ModelInfo } from '../src/lib/types';
describe('voice setup', () => {
  it('requires the selected stack even after a setup prompt was dismissed', () => {
    const voice = defaults().voice;
    const models = ['parakeet', 'silero', 's1-mini'].map(
      (id) => ({ id, installed: true }) as ModelInfo,
    );
    expect(voiceStackReady(voice, models)).toBe(true);
    expect(voiceStackReady({ ...voice, model: 'whisper-small' }, models)).toBe(false);
    expect(
      voiceStackReady(
        voice,
        models.filter((m) => m.id !== 's1-mini'),
      ),
    ).toBe(false);
    expect(
      voiceStackReady(
        { ...voice, cleanup: false },
        models.filter((m) => m.id !== 's1-mini'),
      ),
    ).toBe(true);
  });
  it('requests permission before enumerating named inputs and releases capture', async () => {
    const stop = vi.fn();
    const permission = vi.fn(async () => ({ getTracks: () => [{ stop }] }));
    const enumerate = vi.fn(async () => {
      expect(permission).toHaveBeenCalledOnce();
      return [
        { kind: 'audioinput', deviceId: 'default', label: 'Default' },
        { kind: 'audioinput', deviceId: 'webcam', label: 'Webcam microphone' },
        { kind: 'audioinput', deviceId: 'cable', label: 'Virtual audio cable' },
        { kind: 'audiooutput', deviceId: 'speakers', label: 'Speakers' },
      ];
    });
    const result = await listMicrophones({
      getUserMedia: permission,
      enumerateDevices: enumerate,
    } as unknown as MediaDevices);
    expect(result.map((d) => d.label)).toEqual(['Webcam microphone', 'Virtual audio cable']);
    expect(stop).toHaveBeenCalledOnce();
  });
  it('releases microphone capture if enumeration fails', async () => {
    const stop = vi.fn();
    const media = {
      getUserMedia: async () => ({ getTracks: () => [{ stop }] }),
      enumerateDevices: async () => {
        throw Error('disconnected');
      },
    };
    await expect(listMicrophones(media as unknown as MediaDevices)).rejects.toThrow('disconnected');
    expect(stop).toHaveBeenCalledOnce();
  });
});
