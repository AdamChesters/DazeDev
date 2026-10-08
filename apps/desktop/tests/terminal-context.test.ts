import { expect, it } from 'vitest';
import { terminalInputChangesContext } from '../src/lib/terminals';

it('keeps a review destination valid across terminal focus reports', () => {
  expect(terminalInputChangesContext('\x1b[I')).toBe(false);
  expect(terminalInputChangesContext('\x1b[O')).toBe(false);
});

it.each(['answer', '\r', '\x03', '\x1b[A', '\x1b[200~pasted text\x1b[201~'])(
  'invalidates a review destination after actual input %j',
  (data) => expect(terminalInputChangesContext(data)).toBe(true),
);
