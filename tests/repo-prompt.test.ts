import { expect, it } from 'vitest';
import { reviewPasteText } from '../src/lib/terminals';

it('cannot submit a multiline orientation prompt into an unbracketed shell', () => {
  expect(reviewPasteText('Read instructions.\r\nThen inspect.\t Do not edit.\n', false)).toBe(
    'Read instructions. Then inspect.  Do not edit.',
  );
  expect(reviewPasteText('First\nSecond', true)).toBe('First\nSecond');
  expect(() => reviewPasteText('text\u001b[200~command', true)).toThrow('control characters');
});
