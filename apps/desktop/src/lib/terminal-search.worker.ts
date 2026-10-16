import { findLineMatches, type SearchOptions } from './terminal-search';

self.onmessage = (
  event: MessageEvent<{ lines: string[]; options: SearchOptions; limit: number }>,
) => {
  try {
    const { lines, options, limit } = event.data;
    self.postMessage({ matches: findLineMatches(lines, options, limit) });
  } catch {
    self.postMessage({
      error: 'Invalid regular expression. Fix the pattern or turn Regular expression off.',
    });
  }
};
