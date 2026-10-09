import { expect, it, vi, afterEach } from 'vitest';
import { HOSTLESS, workspaceHosts } from '../src/lib/workspace-groups';
import { cleanTabGroups, tabLayout, retainTabPanes, tabSelections } from '../src/lib/terminal-tabs';
import { columns, ids } from '../src/lib/layout';
import { ReviewHotkey } from '../src/lib/review-hotkey';
import { mergeChangelog } from '../src/lib/changelog';
import { authenticationState } from '../src/lib/connection';
import type { Workspace, Host } from '../src/lib/types';
afterEach(() => vi.useRealTimers());
it('tabs keep all terminal identities when switching and restoring a saved layout', () => {
  const groups = [{ id: 'tabs', panes: ['a', 'b'] }];
  const layout = columns(['a', 'b', 'c']);
  const switched = tabLayout(layout, groups, { tabs: 'b' });
  expect(ids(switched)).toEqual(['b', 'c']);
  expect(ids(retainTabPanes(switched, groups)).sort()).toEqual(['a', 'b', 'c']);
  expect(ids(layout)).toEqual(['a', 'b', 'c']);
  expect(cleanTabGroups(groups, ['b', 'c'])).toEqual([]);
});
it('search and dictation focus reveal a previously hidden tab', () => {
  const workspace = {
    panes: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
    focused: 'b',
    tabGroups: [{ id: 'tabs', panes: ['a', 'b'] }],
  } as Workspace;
  const active = { tabs: 'a' };
  expect(
    ids(tabLayout(columns(['a', 'b', 'c']), workspace.tabGroups, tabSelections(workspace, active))),
  ).toEqual(['b', 'c']);
  expect(active.tabs).toBe('a');
});
it('hostless groups deduplicate connections by verified host identity and report unknown hosts', () => {
  const hosts = [{ id: 'new-id', fingerprint: 'machine:user:socket', name: 'Dev' }] as Host[];
  const workspace = {
    hostId: HOSTLESS,
    panes: [
      { hostId: 'old-id', hostKey: 'machine:user:socket' },
      { hostId: 'old-id', hostKey: 'machine:user:socket' },
      { hostId: 'new-id', hostKey: 'different', hostDetails: { name: 'Unknown' } },
    ],
  } as Workspace;
  const result = workspaceHosts(workspace, hosts, {});
  expect(result.required.map((h) => h.id)).toEqual(['new-id']);
  expect(result.missing).toEqual(['Unknown']);
  expect(result.panes[0].hostId).toBe('new-id');
  expect(workspace.panes[0].hostId).toBe('old-id');
  expect(workspaceHosts(workspace, hosts, { 'new-id': 'Connected' }).required).toEqual([]);
});
it('a review double-tap sends once without starting another recording', () => {
  vi.useFakeTimers();
  const gesture = new ReviewHotkey(),
    start = vi.fn(),
    send = vi.fn();
  gesture.tap(start, send);
  vi.advanceTimersByTime(200);
  gesture.tap(start, send);
  vi.advanceTimersByTime(1000);
  expect(start).not.toHaveBeenCalled();
  expect(send).toHaveBeenCalledTimes(1);
  gesture.tap(start, send);
  vi.advanceTimersByTime(351);
  expect(start).toHaveBeenCalledTimes(1);
  gesture.tap(start, send);
  gesture.cancel();
  vi.advanceTimersByTime(400);
  expect(start).toHaveBeenCalledTimes(1);
});
it('cross-platform history stays labelled and rejects malformed catalog entries', () => {
  const base = [
    { platform: 'desktop' as const, version: '1.0.0', date: '2026-10-01', notes: 'Desktop' },
  ];
  expect(
    mergeChangelog(
      [
        { platform: 'mobile', version: '9.0.0', date: '2026-10-09', notes: 'Mobile' },
        { platform: 'wrong', version: '10', date: '2026-10-09', notes: 'bad' },
      ],
      base,
    ).map((e) => e.platform),
  ).toEqual(['mobile', 'desktop']);
  expect(mergeChangelog(null, base)).toEqual(base);
});
it('unknown keys do not claim a changed host identity', () => {
  expect(
    authenticationState(
      'No ED25519 host key is known and you have requested strict checking. Host key verification failed.',
    ),
  ).toBe('host-unknown');
  expect(
    authenticationState('REMOTE HOST IDENTIFICATION HAS CHANGED! Host key verification failed.'),
  ).toBe('host-error');
  expect(authenticationState('TAILSCALE_WINDOWS_TARGET: unsupported', true)).toBe(
    'unsupported-target',
  );
});
