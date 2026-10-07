import { describe, expect, it, vi } from 'vitest';
import type { Terminal } from '@xterm/xterm';
import {
  LiveBufferSearch,
  searchPattern,
  scopeTargets,
  type SearchTarget,
} from '../src/lib/terminal-search';
const options = { query: 'upload', matchCase: false, regex: false };
function fixture(texts: string[], cols = 40, wrapped: number[] = []) {
  const markers: { line: number; isDisposed: boolean; dispose: () => void }[] = [];
  const cells = texts.map((text) =>
    Array.from(text).flatMap((char) => {
      const width = char === '界' ? 2 : 1;
      return [{ chars: char, width }, ...(width === 2 ? [{ chars: '', width: 0 }] : [])];
    }),
  );
  const buffer = {
    type: 'normal',
    baseY: 0,
    cursorY: texts.length - 1,
    length: texts.length,
    getLine: (y: number) =>
      texts[y] === undefined
        ? undefined
        : {
            length: cols,
            isWrapped: wrapped.includes(y),
            translateToString: () => texts[y],
            getCell: (x: number) => ({
              getChars: () => cells[y]?.[x]?.chars || '',
              getWidth: () => cells[y]?.[x]?.width ?? 1,
            }),
          },
  };
  const term = {
    cols,
    buffer: { active: buffer },
    registerMarker: (offset: number) => {
      const marker = {
        line: buffer.cursorY + offset,
        isDisposed: false,
        dispose() {
          this.isDisposed = true;
        },
      };
      markers.push(marker);
      return marker;
    },
    scrollToLine: vi.fn(),
    select: vi.fn(),
    getSelectionPosition: vi.fn(),
  };
  return {
    term: term as unknown as Terminal,
    markers,
    cells,
    buffer,
    texts,
    select: term.select,
    scroll: term.scrollToLine,
  };
}
describe('live terminal search', () => {
  it('treats ordinary punctuation literally and validates regular expressions', () => {
    expect(searchPattern({ ...options, query: 'a.b' })!.test('axb')).toBe(false);
    expect(searchPattern({ ...options, query: 'a.b' })!.test('a.b')).toBe(true);
    expect(() => searchPattern({ ...options, query: '[', regex: true })).toThrow();
  });
  it('finds every occurrence with optional case sensitivity', () => {
    const f = fixture(['upload Upload upload']);
    const search = new LiveBufferSearch(f.term);
    expect(search.collect(options)).toHaveLength(3);
    expect(search.collect({ ...options, matchCase: true })).toHaveLength(2);
    expect(f.markers.slice(0, 3).every((marker) => marker.isDisposed)).toBe(true);
  });
  it('joins soft wraps and selects exact physical cells', () => {
    const f = fixture(['prefix upl', 'oad suffix'], 10, [1]);
    const search = new LiveBufferSearch(f.term);
    const [hit] = search.collect(options);
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.scroll).toHaveBeenCalledWith(0);
    expect(f.select).toHaveBeenCalledWith(7, 0, 6);
  });
  it('omits terminal padding before a wide glyph wraps', () => {
    const f = fixture(['abc', '界d'], 4, [1]);
    const search = new LiveBufferSearch(f.term);
    const [hit] = search.collect({ ...options, query: 'c界' });
    expect(hit.text).toBe('abc界d');
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.select).toHaveBeenCalledWith(2, 0, 4);
  });
  it('does not join hard newline boundaries', () => {
    expect(new LiveBufferSearch(fixture(['upl', 'oad']).term).collect(options)).toHaveLength(0);
  });
  it('maps wide and surrogate-pair characters to terminal cells', () => {
    const f = fixture(['界😀upload']);
    const search = new LiveBufferSearch(f.term);
    const [hit] = search.collect(options);
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.select).toHaveBeenLastCalledWith(3, 0, 6);
    const [emoji] = search.collect({ ...options, query: '😀' });
    expect(search.reveal(emoji.id)).toBe(true);
    expect(f.select).toHaveBeenLastCalledWith(2, 0, 1);
  });
  it('rejects trimmed, overwritten and cleared matches', () => {
    const f = fixture(['upload']);
    const search = new LiveBufferSearch(f.term);
    let [hit] = search.collect(options);
    f.markers[0].isDisposed = true;
    expect(search.reveal(hit.id)).toBe(false);
    [hit] = search.collect(options);
    f.texts[0] = 'changed';
    expect(search.reveal(hit.id)).toBe(false);
    search.release();
    expect(search.reveal(hit.id)).toBe(false);
    expect(f.markers.every((marker) => marker.isDisposed)).toBe(true);
  });
  it('follows a surviving marker after scrollback trimming', () => {
    const f = fixture(['before', 'upload']);
    const search = new LiveBufferSearch(f.term);
    const [hit] = search.collect(options);
    f.texts.shift();
    f.cells.shift();
    f.markers[0].line = 0;
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.select).toHaveBeenCalledWith(0, 0, 6);
  });
  it('rejects inactive buffers and respects result limits and zero-length patterns', () => {
    const f = fixture(['upload upload upload']);
    const search = new LiveBufferSearch(f.term);
    const hits = search.collect(options, 2);
    expect(hits).toHaveLength(2);
    Object.assign(f.term.buffer, {
      active: {
        ...f.buffer,
        type: 'alternate',
      } as unknown as Terminal['buffer']['active'],
    });
    expect(search.reveal(hits[0].id)).toBe(false);
    expect(search.collect({ ...options, query: '^', regex: true })).toHaveLength(0);
  });
  it('uses each terminal’s host rather than its owning workspace host', () => {
    const targets = [
      { paneId: 'a', hostId: 'first', workspaceId: 'w1' },
      { paneId: 'b', hostId: 'second', workspaceId: 'w1' },
      { paneId: 'c', hostId: 'first', workspaceId: 'w2' },
    ] as SearchTarget[];
    expect(scopeTargets(targets, 'terminal', targets[0]).map((t) => t.paneId)).toEqual(['a']);
    expect(scopeTargets(targets, 'workspace', targets[0]).map((t) => t.paneId)).toEqual(['a', 'b']);
    expect(scopeTargets(targets, 'host', targets[0]).map((t) => t.paneId)).toEqual(['a', 'c']);
    expect(scopeTargets(targets, 'global', targets[0])).toHaveLength(3);
  });
});
