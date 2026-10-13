import type { PaneStatus } from './pane-status';
export function deadPaneTarget(status: PaneStatus): { id: string; stamp: string; paneId: string } | null {
  if (status.attachment !== 'tmux' || status.nestedSsh || !status.paneDead ||
      !/^\$\d+$/.test(status.sessionId || '') || !status.stamp || !/^%\d+$/.test(status.paneId || '')) return null;
  return { id: status.sessionId!, stamp: status.stamp, paneId: status.paneId! };
}
export async function recoverDeadPane(status: PaneStatus, recover: (target: { id: string; stamp: string; paneId: string }) => Promise<unknown>) {
  const target = deadPaneTarget(status);
  if (!target) throw Error('Session changed. Wait for current terminal status before opening it.');
  return recover(target);
}
