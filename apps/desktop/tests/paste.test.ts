import { expect, it } from 'vitest';
import { needsPasteConfirmation, pastePreview } from '../src/lib/paste';
import { checkCommandText, paneLifecycle } from '../src/lib/terminals';

it('asks before pasting any line break unless the user turned it off', () => {
  expect(needsPasteConfirmation('ls -la', true)).toBe(false);
  expect(needsPasteConfirmation('rm -rf build\n', undefined)).toBe(true);
  expect(needsPasteConfirmation('a\rb', true)).toBe(true);
  expect(needsPasteConfirmation('a\nb', false)).toBe(false);
});
it('previews the line count and first lines without control characters', () => {
  const preview = pastePreview('one\r\ntwo\x1b[31m\nthree\nfour\n');
  expect(preview.count).toBe(4);
  expect(preview.lines).toEqual(['one', 'two·[31m', 'three']);
  expect(preview.more).toBe(1);
  expect(pastePreview('x'.repeat(200)).lines[0]).toHaveLength(161);
});
it('rejects control characters only in app-generated command text', () => {
  expect(() => checkCommandText('/model')).not.toThrow();
  expect(() => checkCommandText('/model\r')).toThrow('control characters');
});
it('orders a late close from an old view before the new view opens the same pane', async () => {
  const calls: string[] = [];
  let finishClose = () => {};
  const close = paneLifecycle.run('pane', () => {
    calls.push('close start');
    return new Promise<void>((resolve) => (finishClose = resolve));
  });
  const open = paneLifecycle.run('pane', async () => {
    calls.push('open');
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(calls).toEqual(['close start']);
  finishClose();
  await Promise.all([close, open]);
  expect(calls).toEqual(['close start', 'open']);
});
