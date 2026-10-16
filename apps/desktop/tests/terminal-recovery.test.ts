import { expect, test } from 'vitest';
import { deadPaneTarget, recoverDeadPane } from '../src/lib/terminal-recovery';
import type { PaneStatus } from '../src/lib/pane-status';
import { terminalPresentation } from '../src/lib/pane-status';

const ended = {
  attachment: 'tmux',
  nestedSsh: false,
  hostname: 'fixture',
  sessionId: '$9',
  stamp: 'server:session',
  paneId: '%12',
  paneDead: true,
} as PaneStatus;
test('an ended manually attached tmux pane offers its actual verified recovery target', () => {
  expect(deadPaneTarget(ended)).toEqual({ id: '$9', stamp: 'server:session', paneId: '%12' });
});
test('recovery sends only the observed session identity and pane, and exposes failure for retry', async () => {
  const calls: unknown[] = [];
  await expect(
    recoverDeadPane(ended, async (target) => {
      calls.push(target);
      throw Error('Session changed');
    }),
  ).rejects.toThrow('Session changed');
  expect(calls).toEqual([{ id: '$9', stamp: 'server:session', paneId: '%12' }]);
  await recoverDeadPane(ended, async (target) => {
    calls.push(target);
    return { recovered: true };
  });
  expect(calls).toHaveLength(2);
});
test('a live or stale pane refuses recovery before calling the host', async () => {
  let called = false;
  await expect(
    recoverDeadPane({ ...ended, paneDead: false }, async () => {
      called = true;
    }),
  ).rejects.toThrow('Session changed');
  expect(called).toBe(false);
});
test('a recovered named agent pane shows its verified shell type without changing its saved label', () => {
  const saved = {
    name: 'My planning pane',
    agent: 'codex',
    model: 'saved-model',
    shell: false,
    sessionId: '$9',
  };
  expect(terminalPresentation(saved, { ...ended, paneDead: false, agent: 'shell' })).toEqual({
    name: 'My planning pane',
    type: 'tmux',
    model: '',
  });
  expect(saved.agent).toBe('codex');
  expect(
    terminalPresentation(saved, { ...ended, sessionId: '$10', paneDead: false, agent: 'shell' })
      .type,
  ).toBe('codex');
  expect(
    terminalPresentation(saved, { ...ended, nestedSsh: true, paneDead: false, agent: 'shell' })
      .type,
  ).toBe('codex');
});
test('live, nested, stale and incomplete targets do not offer recovery', () => {
  for (const invalid of [
    { paneDead: false },
    { nestedSsh: true },
    { attachment: 'unknown' },
    { sessionId: '' },
    { stamp: '' },
    { paneId: '' },
    { paneId: 'other-pane' },
  ]) {
    expect(deadPaneTarget({ ...ended, ...invalid } as PaneStatus)).toBeNull();
  }
});
