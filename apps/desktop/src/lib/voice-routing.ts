export type CaptureMode = 'dictation' | 'command' | 'broadcast';
export function voiceDestination(shortcut: 'dictation' | 'command', modal: string): CaptureMode {
  return modal === 'broadcast' ? 'broadcast' : modal === 'command' ? 'command' : shortcut;
}
