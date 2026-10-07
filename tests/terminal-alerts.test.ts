import { expect, it } from 'vitest';
import { terminalNotification } from '../src/lib/terminal-alerts';
it('accepts explicit notifications while excluding progress and control sequences', () => {
  expect(terminalNotification(9, 'Ready for your reply')).toBe('Ready for your reply');
  expect(terminalNotification(777, 'notify;Agent;Finished')).toBe('Agent: Finished');
  expect(terminalNotification(9, '4;1;50')).toBeNull();
  expect(terminalNotification(777, 'dazedev-shell')).toBeNull();
  expect(terminalNotification(777, 'dazedev-ready:secret:123')).toBeNull();
  expect(terminalNotification(9, 'a'.repeat(1000))?.length).toBe(240);
});
