export function previewKind(path: string): 'markdown' | 'html' | null {
  if (/\.(md|mdc|markdown)$/i.test(path) || /(^|[/\\])\.(cursor|cline)rules$/i.test(path))
    return 'markdown';
  return /\.html?$/i.test(path) ? 'html' : null;
}
