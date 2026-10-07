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
});
