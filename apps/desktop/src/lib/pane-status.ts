export interface PaneStatus {
  directory?: string;
  sessionId?: string;
  stamp?: string;
  attachment: 'tmux' | 'native' | 'plain' | 'unknown';
  nestedSsh: boolean;
  hostname: string | null;
}
export const unknownStatus: PaneStatus = {
  attachment: 'unknown',
  nestedSsh: false,
  hostname: null,
};
export function hostLabel(connection: string, status?: PaneStatus): string {
  if (status?.nestedSsh) return `Via ${connection} · SSH destination unverified`;
  return `Connection: ${connection}${status?.hostname ? ` (${status.hostname})` : ''}`;
}
