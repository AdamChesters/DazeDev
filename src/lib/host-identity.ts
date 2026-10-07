import type { Host, Inventory, Profile } from './types';

export function refreshHostIdentity(
  host: Host,
  inventory: Pick<Inventory, 'machine' | 'user'>,
  profiles: readonly Profile[],
) {
  // Native sessions belong to one host ID, even on the same Windows account.
  host.fingerprint = `${inventory.machine}:${inventory.user}:${host.kind === 'powershell' ? host.id : host.socket}`;
  for (const profile of profiles)
    for (const workspace of profile.workspaces)
      for (const pane of workspace.panes) {
        if (pane.hostKey === host.fingerprint) pane.hostId = host.id;
        else if (pane.hostId === host.id) pane.hostKey = host.fingerprint;
      }
}
