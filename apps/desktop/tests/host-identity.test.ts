import { describe, expect, it } from 'vitest';
import { refreshHostIdentity } from '../src/lib/host-identity';
import type { Host, Profile } from '../src/lib/types';
const inventory = { machine: 'native-windows', user: 'user' };
const host = (id: string, kind: Host['kind'] = 'powershell') => ({ id, kind, socket: '' }) as Host;
const profile = (hostId: string, hostKey: string) =>
  ({ workspaces: [{ panes: [{ hostId, hostKey }] }] }) as Profile;

describe('host identity recovery', () => {
  it.each([['one', 'two'], ['two', 'one']])(
    'keeps native session owners when %s connects before %s', (first, second) => {
      const profiles = [profile('one', 'native-windows:user:'), profile('two', 'native-windows:user:')];
      refreshHostIdentity(host(first), inventory, profiles);
      refreshHostIdentity(host(second), inventory, profiles);
      expect(profiles.map(p => p.workspaces[0].panes[0].hostId)).toEqual(['one', 'two']);
      expect(profiles.map(p => p.workspaces[0].panes[0].hostKey)).toEqual([
        'native-windows:user:one', 'native-windows:user:two',
      ]);
    },
  );
  it('recovers remote pane references by machine, user and tmux socket', () => {
    const saved = profile('old-device-host', 'linux:user:');
    refreshHostIdentity(host('new-device-host', 'ssh'), { machine: 'linux', user: 'user' }, [saved]);
    expect(saved.workspaces[0].panes[0].hostId).toBe('new-device-host');
  });
  it('preserves a different machine when device host IDs collide', () => {
    const foreign = profile('same-id', 'different:user:');
    expect(refreshHostIdentity(host('same-id', 'ssh'), inventory, [foreign])).toEqual(new Set());
    expect(foreign.workspaces[0].panes[0].hostKey).toBe('different:user:');
    expect(foreign.workspaces[0].panes[0].hostDetails).toBeUndefined();
  });
  it('shares connection labels without device credentials and preserves other hosts', () => {
    const remote: Host = { id: 'new', name: 'Devbox', kind: 'tailscale', address: 'devbox.ts.net',
      user: 'ubuntu', port: 2222, socket: 'work', key: '/private/key', jump: 'private-jump', distro: '' };
    const saved = profile('old', 'linux:ubuntu:work');
    const pane = saved.workspaces[0].panes[0];
    pane.hostDetails = { name: 'Old', kind: 'ssh', address: 'old', user: 'ubuntu', socket: 'work', future: true } as typeof pane.hostDetails;
    const foreign = profile('foreign', 'different:ubuntu:work');
    expect(refreshHostIdentity(remote, { machine: 'linux', user: 'ubuntu' }, [saved, foreign])).toEqual(new Set([saved]));
    expect(refreshHostIdentity(remote, { machine: 'linux', user: 'ubuntu' }, [saved, foreign])).toEqual(new Set());
    expect(pane.hostDetails).toEqual({ name: 'Devbox', kind: 'tailscale', address: 'devbox.ts.net',
      user: 'ubuntu', port: 2222, socket: 'work', future: true });
    expect(JSON.stringify(pane.hostDetails)).not.toContain('/private/key');
    expect(JSON.stringify(pane.hostDetails)).not.toContain('private-jump');
    expect(foreign.workspaces[0].panes[0].hostDetails).toBeUndefined();
  });

});
