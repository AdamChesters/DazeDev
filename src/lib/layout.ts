import { uid, type Layout } from './types';
export function tile(ids: string[], axis: 'row' | 'column' = 'row'): Layout {
  if (!ids.length) throw new Error('A layout needs a pane');
  if (ids.length === 1) return { kind: 'pane', id: ids[0] };
  const n = Math.ceil(ids.length / 2);
  return {
    kind: 'split',
    id: uid(),
    axis,
    ratio: n / ids.length,
    first: tile(ids.slice(0, n), axis === 'row' ? 'column' : 'row'),
    second: tile(ids.slice(n), axis === 'row' ? 'column' : 'row'),
  };
}
export function split(node: Layout, target: string, id: string, axis: 'row' | 'column'): Layout {
  if (node.kind === 'pane')
    return node.id === target
      ? { kind: 'split', id: uid(), axis, ratio: 0.5, first: node, second: { kind: 'pane', id } }
      : node;
  return {
    ...node,
    first: split(node.first, target, id, axis),
    second: split(node.second, target, id, axis),
  };
}
export function remove(node: Layout, id: string): Layout | null {
  if (node.kind === 'pane') return node.id === id ? null : node;
  const first = remove(node.first, id),
    second = remove(node.second, id);
  return first && second ? { ...node, first, second } : first || second;
}
export function resize(node: Layout, id: string, ratio: number): Layout {
  if (node.kind === 'pane') return node;
  return node.id === id
    ? { ...node, ratio: Math.max(0.08, Math.min(0.92, ratio)) }
    : { ...node, first: resize(node.first, id, ratio), second: resize(node.second, id, ratio) };
}
export function swap(node: Layout, a: string, b: string): Layout {
  if (node.kind === 'pane') return { ...node, id: node.id === a ? b : node.id === b ? a : node.id };
  return { ...node, first: swap(node.first, a, b), second: swap(node.second, a, b) };
}
export function ids(node: Layout): string[] {
  return node.kind === 'pane' ? [node.id] : [...ids(node.first), ...ids(node.second)];
}
// Local connection IDs differ between devices; the profile's owner is the verified host just joined.
export function rebindOwner<
  T extends { workspaces: { hostId: string; panes: { hostId: string }[] }[] },
>(profile: T, hostId: string): T {
  const result = structuredClone(profile);
  for (const workspace of result.workspaces) {
    const previous = workspace.hostId;
    workspace.hostId = hostId;
    for (const pane of workspace.panes) if (pane.hostId === previous) pane.hostId = hostId;
  }
  return result;
}

export type Edge = 'left' | 'right' | 'top' | 'bottom';
export type Rect = { x: number; y: number; width: number; height: number };
export function columns(panes: string[], axis: 'row' | 'column' = 'row'): Layout {
  if (!panes.length) throw new Error('A layout needs a pane');
  if (panes.length === 1) return { kind: 'pane', id: panes[0] };
  return {
    kind: 'split',
    id: uid(),
    axis,
    ratio: 1 / panes.length,
    first: { kind: 'pane', id: panes[0] },
    second: columns(panes.slice(1), axis),
  };
}
export function balanced(panes: string[], width = 1200): Layout {
  // Prefer a compact grid; narrow windows can use fewer columns for readable terminals.
  const count = Math.max(1, Math.min(Math.ceil(Math.sqrt(panes.length)), Math.floor(width / 280)));
  if (panes.length <= count) return columns(panes);
  const rows: Layout[] = [];
  const rowCount = Math.ceil(panes.length / count);
  for (let i = 0, offset = 0; i < rowCount; i++) {
    const n = Math.ceil((panes.length - offset) / (rowCount - i));
    rows.push(columns(panes.slice(offset, offset + n)));
    offset += n;
  }
  function stack(nodes: Layout[]): Layout {
    return nodes.length === 1
      ? nodes[0]
      : {
          kind: 'split',
          id: uid(),
          axis: 'column',
          ratio: 1 / nodes.length,
          first: nodes[0],
          second: stack(nodes.slice(1)),
        };
  }
  return stack(rows);
}
export function rectangles(
  node: Layout,
  rect: Rect = { x: 0, y: 0, width: 100, height: 100 },
): Record<string, Rect> {
  if (node.kind === 'pane') return { [node.id]: rect };
  const first = { ...rect },
    second = { ...rect };
  if (node.axis === 'row') {
    first.width *= node.ratio;
    second.x += first.width;
    second.width -= first.width;
  } else {
    first.height *= node.ratio;
    second.y += first.height;
    second.height -= first.height;
  }
  return { ...rectangles(node.first, first), ...rectangles(node.second, second) };
}
export function insertAt(node: Layout, target: string, id: string, edge: Edge): Layout {
  if (node.kind === 'pane') {
    if (node.id !== target) return node;
    const before = edge === 'left' || edge === 'top';
    const pane: Layout = { kind: 'pane', id };
    return {
      kind: 'split',
      id: uid(),
      axis: edge === 'left' || edge === 'right' ? 'row' : 'column',
      ratio: 0.5,
      first: before ? pane : node,
      second: before ? node : pane,
    };
  }
  return {
    ...node,
    first: insertAt(node.first, target, id, edge),
    second: insertAt(node.second, target, id, edge),
  };
}
export function dock(node: Layout, source: string, target: string, edge: Edge): Layout {
  if (source === target || !ids(node).includes(source) || !ids(node).includes(target)) return node;
  return insertAt(remove(node, source)!, target, source, edge);
}
export function dropZone(x: number, y: number): Edge | 'center' {
  const edges: [Edge, number][] = [
    ['left', x],
    ['right', 1 - x],
    ['top', y],
    ['bottom', 1 - y],
  ];
  edges.sort((a, b) => a[1] - b[1]);
  return edges[0][1] < 0.25 ? edges[0][0] : 'center';
}
