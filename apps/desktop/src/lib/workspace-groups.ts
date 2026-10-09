import type { Host, Workspace } from './types';
export const HOSTLESS = '@device-workspaces';
export function workspaceHosts(
  workspace: Workspace,
  hosts: Host[],
  connected: Record<string, string>,
) {
  const required = new Map<string, Host>();
  const missing = new Set<string>();
  const panes = workspace.panes.map((pane) => {
    const host = hosts.find((h) =>
      pane.hostKey ? h.fingerprint === pane.hostKey : h.id === pane.hostId,
    );
    if (!host)
      missing.add(pane.hostDetails?.name || pane.hostDetails?.address || 'Unconfigured host');
    else if (!['Connected', 'Connecting'].includes(connected[host.id])) required.set(host.id, host);
    return host ? { ...pane, hostId: host.id } : pane;
  });
  return { panes, required: [...required.values()], missing: [...missing] };
}
