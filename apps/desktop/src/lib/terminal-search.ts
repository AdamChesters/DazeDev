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

// Markers live only for the current search and follow normal-buffer scrollback trims.
export class LiveBufferSearch {
  private matches = new Map<
    string,
    {
      buffer: IBuffer;
      marker?: IMarker;
      first: number;
      text: string;
      start: number;
      end: number;
    }
  >();
  private revision = 0;
  private cancelWorker?: () => void;
  constructor(private terminal: Terminal) {}
  async collectAsync(options: SearchOptions, limit = 1000): Promise<BufferMatch[]> {
    this.release();
    const revision = this.revision;
    if (!options.query) return [];
    searchPattern(options);
    const buffer = this.terminal.buffer.active;
    const lines: { text: string; first: number }[] = [];
    for (let row = 0; row < buffer.length; row++) {
      const line = logicalLine(buffer, row);
      lines.push({text: line.text, first: line.first});
      row = line.last;
      if (lines.length % 500 === 0) {
        await new Promise(resolve => setTimeout(resolve, 0));
        if (revision !== this.revision) return [];
      }
    }
    const worker = new Worker(new URL('./terminal-search.worker.ts', import.meta.url), {type: 'module'});
    const found = await new Promise<{line: number; start: number; end: number}[]>((resolve, reject) => {
      const finish = (error?: Error, result: {line: number; start: number; end: number}[] = []) => {
        clearTimeout(timer); worker.terminate(); this.cancelWorker = undefined;
        if (error) reject(error); else resolve(result);
      };
      const timer = setTimeout(() => finish(new Error('Search timed out. Simplify the pattern or turn Regular expression off.')), 500);
      this.cancelWorker = () => finish(undefined, []);
      worker.onerror = () => finish(new Error('Search failed. Try a simpler pattern.'));
      worker.onmessage = event => event.data.error ? finish(new Error(event.data.error)) : finish(undefined, event.data.matches);
      worker.postMessage({lines: lines.map(line => line.text), options, limit});
    });
    if (revision !== this.revision || buffer !== this.terminal.buffer.active) return [];
    return found.map(hit => {
      const line = lines[hit.line];
      const id = crypto.randomUUID();
      const marker = buffer.type === 'normal' ? this.terminal.registerMarker(line.first - buffer.baseY - buffer.cursorY) : undefined;
      this.matches.set(id, {buffer, marker, first: line.first, text: line.text, start: hit.start, end: hit.end});
      const from = Math.max(0, hit.start - 60);
      return {id, from: hit.start - from, to: hit.end - from, text: line.text.slice(from, hit.end + 120)};
    });
  }
  release() {
    this.revision++;
    this.cancelWorker?.();
    for (const match of this.matches.values()) match.marker?.dispose();
    this.matches.clear();
  }
  collect(options: SearchOptions, limit = 1000): BufferMatch[] {
    this.release();
    const pattern = searchPattern(options);
    if (!pattern) return [];
    const buffer = this.terminal.buffer.active;
    const found: BufferMatch[] = [];
    for (let row = 0; row < buffer.length && found.length < limit; row++) {
      const line = logicalLine(buffer, row);
      row = line.last;
      pattern.lastIndex = 0;
      for (const match of line.text.matchAll(pattern)) {
        if (!match[0].length) continue;
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
          start: match.index!,
          end: match.index! + match[0].length,
        });
        found.push({
          id,
          from: match.index! - Math.max(0, match.index! - 60),
          to: match.index! - Math.max(0, match.index! - 60) + match[0].length,
          text: line.text.slice(
            Math.max(0, match.index! - 60),
            match.index! + match[0].length + 120,
          ),
        });
        if (found.length >= limit) break;
      }
    }
    return found;
  }
  reveal(id: string): boolean {
    const match = this.matches.get(id);
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
