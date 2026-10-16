export interface PaneStatus {
  paneId?: string;
  paneDead?: boolean;
  directory?: string;
  sessionId?: string;
  stamp?: string;
  sessionName?: string | null;
  agent?: string | null;
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

export function terminalPresentation(
  pane: { name: string; agent: string; model: string; shell?: boolean; sessionId?: string },
  status?: PaneStatus,
) {
  if (pane.shell && status?.attachment === 'tmux' && !status.nestedSsh) {
    const agent = status.agent;
    return {
      name: status.sessionName || pane.name,
      type: agent && ['codex', 'claude', 'gemini', 'opencode'].includes(agent) ? agent : 'tmux',
      model: '',
    };
  }
  if (
    !pane.shell &&
    pane.sessionId &&
    status?.attachment === 'tmux' &&
    !status.nestedSsh &&
    status.sessionId === pane.sessionId &&
    status.paneDead === false &&
    status.agent === 'shell'
  ) {
    return { name: pane.name, type: 'tmux', model: '' };
  }
  return { name: pane.name, type: pane.agent, model: pane.model };
}

// Status polls return a new object every time; keep the old one when equal.
export function sameStatus(a: PaneStatus | undefined, b: PaneStatus | undefined): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]) as Set<keyof PaneStatus>;
  for (const key of keys) if (a[key] !== b[key]) return false;
  return true;
}
