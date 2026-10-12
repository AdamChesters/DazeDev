import type { Device, ModelInfo } from './types';
export const modelDescriptions: Record<string, string> = {
  parakeet: 'Fast English dictation.',
  'whisper-small': 'Multilingual · lower memory.',
  'whisper-medium': 'Multilingual · balanced accuracy.',
  'whisper-turbo': 'Multilingual · faster Large variant.',
  'whisper-large': 'Multilingual · highest resource use.',
  silero: 'Detects speech and silence so quiet audio is not transcribed.',
  's1-mini': 'Optional local cleanup for fillers, punctuation and spoken corrections.',
};
export function requiredVoiceModels(voice: Device['voice']) {
  return [voice.model, 'silero', ...(voice.cleanup ? ['s1-mini'] : [])];
}
export function voiceStackReady(voice: Device['voice'], models: ModelInfo[]) {
  return requiredVoiceModels(voice).every((id) => models.some((m) => m.id === id && m.installed));
}
export async function listMicrophones(media: MediaDevices, requestPermission = true) {
  let stream: MediaStream | undefined;
  try {
    if (requestPermission) stream = await media.getUserMedia({ audio: true });
    const devices = await media.enumerateDevices();
    return devices.filter((d) => d.kind === 'audioinput' && d.deviceId !== 'default');
  } finally {
    stream?.getTracks().forEach((track) => track.stop());
  }
}
