import { describe, expect, it, vi } from 'vitest';
import { authenticationState, verificationUrl } from '../src/lib/connection';
import { listMicrophones, voiceStackReady } from '../src/lib/voice-setup';
import { defaults, type ModelInfo } from '../src/lib/types';
describe('connection prompts', () => {
  it('does not invent a password prompt for Tailscale or startup', () => {
    expect(authenticationState('')).toBe('connecting');
    expect(authenticationState('Connecting with Tailscale')).toBe('connecting');
    expect(authenticationState('Permission denied', true)).toBe('closed');
  });
  it('separates host trust, changed keys, credentials and browser checks', () => {
    expect(
      authenticationState(
        'ED25519 key fingerprint is SHA256:abc\nAre you sure you want to continue connecting (yes/no/[fingerprint])?',
      ),
    ).toBe('trust');
    expect(authenticationState('REMOTE HOST IDENTIFICATION HAS CHANGED! yes/no')).toBe(
      'host-error',
    );
    expect(authenticationState("Enter passphrase for key 'test':")).toBe('credentials');
    const check = 'Tailscale SSH requires a check: https://login.tailscale.com/a/example';
    expect(authenticationState(check)).toBe('browser');
    expect(verificationUrl(check)).toBe('https://login.tailscale.com/a/example');
  });
});
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
