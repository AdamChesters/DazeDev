import { expect, it } from 'vitest';
import { balanced, ids, rectangles } from '../src/lib/layout';
import {
  insertPaneStack,
  removePanes,
  reorderWorkspaces,
  selectPanes,
} from '../src/lib/pane-transfer';
import type { Workspace } from '../src/lib/types';

it('selects arbitrary terminals with Shift and resets on a plain click', () => {
  let selection = selectPanes([], 'a', false);
  selection = selectPanes(selection, 'd', true);
  selection = selectPanes(selection, 'b', true);
  expect(selection).toEqual(['a', 'd', 'b']);
  expect(selectPanes(selection, 'd', true)).toEqual(['a', 'b']);
  expect(selectPanes(selection, 'b', false)).toEqual(['b']);
});
it('keeps destination geometry on the left and stacks the incoming group on the right', () => {
  const original = balanced(['existing-a', 'existing-b', 'viewer']);
  const next = insertPaneStack(original, ['incoming-a', 'incoming-b', 'incoming-c']);
  const rects = rectangles(next);
  for (const [id, box] of Object.entries(rectangles(original))) {
    expect(rects[id]).toEqual({ ...box, x: box.x / 2, width: box.width / 2 });
  }
  for (let i = 0; i < 3; i++) {
    const box = rects[['incoming-a', 'incoming-b', 'incoming-c'][i]];
    expect(box.x).toBe(50);
    expect(box.width).toBe(50);
    expect(box.y).toBeCloseTo((i * 100) / 3);
    expect(box.height).toBeCloseTo(100 / 3);
  }
  expect(ids(next)).toEqual([
    'existing-a',
    'existing-b',
    'viewer',
    'incoming-a',
    'incoming-b',
    'incoming-c',
  ]);
});
it('handles an empty destination and removes multiple panes without losing viewers', () => {
  const empty = removePanes(balanced(['a', 'b']), ['b', 'a']);
  expect(ids(insertPaneStack(empty, ['a', 'b']))).toEqual(['a', 'b']);
  const source = removePanes(balanced(['a', 'b', 'viewer', 'c']), ['a', 'c']);
  expect(ids(source)).toEqual(['b', 'viewer']);
});
it.each(['left', 'right', 'top', 'bottom'] as const)(
  'docks the whole group at the %s edge',
  (edge) => {
    const next = insertPaneStack(balanced(['a', 'b']), ['c', 'd'], 'b', edge);
    const boxes = rectangles(next);
    expect(ids(next).sort()).toEqual(['a', 'b', 'c', 'd']);
    expect(boxes.c.x).toBe(boxes.d.x);
    expect(boxes.c.y + boxes.c.height).toBeCloseTo(boxes.d.y);
    if (edge === 'left') expect(boxes.c.x + boxes.c.width).toBeCloseTo(boxes.b.x);
    if (edge === 'right') expect(boxes.b.x + boxes.b.width).toBeCloseTo(boxes.c.x);
    if (edge === 'top') expect(boxes.d.y + boxes.d.height).toBeCloseTo(boxes.b.y);
    if (edge === 'bottom') expect(boxes.b.y + boxes.b.height).toBeCloseTo(boxes.c.y);
  },
);
const workspace = (id: string, hostId = 'host'): Workspace => ({
  id,
  hostId,
  name: id,
  panes: [],
  layout: { kind: 'pane', id: '' },
  focused: '',
});
it('reorders either direction and preserves workspace objects and host ownership', () => {
  const list = ['a', 'b', 'c'].map((id) => workspace(id));
  const next = reorderWorkspaces(list, 'a', 'c', true);
  expect(next.map((w) => w.id)).toEqual(['b', 'c', 'a']);
  expect(next[2]).toBe(list[0]);
  expect(reorderWorkspaces(next, 'a', 'b', false).map((w) => w.id)).toEqual(['a', 'b', 'c']);
  expect(list.map((w) => w.id)).toEqual(['a', 'b', 'c']);
  expect(reorderWorkspaces(list, 'a', 'missing', true)).toBe(list);
  expect(reorderWorkspaces(list, 'a', 'a', true)).toBe(list);
  const mixed = [workspace('a'), workspace('b', 'other-host')];
  expect(reorderWorkspaces(mixed, 'a', 'b', false)).toBe(mixed);
});
