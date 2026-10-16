import { describe, it, expect } from 'vitest';
import { recommendVoice, accelerationAdvice, type VoiceHardware } from '../src/lib/hardware';
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
