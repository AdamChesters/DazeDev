import type { IBuffer, IMarker, Terminal } from '@xterm/xterm';

export type SearchScope = 'terminal' | 'workspace' | 'host' | 'global';
export interface SearchOptions {
  query: string;
  matchCase: boolean;
  regex: boolean;
}
export interface BufferMatch {
  id: string;
  text: string;
  from: number;
  to: number;
}
export interface SearchTarget {
  paneId: string;
  hostId: string;
  host: string;
  workspaceId: string;
  workspace: string;
  terminal: string;
  session: string;
}
export interface SearchHit extends SearchTarget, BufferMatch {}
export interface SearchNavigation {
  query: string;
  position: string;
  destination: string;
}
export function searchPattern(options: SearchOptions): RegExp | null {
  if (!options.query) return null;
  const escaped = options.query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(options.regex ? options.query : escaped, options.matchCase ? 'g' : 'gi');
}
export function findLineMatches(lines: string[], options: SearchOptions, limit: number) {
  const pattern = searchPattern(options);
  const matches: { line: number; start: number; end: number }[] = [];
  if (pattern)
    for (let line = 0; line < lines.length && matches.length < limit; line++) {
      pattern.lastIndex = 0;
      for (const match of lines[line].matchAll(pattern)) {
        if (!match[0].length) continue;
        matches.push({ line, start: match.index!, end: match.index! + match[0].length });
        if (matches.length >= limit) break;
      }
    }
  return matches;
}
export function scopeTargets(targets: SearchTarget[], scope: SearchScope, origin: SearchTarget) {
  return targets.filter(
    (target) =>
      scope === 'global' ||
      (scope === 'host' && target.hostId === origin.hostId) ||
      (scope === 'workspace' && target.workspaceId === origin.workspaceId) ||
      (scope === 'terminal' && target.paneId === origin.paneId),
  );
}

interface Point {
  row: number;
  col: number;
}
interface LogicalLine {
  text: string;
  points: Point[];
  first: number;
  last: number;
}
function logicalLine(buffer: IBuffer, first: number, withPoints = false): LogicalLine {
  let text = '';
  const points: Point[] = [];
  let row = first;
  for (;;) {
    const line = buffer.getLine(row);
    if (!line) break;
    const wrapped = !!buffer.getLine(row + 1)?.isWrapped;
    if (!withPoints) {
      let rowText = line.translateToString(!wrapped);
      if (wrapped && rowText.endsWith(' ') && !line.getCell(line.length - 1)?.getChars() && buffer.getLine(row + 1)?.getCell(0)?.getWidth() === 2) rowText = rowText.slice(0, -1);
      text += rowText;
      if (!wrapped) break;
      row++;
      continue;
    }
    const end = wrapped ? line.length : line.translateToString(true).length;
    let translated = 0;
    for (let col = 0; col < line.length; col++) {
      const cell = line.getCell(col);
      if (!cell || cell.getWidth() === 0) continue;
      if (
        wrapped &&
        col === line.length - 1 &&
        !cell.getChars() &&
        buffer
          .getLine(row + 1)
          ?.getCell(0)
          ?.getWidth() === 2
      )
        continue;
      const chars = cell.getChars() || ' ';
      if (!wrapped && translated >= end) break;
      if (withPoints) for (let index = 0; index < chars.length; index++) points.push({ row, col });
      text += chars;
      translated += chars.length;
    }
    if (!wrapped) break;
    row++;
  }
  return { text, points, first, last: row };
}

type WorkerHit = { line: number; start: number; end: number };
export interface SearchWorkerLike {
  postMessage(message: unknown): void;
  terminate(): void;
  onmessage: ((event: MessageEvent) => void) | null;
  onerror: ((event: unknown) => void) | null;
}
// One worker serves every terminal; searches run one at a time. A search that
// runs too long (for example a catastrophic regular expression) ends the
// worker, and the next search starts a fresh one.
export class SearchWorkers {
  private worker?: SearchWorkerLike;
  private chain: Promise<unknown> = Promise.resolve();
  constructor(
    private create: () => SearchWorkerLike,
    private timeout = 500,
  ) {}
  run(
    lines: string[],
    options: SearchOptions,
    limit: number,
    cancelled: () => boolean = () => false,
  ): Promise<WorkerHit[]> {
    const job = this.chain
      .catch(() => {})
      .then(() => (cancelled() ? [] : this.post(lines, options, limit)));
    this.chain = job;
    return job;
  }
  private post(lines: string[], options: SearchOptions, limit: number) {
    return new Promise<WorkerHit[]>((resolve, reject) => {
      const worker = (this.worker ||= this.create());
      const timer = setTimeout(() => {
        this.reset();
        reject(new Error('Search timed out. Simplify the pattern or turn Regular expression off.'));
      }, this.timeout);
      worker.onmessage = (event) => {
        clearTimeout(timer);
        if (event.data.error) reject(new Error(event.data.error));
        else resolve(event.data.matches);
      };
      worker.onerror = () => {
        clearTimeout(timer);
        this.reset();
        reject(new Error('Search failed. Try a simpler pattern.'));
      };
      worker.postMessage({ lines, options, limit });
    });
  }
  reset() {
    this.worker?.terminate();
    this.worker = undefined;
  }
}
export const searchWorkers = new SearchWorkers(
  () =>
    new Worker(new URL('./terminal-search.worker.ts', import.meta.url), {
      type: 'module',
    }) as unknown as SearchWorkerLike,
);

interface StoredMatch {
  buffer: IBuffer;
  marker?: IMarker;
  first: number;
  text: string;
  start: number;
  end: number;
}
// Markers follow normal-buffer scrollback trims. A refresh keeps the previous
// results usable until the next refresh, so the list can stay on screen while
// new results are collected.
export class LiveBufferSearch {
  private matches = new Map<string, StoredMatch>();
  private previous = new Map<string, StoredMatch>();
  private revision = 0;
  constructor(
    private terminal: Terminal,
    private workers: SearchWorkers = searchWorkers,
  ) {}
  async collectAsync(options: SearchOptions, limit = 1000): Promise<BufferMatch[]> {
    const revision = ++this.revision;
    if (!options.query) {
      this.release();
      return [];
    }
    searchPattern(options);
    const buffer = this.terminal.buffer.active;
    const lines: { text: string; first: number }[] = [];
    for (let row = 0; row < buffer.length; row++) {
      const line = logicalLine(buffer, row);
      lines.push({ text: line.text, first: line.first });
      row = line.last;
      if (lines.length % 500 === 0) {
        await new Promise((resolve) => setTimeout(resolve, 0));
        if (revision !== this.revision) return [];
      }
    }
    const found = await this.workers.run(
      lines.map((line) => line.text),
      options,
      limit,
      () => revision !== this.revision,
    );
    if (revision !== this.revision || buffer !== this.terminal.buffer.active) return [];
    for (const match of this.previous.values()) match.marker?.dispose();
    this.previous = this.matches;
    this.matches = new Map();
    return found.map((hit) => {
      const line = lines[hit.line];
      const id = crypto.randomUUID();
      const marker =
        buffer.type === 'normal'
          ? this.terminal.registerMarker(line.first - buffer.baseY - buffer.cursorY)
          : undefined;
      this.matches.set(id, {
        buffer,
        marker,
        first: line.first,
        text: line.text,
        start: hit.start,
        end: hit.end,
      });
      const from = Math.max(0, hit.start - 60);
      return {
        id,
        from: hit.start - from,
        to: hit.end - from,
        text: line.text.slice(from, hit.end + 120),
      };
    });
  }
  release() {
    this.revision++;
    for (const match of [...this.matches.values(), ...this.previous.values()])
      match.marker?.dispose();
    this.matches.clear();
    this.previous.clear();
  }
  reveal(id: string): boolean {
    const match = this.matches.get(id) || this.previous.get(id);
    if (!match || match.buffer !== this.terminal.buffer.active || match.marker?.isDisposed)
      return false;
    const row = match.marker?.line ?? match.first;
    const line = logicalLine(match.buffer, row, true);
    if (line.text !== match.text) return false;
    const start = line.points[match.start],
      last = line.points[match.end - 1];
    const end = last && {
      row: last.row,
      col: last.col + (match.buffer.getLine(last.row)?.getCell(last.col)?.getWidth() || 1),
    };
    if (!start || !end) return false;
    this.terminal.scrollToLine(start.row);
    this.terminal.select(
      start.col,
      start.row,
      (end.row - start.row) * this.terminal.cols + end.col - start.col,
    );
    return true;
  }
  selection() {
    return this.terminal.getSelectionPosition();
  }
}

export const searchUpdates = new EventTarget();
export function notifySearchUpdate(paneId: string, invalidated = false) {
  searchUpdates.dispatchEvent(new CustomEvent('change', { detail: { paneId, invalidated } }));
}
