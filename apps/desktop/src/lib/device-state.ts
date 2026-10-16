import type { Device, Workspace } from './types';

// Drop keys that no longer name a live item. Returns the same object when
// nothing changed, so callers can skip saving.
export function pruneKeys<T>(record: Record<string, T>, live: Set<string>): Record<string, T> {
  const keys = Object.keys(record);
  if (keys.every((key) => live.has(key))) return record;
  return Object.fromEntries(keys.filter((key) => live.has(key)).map((key) => [key, record[key]]));
}

// Maps keyed by a container (workspace or tab group) that point to a pane.
// An entry is removed when its container is loaded and no longer holds that
// pane, or when its container was seen this session and is now gone. Entries
// for hosts that have not loaded yet stay until every host has loaded.
export function prunePaneChoices(
  record: Record<string, string>,
  containers: Map<string, Set<string>>,
  seen: Set<string>,
  complete: boolean,
): Record<string, string> {
  const keep = (key: string, pane: string) =>
    containers.has(key) ? containers.get(key)!.has(pane) : !complete && !seen.has(key);
  const keys = Object.keys(record);
  if (keys.every((key) => keep(key, record[key]))) return record;
  return Object.fromEntries(
    keys.filter((key) => keep(key, record[key])).map((key) => [key, record[key]]),
  );
}

export function paneContainers(workspaces: Workspace[]) {
  const byWorkspace = new Map<string, Set<string>>();
  const byGroup = new Map<string, Set<string>>();
  for (const workspace of workspaces) {
    const panes = new Set(workspace.panes.map((pane) => pane.id));
    byWorkspace.set(workspace.id, panes);
    for (const group of workspace.tabGroups || [])
      byGroup.set(group.id, new Set(group.panes.filter((id) => panes.has(id))));
  }
  return { byWorkspace, byGroup };
}

// Remote copy used to default to a silent write. Version 1 makes "notify" the
// default; a saved "write" was that old default, so it becomes "notify".
export function migrateClipboardPolicy(device: Device): Device {
  if ((device.clipboardPolicy || 0) >= 1) return device;
  const upgrade = <T extends string | undefined>(value: T) =>
    (value === 'write' ? 'notify' : value) as T;
  return {
    ...device,
    clipboardPolicy: 1,
    terminal: device.terminal && {
      ...device.terminal,
      clipboard: upgrade(device.terminal.clipboard),
    },
    clipboardByHost:
      device.clipboardByHost &&
      Object.fromEntries(
        Object.entries(device.clipboardByHost).map(([host, value]) => [host, upgrade(value)]),
      ),
  };
}
