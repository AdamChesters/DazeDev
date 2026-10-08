import { describe, it, expect } from 'vitest';
import { tile, split, remove, resize, swap, ids, rebindOwner } from '../src/lib/layout';
import { parseCommand } from '../src/lib/commands';
describe('workspace layout', () => {
  it.each([1, 2, 3, 4, 7, 12, 37])('preserves every pane at count %i', (n) => {
    const panes = Array.from({ length: n }, (_, i) => String(i));
    expect(ids(tile(panes))).toEqual(panes);
  });
  it('collapses a removed branch without losing other panes', () => {
    const tree = split(tile(['a', 'b']), 'a', 'c', 'column');
    expect(ids(remove(tree, 'a')!)).toEqual(['c', 'b']);
  });
  it('moves panes without changing session identity', () => {
    expect(ids(swap(tile(['a', 'b', 'c']), 'a', 'c'))).toEqual(['c', 'b', 'a']);
  });
  it('bounds divider positions and keeps all pane references', () => {
    const tree = tile(['a', 'b']);
    const result = resize(tree, tree.id, 5);
    expect(result.kind === 'split' && result.ratio).toBe(0.92);
    expect(ids(result)).toEqual(['a', 'b']);
  });
});
describe('offline management parser', () => {
  it('resolves arbitrary pane counts', () => {
    expect(parseCommand('new group with 12 Codex terminals', [], 'dev')).toMatchObject({
      kind: 'new',
      count: 12,
      agent: 'codex',
    });
  });
  it('recognizes spoken counts', () => {
    expect(parseCommand('new group with four Claude terminals', [], 'dev')).toMatchObject({
      count: 4,
      agent: 'claude',
    });
  });
  it('does not interpret arbitrary shell commands', () => {
    expect(() => parseCommand('rm -rf /', [], 'dev')).toThrow();
  });
  it('keeps destruction an explicit action requiring UI confirmation', () => {
    expect(parseCommand('destroy this session', [], 'dev')).toEqual({ kind: 'destroy' });
  });
});

it('rebinds saved owning-host IDs when restored on another device', () => {
  const p = { workspaces: [{ hostId: 'old', panes: [{ hostId: 'old' }, { hostId: 'other' }] }] };
  expect(rebindOwner(p, 'new').workspaces[0]).toEqual({
    hostId: 'new',
    panes: [{ hostId: 'new' }, { hostId: 'other' }],
  });
  expect(p.workspaces[0].hostId).toBe('old');
});

describe('host-scoped voice launch', () => {
  it('resolves repositories, identities and model controls on the spoken target host', () => {
    const hosts = [
      { id: 'local', name: 'Laptop' },
      { id: 'remote', name: 'Server' },
    ] as import('../src/lib/types').Host[];
    const local = {
      repos: [{ name: 'core', path: '/local/core' }],
      identities: [{ login: 'LocalUser' }],
      agents: [],
    } as unknown as import('../src/lib/types').Inventory;
    const remote = {
      repos: [{ name: 'core', path: '/remote/core' }],
      identities: [{ login: 'RemoteUser' }],
      agents: [
        { id: 'codex', models: [{ id: 'test-model', name: 'Test Model', efforts: ['high'] }] },
      ],
    } as unknown as import('../src/lib/types').Inventory;
    expect(
      parseCommand(
        'new group with four Codex terminals on Server core under RemoteUser model test-model high effort',
        hosts,
        'local',
        local,
        { local, remote },
      ),
    ).toMatchObject({
      hostId: 'remote',
      repo: '/remote/core',
      identity: 'RemoteUser',
      model: 'test-model',
      effort: 'high',
      count: 4,
    });
  });
});

describe('named session voice commands', () => {
  it('preserves a single requested session name separately from launch options', () => {
    expect(
      parseCommand(
        'create a new tmux session named review using Codex model test-model',
        [],
        'dev',
      ),
    ).toMatchObject({
      kind: 'new',
      count: 1,
      name: 'review',
      exactName: true,
      agent: 'codex',
      model: 'test-model',
    });
  });
  it('accepts plural tmux sessions and spoken eleven', () => {
    expect(parseCommand('new group with eleven tmux sessions', [], 'dev')).toMatchObject({
      kind: 'new',
      count: 11,
      exactName: false,
    });
  });
});
