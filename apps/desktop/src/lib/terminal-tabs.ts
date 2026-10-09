import type { Layout, Workspace } from './types';
import { ids, insertAt, remove } from './layout';
export interface TerminalTabGroup {
  id: string;
  panes: string[];
}
export function cleanTabGroups(
  groups: TerminalTabGroup[] = [],
  panes: string[],
): TerminalTabGroup[] {
  const available = new Set(panes),
    used = new Set<string>();
  return (Array.isArray(groups) ? groups : [])
    .filter((group) => group && typeof group.id === 'string' && Array.isArray(group.panes))
    .map((group) => ({
      ...group,
      panes: group.panes.filter((id) => {
        if (!available.has(id) || used.has(id)) return false;
        used.add(id);
        return true;
      }),
    }))
    .filter((group) => group.panes.length > 1);
}
export function terminalTabs(workspace: Workspace, paneId: string) {
  return cleanTabGroups(
    workspace.tabGroups,
    workspace.panes.map((p) => p.id),
  ).find((group) => group.panes.includes(paneId));
}
export function tabLayout(
  layout: Layout,
  groups: TerminalTabGroup[] = [],
  active: Record<string, string> = {},
): Layout {
  for (const group of groups) {
    const present = ids(layout).filter((id) => group.panes.includes(id));
    if (present.length < 2) continue;
    const selected = present.includes(active[group.id]) ? active[group.id] : present[0];
    const anchor = present[0];
    for (const id of present.slice(1)) layout = remove(layout, id)!;
    function rename(node: Layout): Layout {
      return node.kind === 'pane'
        ? { ...node, id: node.id === anchor ? selected : node.id }
        : { ...node, first: rename(node.first), second: rename(node.second) };
    }
    layout = rename(layout);
  }
  return layout;
}
export function retainTabPanes(layout: Layout, groups: TerminalTabGroup[] = []): Layout {
  for (const group of groups) {
    const anchor = ids(layout).find((id) => group.panes.includes(id));
    if (!anchor) continue;
    for (const id of group.panes)
      if (!ids(layout).includes(id)) layout = insertAt(layout, anchor, id, 'right');
  }
  return layout;
}
