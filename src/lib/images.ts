import { rpc } from './bridge';
import { wrappedWebLinks } from './web-links';
export const imagePath = (value: string) =>
  /\.(png|jpe?g|gif|webp|bmp|ico|avif)(?:[?#].*)?$/i.test(value);
export interface ViewedImage {
  path: string;
  src: string;
}
interface ImageChunk {
  path: string;
  mime: string;
  revision: string;
  size: number;
  offset: number;
  data: string;
}
export async function readHostImage(
  hostId: string,
  path: string,
  directory = '',
): Promise<ViewedImage> {
  const parts: string[] = [];
  let offset = 0,
    revision = '',
    total = 0,
    mime = '',
    canonical = path;
  do {
    const chunk = await rpc<ImageChunk>(hostId, 'image_read', {
      path: canonical,
      ...(directory ? { directory } : {}),
      offset,
      ...(revision ? { revision } : {}),
    });
    if (
      !Number.isSafeInteger(chunk.size) ||
      chunk.size <= 0 ||
      chunk.size > 16 * 1024 * 1024 ||
      chunk.offset !== offset ||
      !/^image\/(png|jpeg|gif|webp|bmp|x-icon|avif)$/.test(chunk.mime) ||
      typeof chunk.data !== 'string' ||
      chunk.data.length > 65536 ||
      typeof chunk.revision !== 'string' ||
      !chunk.revision ||
      (revision && (revision !== chunk.revision || total !== chunk.size || mime !== chunk.mime))
    )
      throw Error('The host returned invalid image data.');
    const size = atob(chunk.data).length;
    if (!size || size > 49152 || size !== Math.min(49152, chunk.size - offset))
      throw Error('Incomplete image data.');
    parts.push(chunk.data);
    offset += size;
    revision = chunk.revision;
    total = chunk.size;
    mime = chunk.mime;
    canonical = chunk.path;
  } while (offset < total);
  return { path: canonical, src: `data:${mime};base64,${parts.join('')}` };
}
const viewerExtensions =
  'png|jpe?g|gif|webp|bmp|ico|avif|html?|md|mdc|markdown|txt|log|json|jsonc|ya?ml|toml|ini|cfg|conf|csv|tsv|xml|svg|css|scss|less|[cm]?[jt]sx?|py|rs|sh|bash|ps1|psm1|psd1|bat|cmd|sql|c|h|cpp|hpp|java|go|rb|php|svelte|vue';
export function viewerLinks(text: string): { path: string; start: number; end: number }[] {
  const links: { path: string; start: number; end: number }[] = [];
  // Markdown destinations retain spaces; plain paths use quotes to delimit spaces.
  const suffix = `(?:\\.(?:${viewerExtensions})|[/\\\\](?:Dockerfile|Makefile|LICENSE|README|\\.cursorrules|\\.clinerules))`;
  const root = '(?:[A-Za-z]:[\\\\/]|/|~/|(?:[\\w.-]+[\\\\/])|(?=[\\w.-]+\\.))';
  const enclosedRoot = `(?:${root}|[\\w.-])`;
  const pattern = new RegExp(
    `\\]\\((${enclosedRoot}[^\\r\\n]*?${suffix})\\)|["'\\x60](${enclosedRoot}[^\\r\\n]*?${suffix})["'\\x60]|(${root}[^\\s<>"\`]*?${suffix})(?=$|[\\s)\\],:;\\x60])`,
    'gi',
  );
  for (const match of text.matchAll(pattern)) {
    const path = match[1] || match[2] || match[3];
    if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(path) && !/^[A-Za-z]:[\\/]/.test(path)) continue;
    const start = match.index! + match[0].indexOf(path);
    if (start > 0 && /[\w:/]/.test(text[start - 1])) continue;
    links.push({ path, start, end: start + path.length });
  }
  return links;
}

export const imageLinks = (text: string) =>
  viewerLinks(text).filter((link) => imagePath(link.path));
export const registerImageLinks = (
  terminal: import('@xterm/xterm').Terminal,
  open: (path: string) => void,
) => registerViewerLinks(terminal, open, { findLinks: imageLinks });
export function registerViewerLinks(
  terminal: import('@xterm/xterm').Terminal,
  open: (path: string) => void,
  {
    findLinks = viewerLinks,
    hover,
  }: { findLinks?: typeof viewerLinks; hover?: (path: string) => void } = {},
) {
  return terminal.registerLinkProvider({
    provideLinks(line, callback) {
      const buffer = terminal.buffer.active;
      if (!buffer.getLine(line - 1)) return callback(undefined);
      let first = line - 1;
      while (first > 0 && line - first < 32 && buffer.getLine(first)?.isWrapped) first--;
      let text = '';
      const positions: { start: { x: number; y: number }; end: { x: number; y: number } }[] = [];
      for (let y = first; y < first + 32; y++) {
        const row = buffer.getLine(y);
        if (!row || (y > first && !row.isWrapped)) break;
        for (let x = 0; x < row.length; x++) {
          const cell = row.getCell(x)!;
          if (!cell.getWidth()) continue;
          const chars = cell.getChars() || ' ';
          text += chars;
          for (let i = 0; i < chars.length; i++)
            positions.push({
              start: { x: x + 1, y: y + 1 },
              end: { x: x + cell.getWidth(), y: y + 1 },
            });
        }
      }
      const webRanges = wrappedWebLinks(terminal, line).map((link) => link.range);
      const before = (a: { x: number; y: number }, b: { x: number; y: number }) =>
        a.y < b.y || (a.y === b.y && a.x < b.x);
      callback(
        findLinks(text)
          .filter(
            (link) =>
              !webRanges.some(
                (range) =>
                  !before(positions[link.end - 1].end, range.start) &&
                  !before(range.end, positions[link.start].start),
              ),
          )
          .filter(
            (link) =>
              positions[link.start].start.y <= line && positions[link.end - 1].end.y >= line,
          )
          .map((link) => ({
            text: link.path,
            range: { start: positions[link.start].start, end: positions[link.end - 1].end },
            hover: () => hover?.(link.path),
            leave: () => hover?.(''),
            activate: (event: MouseEvent) => {
              if (
                event.ctrlKey ||
                event.metaKey ||
                (imagePath(link.path) && !terminal.hasSelection())
              )
                open(link.path);
            },
          })),
      );
    },
  });
}
