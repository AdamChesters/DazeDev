import { expect, it } from 'vitest';
import {
  CODEX_PASTE_LIMIT,
  needsPasteConfirmation,
  needsPastePreview,
  pasteLength,
  pastePieces,
  pastePreview,
  splitPaste,
} from '../src/lib/paste';
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
it('counts characters as an agent does: code points, CRLF as one', () => {
  expect(pasteLength('a\r\nb\rc\n')).toBe(6);
  expect(pasteLength('\u{1f600}x')).toBe(2);
  expect(pasteLength('\ud800x')).toBe(2);
  expect(needsPastePreview('x'.repeat(2000), true)).toBe(false);
  expect(needsPastePreview('x'.repeat(2001), undefined)).toBe(true);
  expect(needsPastePreview('x'.repeat(2001), false)).toBe(false);
});
it('splits text into pieces that join back exactly, preferring line ends', () => {
  const lines = Array.from({ length: 40 }, (_, i) => `line ${i} ${'w'.repeat(40)}`);
  for (const text of [
    lines.join('\n'),
    lines.join('\r\n') + '\r\n',
    'x'.repeat(2500),
    'word '.repeat(450),
    '\u{1f600}'.repeat(1500),
    'a'.repeat(999) + '\r\n' + 'b'.repeat(999),
  ]) {
    const pieces = splitPaste(text);
    expect(pieces.join('')).toBe(text);
    for (const piece of pieces) {
      expect(pasteLength(piece)).toBeLessThanOrEqual(CODEX_PASTE_LIMIT);
      expect(piece.startsWith('\n') && text.includes('\r\n')).toBe(false);
      expect(/^[\udc00-\udfff]/.test(piece)).toBe(false);
    }
  }
  const pieces = splitPaste(lines.join('\n'));
  expect(pieces.slice(0, -1).every((piece) => piece.endsWith('\n'))).toBe(true);
  expect(
    splitPaste('word '.repeat(450))
      .slice(0, -1)
      .every((p) => p.endsWith(' ')),
  ).toBe(true);
  expect(splitPaste('')).toEqual([]);
});
it('splits only bracketed pastes into Codex that it would collapse but can show inline', () => {
  const medium = 'm'.repeat(1500);
  expect(pastePieces(medium, 'codex', true)).toHaveLength(2);
  expect(pastePieces(medium, 'codex', false)).toEqual([medium]);
  expect(pastePieces(medium, 'claude', true)).toEqual([medium]);
  expect(pastePieces(medium, 'shell', true)).toEqual([medium]);
  expect(pastePieces('s'.repeat(1000), 'codex', true)).toEqual(['s'.repeat(1000)]);
  expect(pastePieces('l'.repeat(2001), 'codex', true)).toEqual(['l'.repeat(2001)]);
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
