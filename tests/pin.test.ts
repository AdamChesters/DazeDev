import { it, expect } from 'vitest';
import { parsePinStatus, validPin } from '../src/lib/pin';
it('rejects missing or malformed native gate status instead of treating it as disabled', () => {
  for (const value of [
    null,
    {},
    { enabled: false },
    { enabled: false, locked: false, unavailable: false, retryAfter: -1 },
    { enabled: false, locked: false, unavailable: true, retryAfter: 0 },
  ])
    expect(() => parsePinStatus(value)).toThrow();
  expect(
    parsePinStatus({ enabled: false, locked: false, unavailable: false, retryAfter: 0 }).locked,
  ).toBe(false);
  expect(
    parsePinStatus({ enabled: true, locked: true, unavailable: false, retryAfter: 2 }).locked,
  ).toBe(true);
});
it('requires six to twelve ASCII digits', () => {
  expect(validPin('684291')).toBe(true);
  expect(validPin('684291739182')).toBe(true);
  for (const value of ['12345', '1234567890123', '１２３４５６', '123 45', '123456\n', 'abcdef'])
    expect(validPin(value)).toBe(false);
});
