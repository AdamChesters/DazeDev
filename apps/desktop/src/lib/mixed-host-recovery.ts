import type { Host, Workspace } from './types';

export interface HostRecovery {
  hostId: string;
  workspaces: string[];
}

export function hasReusableAccess(host: Host): boolean {
  return ['local', 'wsl', 'tailscale', 'powershell'].includes(host.kind) || !!host.key.trim();
}

export class MixedHostRecovery {
  private offered = new Set<string>();
  answer(hostId: string) {
    this.offered.add(hostId);
  }
  plan(
    owner: string,
    workspaces: readonly Workspace[],
    hosts: readonly Host[],
    unavailable: ReadonlySet<string>,
  ): HostRecovery[] {
    const candidates = new Map<string, Set<string>>();
    for (const workspace of workspaces)
      for (const pane of workspace.panes) {
        const host = hosts.find((h) => h.id === pane.hostId);
        if (
          !host ||
          host.id === owner ||
          this.offered.has(host.id) ||
          unavailable.has(host.id) ||
          !hasReusableAccess(host)
        )
          continue;
        const names = candidates.get(host.id) || new Set<string>();
        names.add(workspace.name);
        candidates.set(host.id, names);
      }
    return [...candidates].map(([hostId, names]) => {
      this.offered.add(hostId);
      return { hostId, workspaces: [...names] };
    });
  }
}
