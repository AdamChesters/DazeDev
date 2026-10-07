import type { ILink, Terminal } from '@xterm/xterm';
import { webUrl } from './terminals';

const maxCells = 65536;
// Screen snapshots can redraw wrapped rows without xterm's soft-wrap marker.
function urlContinues(terminal: Terminal, y: number): boolean {
  const previous = terminal.buffer.active.getLine(y - 1);
  const next = terminal.buffer.active.getLine(y);
  if (!previous || !next || next.isWrapped) return false;
  // xterm trims empty cells, but native redraws can contain printed spaces.
  const lastRow = previous.translateToString(true).trimEnd();
  const fullWidth = lastRow.length >= terminal.cols;
  const hyphenBreak = lastRow.endsWith('-') && terminal.cols - lastRow.length <= 16;
  if (!fullWidth && !hyphenBreak) return false;
  let before = lastRow;
  let first = y - 1;
  let prefix = before.match(/https?:\/\/[^\s<>"'`()]+$/i)?.[0];
  while (!prefix && first > 0) {
    if (/\s/.test(before.trimStart())) return false;
    const earlier = terminal.buffer.active.getLine(first - 1)!;
    const earlierText = earlier.translateToString(true).trimEnd();
    if (
      !terminal.buffer.active.getLine(first)?.isWrapped &&
      earlierText.length < terminal.cols &&
      !(earlierText.endsWith('-') && terminal.cols - earlierText.length <= 16)
    )
      return false;
    before = earlierText + before.trimStart();
    first--;
    if (before.length > maxCells) return false;
    prefix = before.match(/https?:\/\/[^\s<>"'`()]+$/i)?.[0];
  }
  const suffix = next
    .translateToString(true)
    .match(/^\s*([a-z0-9_%~.-][^\s<>"'`()]*)(?=[)\s]|$)/i)?.[1];
  if (!prefix || !suffix) return false;
  if (/^https?:\/\//i.test(suffix)) return false;
  try {
    const combined = new URL(prefix + suffix);
    // Away from a full-width boundary, retain the narrower hyphen recovery guards.
    if (!fullWidth && new URL(prefix).pathname === '/') {
      if (!/^[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:[:/]|$)/i.test(suffix)) return false;
    } else if (!fullWidth && !/[./?&#=%]/.test(suffix)) return false;
    const hostname = combined.hostname;
    return hostname.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label));
  } catch {
    return false;
  }
}

const urlPattern = /https?:\/\/[^\s"'!*(){}|\\^<>`]*[^\s"':,.!?{}|\\^~\[\]`()<>]/gi;

export function wrappedWebLinks(terminal: Terminal, line: number): ILink[] {
  const buffer = terminal.buffer.active;
  let first = line - 1;
  if (!buffer.getLine(first)) return [];
  let scanned = 0;
  while (first > 0 && (buffer.getLine(first)?.isWrapped || urlContinues(terminal, first))) {
    if (++scanned > maxCells / terminal.cols) return [];
    first--;
  }
  // If scrollback has evicted the start, no complete URL can be recovered.
  if (buffer.getLine(first)?.isWrapped) return [];
  let text = '';
  const positions: { start: { x: number; y: number }; end: { x: number; y: number } }[] = [];
  let cells = 0;
  for (let y = first; y < buffer.length; y++) {
    const row = buffer.getLine(y)!;
    const joined = urlContinues(terminal, y);
    if (y > first && !row.isWrapped && !joined) break;
    const joinsNext = urlContinues(terminal, y + 1);
    const rowText = row.translateToString(true);
    const left = joined ? rowText.length - rowText.trimStart().length : 0;
    let right = row.length;
    if (joinsNext) {
      while (right > left) {
        const last = row.getCell(right - 1)!;
        if (!last.getWidth() || (last.getChars() && last.getChars() !== ' ')) break;
        right--;
      }
    }
    for (let x = left; x < right; x++) {
      if (++cells > maxCells) return [];
      const cell = row.getCell(x)!;
      if (!cell.getWidth()) continue;
      const chars = cell.getChars();
      // A wide character can wrap early, leaving an unused last column.
      if (!chars && x === row.length - 1) {
        const next = buffer.getLine(y + 1);
        if (next?.isWrapped && next.getCell(0)?.getWidth() === 2) continue;
      }
      const value = chars || ' ';
      if (text.length + value.length > maxCells) return [];
      text += value;
      for (let i = 0; i < value.length; i++)
        positions.push({
          start: { x: x + 1, y: y + 1 },
          end: { x: x + cell.getWidth(), y: y + 1 },
        });
    }
  }
  return Array.from(text.matchAll(urlPattern)).flatMap((match) => {
    const start = positions[match.index!].start;
    const end = positions[match.index! + match[0].length - 1].end;
    if (start.y > line || end.y < line || !webUrl(match[0])) return [];
    return [{ text: match[0], range: { start, end }, activate: () => {} }];
  });
}

export function registerWebLinks(
  terminal: Terminal,
  activate: (event: MouseEvent, uri: string) => void,
  hover: (uri: string) => void,
) {
  return terminal.registerLinkProvider({
    provideLinks(line, callback) {
      callback(
        wrappedWebLinks(terminal, line).map((link) => ({
          ...link,
          activate,
          hover: () => hover(link.text),
          leave: () => hover(''),
        })),
      );
    },
  });
}
