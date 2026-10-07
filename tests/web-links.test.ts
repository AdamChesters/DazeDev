import { describe, expect, it } from 'vitest';
import type { Terminal } from '@xterm/xterm';
import { wrappedWebLinks } from '../src/lib/web-links';

function terminal(rows: { text: string; wrapped?: boolean }[], cols = 40): Terminal {
  return {
    cols,
    buffer: {
      active: {
        length: rows.length,
        getLine: (y: number) =>
          rows[y] && {
            length: cols,
            isWrapped: !!rows[y].wrapped,
            translateToString: () => rows[y].text,
            getCell: (x: number) => ({ getWidth: () => 1, getChars: () => rows[y].text[x] || '' }),
          },
      },
    },
  } as unknown as Terminal;
}
const url = 'https://server.examplex-tests.invalid:8443/?v=069.3';
describe('complete wrapped web links', () => {
  it('retains a long URL on every soft-wrapped row', () => {
    const full = 'https://example.com/' + 'a'.repeat(3000);
    const text = 'URL: ' + full;
    const rows = Array.from({ length: Math.ceil(text.length / 40) }, (_, y) => ({
      text: text.slice(y * 40, (y + 1) * 40),
      wrapped: y > 0,
    }));
    const term = terminal(rows);
    for (let y = 1; y <= rows.length; y++) expect(wrappedWebLinks(term, y)[0].text).toBe(full);
  });
  it('recovers a hostname split by the renderer at a hyphen, from either row', () => {
    const term = terminal(
      [{ text: '  https://server.examplex-' }, { text: '  tests.invalid:8443/?v=069.3' }],
      30,
    );
    for (const y of [1, 2]) {
      const [link] = wrappedWebLinks(term, y);
      expect(link.text).toBe(url);
      expect(link.range).toEqual({ start: { x: 3, y: 1 }, end: { x: 29, y: 2 } });
    }
  });
  it('does not join complete URLs or unrelated output lines', () => {
    const term = terminal([
      { text: 'https://example.com/path' },
      { text: 'other.example.com/private' },
    ]);
    expect(wrappedWebLinks(term, 1)[0].text).toBe('https://example.com/path');
    expect(wrappedWebLinks(term, 2)).toEqual([]);
    expect(
      wrappedWebLinks(
        terminal([{ text: 'https://example-' }, { text: 'output continued' }], 20),
        1,
      )[0].text,
    ).toBe('https://example-');
  });
  it('recovers the reported renderer break in a URL filename, from either row', () => {
    const full = 'https://server.examplex-tests.invalid:8443/assets/brain-social-card.jpg?v=086.1';
    const term = terminal(
      [
        { text: 'The social image (https://server.examplex-tests.invalid:8443/assets/brain-' },
        { text: 'social-card.jpg?v=086.1) now uses the blended viewer defaults.' },
      ],
      76,
    );
    for (const y of [1, 2]) {
      const [link] = wrappedWebLinks(term, y);
      expect(link.text).toBe(full);
      expect(link.range).toEqual({ start: { x: 19, y: 1 }, end: { x: 23, y: 2 } });
    }
  });
  it('does not append unrelated prose to a path ending in a hyphen', () => {
    const term = terminal(
      [{ text: 'https://example.com/brain-' }, { text: 'continued in another paragraph.' }],
      30,
    );
    expect(wrappedWebLinks(term, 1)[0].text).toBe('https://example.com/brain-');
    expect(wrappedWebLinks(term, 2)).toEqual([]);
  });
  it('recovers a filename break after the URL prefix has already soft-wrapped', () => {
    const first = 'The social image (https://server.examplex-tests.invalid:8443/assets/brain-';
    const rows = Array.from({ length: Math.ceil(first.length / 40) }, (_, y) => ({
      text: first.slice(y * 40, (y + 1) * 40),
      wrapped: y > 0,
    }));
    rows.push({ text: 'social-card.jpg?v=086.1) now uses the defaults.', wrapped: false });
    const term = terminal(rows);
    for (const y of [1, 2, 3])
      expect(wrappedWebLinks(term, y)[0].text).toBe(
        'https://server.examplex-tests.invalid:8443/assets/brain-social-card.jpg?v=086.1',
      );
  });
  it('does not open a truncated URL when the complete line exceeds the scan budget', () => {
    const rows = Array.from({ length: 1700 }, (_, y) => ({
      text: y === 0 ? 'https://example.com/' + 'a'.repeat(20) : 'a'.repeat(40),
      wrapped: y > 0,
    }));
    expect(wrappedWebLinks(terminal(rows), 1)).toEqual([]);
  });
  it('recovers the actual native snapshot rows without soft-wrap markers', () => {
    const term = terminal(
      [
        { text: 'The social image (https://server.examplex' },
        { text: '-tests.invalid:8443/assets/brain-' },
        { text: 'social-card.jpg?v=086.1) now uses the blend' },
        { text: 'ed viewer defaults.' },
      ],
      41,
    );
    for (const y of [1, 2, 3])
      expect(wrappedWebLinks(term, y)[0].text).toBe(
        'https://server.examplex-tests.invalid:8443/assets/brain-social-card.jpg?v=086.1',
      );
  });
  it.each(['social-card?v=086.1', 'social-card.jpg?v=086.1'])(
    'recovers native printed-space padding before %s',
    (suffix) => {
      const term = terminal(
        [
          { text: 'The social image (https://server.examplex' },
          { text: '-tests.invalid:8443/assets/brain-        ', wrapped: true },
          { text: suffix + ') now uses the blended' },
          { text: ' viewer defaults.', wrapped: true },
        ],
        41,
      );
      for (const y of [1, 2, 3]) {
        const [link] = wrappedWebLinks(term, y);
        expect(link.text).toBe('https://server.examplex-tests.invalid:8443/assets/brain-' + suffix);
        expect(link.range.end).toEqual({ x: suffix.length, y: 3 });
      }
    },
  );
  it('does not treat printed-space padding as a full-width URL continuation', () => {
    const term = terminal([
      { text: 'https://example.com/path'.padEnd(40, ' ') },
      { text: 'other.example.com/private' },
    ]);
    expect(wrappedWebLinks(term, 1)[0].text).toBe('https://example.com/path');
    expect(wrappedWebLinks(term, 2)).toEqual([]);
  });
  it('retains URLs across multiple full-width snapshot rows', () => {
    const full = 'https://example.com/' + 'a'.repeat(140);
    const rows = Array.from({ length: Math.ceil(full.length / 40) }, (_, y) => ({
      text: full.slice(y * 40, (y + 1) * 40),
    }));
    const term = terminal(rows);
    for (let y = 1; y <= rows.length; y++) expect(wrappedWebLinks(term, y)[0].text).toBe(full);
  });
  it('does not recover a URL whose beginning has been evicted from scrollback', () => {
    expect(wrappedWebLinks(terminal([{ text: 'https://example.com', wrapped: true }]), 1)).toEqual(
      [],
    );
  });
});
