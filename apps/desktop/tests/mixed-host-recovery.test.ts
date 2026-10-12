import { describe, expect, it } from 'vitest';
import { MixedHostRecovery, hasReusableAccess } from '../src/lib/mixed-host-recovery';
import type { Host, Workspace } from '../src/lib/types';
const host = (id: string, kind: Host['kind'] = 'ssh', key = '') =>
  ({ id, kind, key, name: id }) as Host;
const workspace = (name: string, hosts: string[]) =>
  ({ name, hostId: 'a', panes: hosts.map((hostId) => ({ hostId })) }) as Workspace;

describe('mixed host recovery', () => {
  it('groups secondary hosts across saved workspaces and ignores unknown, primary and already connecting hosts', () => {
    const recovery = new MixedHostRecovery();
    expect(
      recovery.plan(
        'a',
        [workspace('One', ['a', 'b', 'b', 'missing', 'c']), workspace('Two', ['b'])],
        [host('a'), host('b', 'ssh', '/key'), host('c', 'tailscale')],
        new Set(['c']),
      ),
    ).toEqual([{ hostId: 'b', workspaces: ['One', 'Two'] }]);
  });
  it('never prompts again after decline, an explicit disconnect or concurrent owner reconnects', () => {
    const recovery = new MixedHostRecovery();
    const args = [
      'a',
      [workspace('One', ['b'])],
      [host('b', 'tailscale')],
      new Set<string>(),
    ] as const;
    expect(recovery.plan(...args)).toHaveLength(1);
    recovery.answer('b');
    expect(recovery.plan(...args)).toEqual([]);
    expect(recovery.plan('c', [workspace('Two', ['b'])], args[2], args[3])).toEqual([]);
  });
  it('does not infer a saved password or usable SSH agent from a successful past connection', () => {
    expect(hasReusableAccess({ ...host('b'), fingerprint: 'known' })).toBe(false);
    expect(hasReusableAccess(host('b', 'ssh', '/key'))).toBe(true);
    expect(hasReusableAccess(host('b', 'tailscale'))).toBe(true);
    expect(hasReusableAccess(host('b', 'wsl'))).toBe(true);
  });
});
