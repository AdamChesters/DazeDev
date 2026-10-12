import { searchPattern, type SearchOptions } from './terminal-search';

self.onmessage = (event: MessageEvent<{lines: string[]; options: SearchOptions; limit: number}>) => {
  try {
    const {lines, options, limit} = event.data;
    const pattern = searchPattern(options);
    const matches: {line: number; start: number; end: number}[] = [];
    if (pattern) for (let line = 0; line < lines.length && matches.length < limit; line++) {
      pattern.lastIndex = 0;
      for (const match of lines[line].matchAll(pattern)) {
        if (!match[0].length) continue;
        matches.push({line, start: match.index!, end: match.index! + match[0].length});
        if (matches.length >= limit) break;
      }
    }
    self.postMessage({matches});
  } catch {
    self.postMessage({error: 'Invalid regular expression. Fix the pattern or turn Regular expression off.'});
  }
};
