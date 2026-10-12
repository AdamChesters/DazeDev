import { describe, it, expect } from 'vitest';
import { recommendVoice, accelerationAdvice, type VoiceHardware } from '../src/lib/hardware';
import { sessionNameError } from '../src/lib/session-name';
import { energyFrames, borderPoint, energyMotes, energyCount } from '../src/lib/energy-motion';
describe('voice hardware guidance', () => {
  const small: VoiceHardware = {
    logicalCpus: 2,
    inferenceThreads: 1,
    memory: 4 * 1024 ** 3,
    vulkan: false,
    devices: [],
  };
  it('uses conservative guidance without sufficient RAM or a working GPU', () => {
    expect(recommendVoice(small).model).toBe('whisper-small');
    expect(accelerationAdvice(small, 'whisper-turbo', 'auto')).toContain('CPU only');
    expect(recommendVoice({ ...small, logicalCpus: 8, memory: 16 * 1024 ** 3 }).model).toBe(
      'parakeet',
    );
  });
  it('recommends turbo only with memory and detected Vulkan, while preserving CPU choice', () => {
    const gpu = {
      ...small,
      logicalCpus: 16,
      memory: 32 * 1024 ** 3,
      vulkan: true,
      devices: [
        { name: 'GPU', backend: 'vulkan', memory: 8 * 1024 ** 3, freeMemory: 6 * 1024 ** 3 },
      ],
    };
    expect(recommendVoice(gpu).model).toBe('whisper-turbo');
    expect(accelerationAdvice(gpu, 'whisper-turbo', 'cpu')).toContain('CPU is selected');
  });
});
describe('session names', () => {
  it('rejects errors at the first step including spaces, separators and shell characters', () => {
    for (const s of ['', 'repo.work', 'name:1', 'two words', 'foo/bar', '$foo', 'name\n'])
      expect(sessionNameError(s)).not.toBe('');
    expect(sessionNameError('codex-adam_2')).toBe('');
  });
});
describe('compositor motion', () => {
  it('scales density with border distance while bounding compositor layers', () => {
    expect(energyCount(140, 22)).toBe(24);
    expect(energyCount(280, 44)).toBe(27);
    expect(energyCount(900, 500)).toBe(80);
    expect(energyCount(8000, 4000)).toBe(80);
  });
  it('uses rounded continuous paths at status, sidebar and terminal sizes', () => {
    expect(energyMotes).toHaveLength(60);
    for (const [w, h] of [
      [140, 22],
      [200, 40],
      [1200, 900],
    ]) {
      let previous = borderPoint(w, h, 6, 0);
      for (let i = 1; i <= 10000; i++) {
        const point = borderPoint(w, h, 6, i / 10000);
        expect(Math.hypot(point.x - previous.x, point.y - previous.y)).toBeLessThan(1);
        previous = point;
      }
    }
  });
  it('contains only transform/opacity animation and finite coordinates across small and large panes', () => {
    for (const [w, h] of [
      [0, 0],
      [80, 22],
      [2000, 1400],
    ]) {
      for (const kind of [0, 1, 2]) {
        const frames = energyFrames(w, h, 3, 6, kind);
        for (const f of frames) {
          expect(Object.keys(f).sort()).toEqual(['offset', 'opacity', 'transform']);
          expect(String(f.transform)).not.toMatch(/NaN|Infinity/);
        }
        expect(frames[0].opacity).toBe(frames.at(-1)?.opacity);
        expect(frames[0].transform).toBe(frames.at(-1)?.transform);
      }
    }
  });
});
