import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { rebindOwner } from './layout';
import { refreshHostIdentity } from './host-identity';
import type { Host, Profile } from './types';
const fixture = () =>
  JSON.parse(
    readFileSync(new URL('../../../../shared/protocol/profile-v1.json', import.meta.url), 'utf8'),
  );
describe('shared mobile profile fixture', () => {
  it('rebinds the owner while preserving mixed hosts, layout and unknown fields', () => {
    const original = fixture();
    const rebound = rebindOwner(original, 'mobile-a');
    const host = { id: 'mobile-a', socket: 'socket-a', kind: 'ssh' } as Host;
    refreshHostIdentity(host, { machine: 'machine-a', user: 'user' }, [rebound as Profile]);
    expect(rebound.workspaces[0].panes.map((p: { hostId: string }) => p.hostId)).toEqual([
      'mobile-a',
      'desktop-b',
    ]);
    expect(rebound.workspaces[0].layout).toEqual(original.workspaces[0].layout);
    expect(rebound.workspaces[0].focused).toBe('p1');
    expect(rebound.futureTop).toEqual(original.futureTop);
    expect(rebound.workspaces[0].panes[0].futurePane).toEqual([1, 2]);
    expect(original.workspaces[0].hostId).toBe('desktop-a');
  });
});
