import { expect, it } from 'vitest';
import {
  migrateClipboardPolicy,
  paneContainers,
  pruneKeys,
  prunePaneChoices,
} from '../src/lib/device-state';
import { defaults, type Workspace } from '../src/lib/types';

const workspace = (id: string, panes: string[], groups: string[][] = []) =>
  ({
    id,
    panes: panes.map((pane) => ({ id: pane })),
    tabGroups: groups.map((group, index) => ({ id: `${id}-g${index}`, panes: group })),
  }) as unknown as Workspace;

it('removes per-pane entries for deleted panes and keeps the object when nothing changed', () => {
  const status = { a: 1, b: 2 };
  expect(pruneKeys(status, new Set(['a', 'b']))).toBe(status);
  expect(pruneKeys(status, new Set(['a']))).toEqual({ a: 1 });
});
it('removes remembered focus for deleted panes and workspaces but keeps unloaded hosts', () => {
  const { byWorkspace, byGroup } = paneContainers([workspace('w1', ['p1', 'p2'], [['p1', 'gone']])]);
  const focus = { w1: 'p9', w2: 'p3', unloaded: 'p4' };
  const seen = new Set(['w1', 'w2']);
  expect(prunePaneChoices(focus, byWorkspace, seen, false)).toEqual({ unloaded: 'p4' });
  expect(prunePaneChoices({ unloaded: 'p4' }, byWorkspace, seen, true)).toEqual({});
  expect(byGroup.get('w1-g0')).toEqual(new Set(['p1']));
  const kept = { w1: 'p2' };
  expect(prunePaneChoices(kept, byWorkspace, seen, true)).toBe(kept);
});
it('moves the old silent remote-copy default to notify once', () => {
  const old = {
    ...defaults(),
    terminal: { fontSize: 13, scrollback: 4000, gpu: true, ligatures: false, images: true, clipboard: 'write' as const },
    clipboardByHost: { a: 'write' as const, b: 'off' as const, c: 'read-write' as const },
  };
  const migrated = migrateClipboardPolicy(old);
  expect(migrated.terminal?.clipboard).toBe('notify');
  expect(migrated.terminal?.scrollback).toBe(4000);
  expect(migrated.clipboardByHost).toEqual({ a: 'notify', b: 'off', c: 'read-write' });
  const chosen = { ...migrated, terminal: { ...migrated.terminal!, clipboard: 'write' as const } };
  expect(migrateClipboardPolicy(chosen).terminal?.clipboard).toBe('write');
});
