import { Worker as NodeWorker } from 'node:worker_threads';
import { buildSync } from 'esbuild';
import { describe, expect, it, vi } from 'vitest';
import type { Terminal } from '@xterm/xterm';
import {
  LiveBufferSearch,
  SearchWorkers,
  findLineMatches,
  searchPattern,
  scopeTargets,
  type SearchOptions,
  type SearchTarget,
  type SearchWorkerLike,
} from '../src/lib/terminal-search';
// Runs the worker's matching code in-process, and counts created workers.
function inlineWorkers() {
  let created = 0;
  const workers = new SearchWorkers(() => {
    created++;
    const worker: SearchWorkerLike = {
      onmessage: null,
      onerror: null,
      terminate() {},
      postMessage(message) {
        const { lines, options, limit } = message as {
          lines: string[];
          options: SearchOptions;
          limit: number;
        };
        queueMicrotask(() =>
          worker.onmessage?.({
            data: { matches: findLineMatches(lines, options, limit) },
          } as MessageEvent),
        );
      },
    };
    return worker;
  });
  return { workers, created: () => created };
}
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
  it('treats ordinary punctuation literally and validates regular expressions', async () => {
    expect(searchPattern({ ...options, query: 'a.b' })!.test('axb')).toBe(false);
    expect(searchPattern({ ...options, query: 'a.b' })!.test('a.b')).toBe(true);
    expect(() => searchPattern({ ...options, query: '[', regex: true })).toThrow();
  });
  it('finds every occurrence with optional case sensitivity', async () => {
    const f = fixture(['upload Upload upload']);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    expect(await search.collectAsync(options)).toHaveLength(3);
    expect(await search.collectAsync({ ...options, matchCase: true })).toHaveLength(2);
  });
  it('keeps the previous results usable until the next refresh completes', async () => {
    const f = fixture(['upload Upload upload']);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    const [first] = await search.collectAsync(options);
    const [second] = await search.collectAsync(options);
    expect(search.reveal(first.id)).toBe(true);
    expect(search.reveal(second.id)).toBe(true);
    await search.collectAsync(options);
    expect(search.reveal(first.id)).toBe(false);
    expect(f.markers.slice(0, 3).every((marker) => marker.isDisposed)).toBe(true);
  });
  it('reuses one worker for every search', async () => {
    const pool = inlineWorkers();
    const a = new LiveBufferSearch(fixture(['upload']).term, pool.workers);
    const b = new LiveBufferSearch(fixture(['upload']).term, pool.workers);
    await Promise.all([a.collectAsync(options), b.collectAsync(options), a.collectAsync(options)]);
    expect(pool.created()).toBe(1);
  });
  it('joins soft wraps and selects exact physical cells', async () => {
    const f = fixture(['prefix upl', 'oad suffix'], 10, [1]);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    const [hit] = await search.collectAsync(options);
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.scroll).toHaveBeenCalledWith(0);
    expect(f.select).toHaveBeenCalledWith(7, 0, 6);
  });
  it('omits terminal padding before a wide glyph wraps', async () => {
    const f = fixture(['abc', '界d'], 4, [1]);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    const [hit] = await search.collectAsync({ ...options, query: 'c界' });
    expect(hit.text).toBe('abc界d');
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.select).toHaveBeenCalledWith(2, 0, 4);
  });
  it('does not join hard newline boundaries', async () => {
    expect(
      await new LiveBufferSearch(
        fixture(['upl', 'oad']).term,
        inlineWorkers().workers,
      ).collectAsync(options),
    ).toHaveLength(0);
  });
  it('maps wide and surrogate-pair characters to terminal cells', async () => {
    const f = fixture(['界😀upload']);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    const [hit] = await search.collectAsync(options);
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.select).toHaveBeenLastCalledWith(3, 0, 6);
    const [emoji] = await search.collectAsync({ ...options, query: '😀' });
    expect(search.reveal(emoji.id)).toBe(true);
    expect(f.select).toHaveBeenLastCalledWith(2, 0, 1);
  });
  it('rejects trimmed, overwritten and cleared matches', async () => {
    const f = fixture(['upload']);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    let [hit] = await search.collectAsync(options);
    f.markers[0].isDisposed = true;
    expect(search.reveal(hit.id)).toBe(false);
    [hit] = await search.collectAsync(options);
    f.texts[0] = 'changed';
    expect(search.reveal(hit.id)).toBe(false);
    search.release();
    expect(search.reveal(hit.id)).toBe(false);
    expect(f.markers.every((marker) => marker.isDisposed)).toBe(true);
  });
  it('follows a surviving marker after scrollback trimming', async () => {
    const f = fixture(['before', 'upload']);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    const [hit] = await search.collectAsync(options);
    f.texts.shift();
    f.cells.shift();
    f.markers[0].line = 0;
    expect(search.reveal(hit.id)).toBe(true);
    expect(f.select).toHaveBeenCalledWith(0, 0, 6);
  });
  it('rejects inactive buffers and respects result limits and zero-length patterns', async () => {
    const f = fixture(['upload upload upload']);
    const search = new LiveBufferSearch(f.term, inlineWorkers().workers);
    const hits = await search.collectAsync(options, 2);
    expect(hits).toHaveLength(2);
    Object.assign(f.term.buffer, {
      active: {
        ...f.buffer,
        type: 'alternate',
      } as unknown as Terminal['buffer']['active'],
    });
    expect(search.reveal(hits[0].id)).toBe(false);
    expect(await search.collectAsync({ ...options, query: '^', regex: true })).toHaveLength(0);
  });
  it('uses each terminal’s host rather than its owning workspace host', async () => {
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

describe('bounded worker search', () => {
  it('terminates a pathological expression and accepts a subsequent search', async () => {
    const code = buildSync({
      entryPoints: ['src/lib/terminal-search.worker.ts'],
      bundle: true,
      write: false,
      format: 'iife',
    }).outputFiles[0].text;
    let terminated = 0;
    class BrowserWorker {
      onmessage: ((event: MessageEvent) => void) | null = null;
      onerror: (() => void) | null = null;
      private worker = new NodeWorker(
        `const {parentPort}=require('node:worker_threads');const self={postMessage: data=>parentPort.postMessage(data)};${code};parentPort.on('message',data=>self.onmessage({data}));`,
        { eval: true },
      );
      constructor() {
        this.worker.on('message', (data) => this.onmessage?.({ data } as MessageEvent));
        this.worker.on('error', () => this.onerror?.());
      }
      postMessage(value: unknown) {
        this.worker.postMessage(value);
      }
      terminate() {
        terminated++;
        void this.worker.terminate();
      }
    }
    const workers = new SearchWorkers(() => new BrowserWorker());
    try {
      const f = fixture(['a'.repeat(32) + '!']);
      const search = new LiveBufferSearch(f.term, workers);
      await expect(
        search.collectAsync({ ...options, query: '(a+)+$', regex: true }),
      ).rejects.toThrow('timed out');
      expect(terminated).toBe(1);
      const matches = await search.collectAsync({ ...options, query: '!' });
      expect(matches).toHaveLength(1);
      expect(search.reveal(matches[0].id)).toBe(true);
      expect(await search.collectAsync({ ...options, query: 'a!' })).toHaveLength(1);
      expect(terminated).toBe(1);
      const pending = search.collectAsync({ ...options, query: '(a+)+$', regex: true });
      search.release();
      await expect(pending).resolves.toEqual([]);
    } finally {
      workers.reset();
    }
  });
});
