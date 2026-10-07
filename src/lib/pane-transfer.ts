import { columns, ids, remove } from './layout';
import { uid, type Layout, type Workspace } from './types';

export function selectPanes(selected: string[], pane: string, additive: boolean): string[] {
  if (!additive) return [pane];
  return selected.includes(pane) ? selected.filter((id) => id !== pane) : [...selected, pane];
}

export function removePanes(layout: Layout, panes: string[]): Layout {
  return (
    panes.reduce<Layout | null>((node, id) => node && remove(node, id), layout) || {
      kind: 'pane',
      id: '',
    }
  );
}

export function insertPaneStack(
  layout: Layout,
  panes: string[],
  target = '',
  edge: 'left' | 'right' | 'top' | 'bottom' = 'right',
): Layout {
  const stack = columns(panes, 'column');
  if (!ids(layout).some(Boolean)) return stack;
  const before = edge === 'left' || edge === 'top';
  const join = (node: Layout): Layout => ({
    kind: 'split',
    id: uid(),
    axis: edge === 'left' || edge === 'right' ? 'row' : 'column',
    ratio: 0.5,
    first: before ? stack : node,
    second: before ? node : stack,
  });
  if (!target) return join(layout);
  const insert = (node: Layout): Layout =>
    node.kind === 'pane'
      ? node.id === target
        ? join(node)
        : node
      : { ...node, first: insert(node.first), second: insert(node.second) };
  return insert(layout);
}

export function reorderWorkspaces(
  workspaces: Workspace[],
  source: string,
  target: string,
  after: boolean,
): Workspace[] {
  const from = workspaces.find((w) => w.id === source);
  const to = workspaces.find((w) => w.id === target);
  if (!from || !to || from === to || from.hostId !== to.hostId) return workspaces;
  const result = workspaces.filter((w) => w.id !== source);
  result.splice(result.findIndex((w) => w.id === target) + Number(after), 0, from);
  return result;
}
