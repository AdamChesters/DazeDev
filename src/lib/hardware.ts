export interface VoiceHardware {
  logicalCpus: number;
  inferenceThreads: number;
  memory: number;
  vulkan: boolean;
  devices: { name: string; backend: string; memory: number; freeMemory: number }[];
}
export function recommendVoice(h: VoiceHardware) {
  const gib = h.memory / 1024 ** 3;
  const gpu = h.devices.find((d) => d.backend === 'vulkan' && d.memory >= 4 * 1024 ** 3);
  if (h.vulkan && gpu && gib >= 16)
    return {
      model: 'whisper-turbo',
      reason:
        'Whisper Turbo balances multilingual accuracy and speed with your available GPU and RAM.',
    };
  if (gib >= 8 && h.logicalCpus >= 4)
    return {
      model: 'parakeet',
      reason:
        'Parakeet is the suggested starting point for fast English dictation on this device. Choose Whisper for other languages.',
    };
  return {
    model: 'whisper-small',
    reason: 'Whisper Small is the conservative choice for limited or unknown RAM/CPU capacity.',
  };
}
export function accelerationAdvice(h: VoiceHardware, model: string, backend: string) {
  if (!model.startsWith('whisper-') && model !== 'parakeet')
    return 'GPU support has not been established for this model.';
  if (!h.vulkan)
    return `CPU only: no usable Vulkan GPU was detected. ${model === 'whisper-medium' || model === 'whisper-turbo' || model === 'whisper-large' ? 'This Whisper model may lag during live dictation on CPU; Parakeet (English) or Whisper Small is the faster starting point. ' : ''}Check graphics drivers if a GPU is expected.`;
  if (backend === 'cpu')
    return 'A Vulkan GPU is available for this model, but CPU is selected. Choose Automatic or GPU to use it.';
  return 'Vulkan acceleration is available for this model. Actual device use is confirmed when the model loads; available memory can still limit loading.';
}
