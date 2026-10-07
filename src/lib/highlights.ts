export const recordingHighlights = [
  { id: 'energy', name: 'Blue energy' },
  { id: 'rainbow', name: 'Rainbow shimmer' },
  { id: 'static', name: 'Static colour' },
] as const;
export type RecordingHighlight = (typeof recordingHighlights)[number]['id'];
export function recordingHighlight(value: unknown): RecordingHighlight {
  return recordingHighlights.find((effect) => effect.id === value)?.id ?? 'energy';
}
