import { expect, it } from 'vitest';
import { AlertThrottle, commandFinishedAlert, terminalNotification } from '../src/lib/terminal-alerts';
it('accepts explicit notifications while excluding progress and control sequences', () => {
  expect(terminalNotification(9, 'Ready for your reply')).toBe('Ready for your reply');
  expect(terminalNotification(777, 'notify;Agent;Finished')).toBe('Agent: Finished');
  expect(terminalNotification(9, '4;1;50')).toBeNull();
  expect(terminalNotification(777, 'dazedev-shell')).toBeNull();
  expect(terminalNotification(777, 'dazedev-ready:secret:123')).toBeNull();
  expect(terminalNotification(9, 'a'.repeat(1000))?.length).toBe(240);
});

it('throttles alerts per pane so one busy pane cannot hide another', () => {
  const throttle = new AlertThrottle(3000);
  expect(throttle.allow('a', 0)).toBe(true);
  expect(throttle.allow('a', 1000)).toBe(false);
  expect(throttle.allow('b', 1000)).toBe(true);
  expect(throttle.allow('a', 3000)).toBe(true);
  throttle.retain(new Set(['b']));
  expect(throttle.allow('a', 3001)).toBe(true);
});
it('reports a finished command only when unwatched or long running', () => {
  expect(commandFinishedAlert({ startedAt: 0, now: 2000, watched: true })).toBe(false);
  expect(commandFinishedAlert({ startedAt: 0, now: 2000, watched: false })).toBe(true);
  expect(commandFinishedAlert({ startedAt: 0, now: 10000, watched: true })).toBe(true);
});
