import type { Host, Inventory, Profile } from './types';

export function refreshHostIdentity(
  host: Host,
  inventory: Pick<Inventory, 'machine' | 'user'>,
  profiles: readonly Profile[],
) {
  // Native sessions belong to one host ID, even on the same Windows account.
  host.fingerprint = `${inventory.machine}:${inventory.user}:${host.kind === 'powershell' ? host.id : host.socket}`;
  const changed = new Set<Profile>();
  for (const profile of profiles)
    for (const workspace of profile.workspaces)
      for (const pane of workspace.panes) {
        const before = JSON.stringify([pane.hostId, pane.hostKey, pane.hostDetails]);
        if (pane.hostKey === host.fingerprint) pane.hostId = host.id;
        else if (pane.hostId === host.id && (!pane.hostKey ||
          (host.kind === 'powershell' && pane.hostKey === `${inventory.machine}:${inventory.user}:${host.socket}`)))
          pane.hostKey = host.fingerprint;
        if (pane.hostKey === host.fingerprint) {
          pane.hostDetails = {
            ...pane.hostDetails,
            name: host.name,
            kind: host.kind,
            address: host.address,
            user: host.user,
            port: host.port ?? 22,
            socket: host.socket,
          };
        }
        if (JSON.stringify([pane.hostId, pane.hostKey, pane.hostDetails]) !== before) changed.add(profile);
      }
  return changed;
}
