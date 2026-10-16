import { describe, expect, it } from 'vitest';
import {
  checked,
  checkDeviceFile,
  checkInventory,
  checkModels,
  checkProfile,
  checkRepositories,
  checkSessions,
  checkUpdate,
} from '../src/lib/ipc';
import { blankProfile, defaults } from '../src/lib/types';

const pane = { id: 'p1', hostId: 'h1', sessionId: '$1', name: 'Terminal 1' };
const profile = {
  ...blankProfile(),
  revision: 3,
  workspaces: [
    {
      id: 'w1',
      hostId: 'h1',
      name: 'Main',
      panes: [pane],
      focused: 'p1',
      layout: {
        kind: 'split',
        id: 's',
        axis: 'row',
        ratio: 0.5,
        first: { kind: 'pane', id: 'p1' },
        second: { kind: 'pane', id: '' },
      },
    },
  ],
};

describe('IPC result checks', () => {
  it('accepts well-formed results unchanged', () => {
    expect(checked('profile_load', checkProfile, profile)).toBe(profile);
    const device = { ...defaults(), recovery: { h1: profile }, localWorkspaces: blankProfile() };
    expect(checked('device_load', checkDeviceFile, device)).toBe(device);
    // A partial device file is fine: defaults fill the rest.
    expect(checked('device_load', checkDeviceFile, { hosts: [] })).toEqual({ hosts: [] });
    expect(checked('update_check', checkUpdate, null)).toBeNull();
    expect(checked('model_list', checkModels, [])).toEqual([]);
    const inventory = { user: 'dev', agents: [{ id: 'codex' }], repos: [], sessions: [] };
    expect(checked('inventory', checkInventory, inventory)).toBe(inventory);
  });

  it('names the command and the bad field', () => {
    const broken = { ...profile, workspaces: [{ ...profile.workspaces[0], panes: {} }] };
    expect(() => checked('profile_load', checkProfile, broken)).toThrow(
      'The profile_load reply was malformed: result.workspaces[0].panes should be a list.',
    );
    expect(() => checked('sessions', checkSessions, [{ id: 7, name: 'x' }])).toThrow(
      'result[0].id should be a string',
    );
    expect(() =>
      checked('update_check', checkUpdate, { version: '1.0.0', notes: '', size: '9' }),
    ).toThrow('result.size should be a number');
    expect(() => checked('model_list', checkModels, { models: [] })).toThrow(
      'result should be a list',
    );
    expect(() =>
      checked('repositories', checkRepositories, { repos: [{ path: '/r' }], warnings: [1] }),
    ).toThrow('result.warnings[0] should be a string');
    expect(() => checked('device_load', checkDeviceFile, { recovery: { h1: null } })).toThrow(
      'result.recovery.h1 should be an object',
    );
    expect(() =>
      checked('profile_load', checkProfile, {
        ...profile,
        workspaces: [{ ...profile.workspaces[0], layout: { kind: 'grid', id: 'x' } }],
      }),
    ).toThrow('result.workspaces[0].layout.kind should be "pane" or "split"');
  });
});
