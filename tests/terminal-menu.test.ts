import { it, expect } from 'vitest';
import {
  terminalMenuDefaults,
  normalizeTerminalMenuOrder,
  orderedTerminalMenu,
  moveTerminalMenuItem,
  type TerminalMenuItem,
} from '../src/lib/terminal-menu';
const visible: TerminalMenuItem[] = [
  { id: 'copy', label: 'Copy' },
  { id: 'split', label: 'Split', kind: 'split' },
  { id: 'rename', label: 'Rename' },
];
it('defaults Split first while keeping conditional commands out of the visible menu', () => {
  expect(orderedTerminalMenu(visible, undefined).map((i) => i.id)).toEqual([
    'split',
    'copy',
    'rename',
  ]);
  expect(
    orderedTerminalMenu(
      visible.filter((i) => i.id !== 'rename'),
      ['rename', 'copy', 'split'],
    ).map((i) => i.id),
  ).toEqual(['copy', 'split']);
});
it('normalizes corrupt or old saved settings without duplicate or unknown actions', () => {
  expect(normalizeTerminalMenuOrder(null)).toEqual([...terminalMenuDefaults]);
  const order = normalizeTerminalMenuOrder(['rename', 'missing', 'rename', 7, 'copy']);
  expect(order.slice(0, 2)).toEqual(['rename', 'copy']);
  expect(order).toHaveLength(terminalMenuDefaults.length);
  expect(new Set(order).size).toBe(terminalMenuDefaults.length);
});
it('persists an order through JSON round-trip and restores temporarily hidden commands', () => {
  const moved = moveTerminalMenuItem(undefined, 'copy', 'split');
  const saved = JSON.parse(JSON.stringify({ paneMenuOrder: moved }));
  expect(orderedTerminalMenu(visible, saved.paneMenuOrder).map((i) => i.id)).toEqual([
    'copy',
    'split',
    'rename',
  ]);
  const temporary = orderedTerminalMenu(
    visible.filter((i) => i.id !== 'copy'),
    saved.paneMenuOrder,
  );
  expect(temporary.map((i) => i.id)).toEqual(['split', 'rename']);
  expect(orderedTerminalMenu(visible, saved.paneMenuOrder)[0].id).toBe('copy');
});
it('supports before/after drops and leaves aborted/self drops unchanged', () => {
  const before = normalizeTerminalMenuOrder(['copy', 'split', 'rename']);
  const after = moveTerminalMenuItem(before, 'copy', 'split', true);
  expect(after.slice(0, 3)).toEqual(['split', 'copy', 'rename']);
  expect(moveTerminalMenuItem(before, 'copy', 'copy')).toEqual(before);
});
it('pins Close last even with old or reordered preferences', () => {
  const items: TerminalMenuItem[] = [...visible, { id: 'close', label: 'Close' }];
  expect(orderedTerminalMenu(items, ['close', 'rename', 'copy']).at(-1)?.id).toBe('close');
  expect(moveTerminalMenuItem(undefined, 'close', 'split').at(-1)).toBe('close');
  expect(moveTerminalMenuItem(undefined, 'copy', 'close').at(-1)).toBe('close');
});
