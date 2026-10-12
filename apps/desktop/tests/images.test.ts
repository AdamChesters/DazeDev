import { it, expect, vi } from 'vitest';
const { rpc } = vi.hoisted(() => ({ rpc: vi.fn() }));
vi.mock('../src/lib/bridge', () => ({ rpc }));
import { imageLinks, viewerLinks, imagePath, readHostImage } from '../src/lib/images';
it('recognises image destinations without mistaking web URLs for host file paths', () => {
  expect(imagePath('https://example.com/a.PNG?version=1')).toBe(true);
  expect(imagePath('script.svg')).toBe(false);
  const text = `![mockup](/tmp/my mockup.png) '/tmp/it's screenshot.jpg' C:\\Users\\User\\one.webp https://example.com/two.png`;
  const links = imageLinks(text);
  expect(links.map((x) => x.path)).toEqual([
    '/tmp/my mockup.png',
    "/tmp/it's screenshot.jpg",
    'C:\\Users\\User\\one.webp',
  ]);
  for (const link of links) expect(text.slice(link.start, link.end)).toBe(link.path);
});
it('assembles bounded image chunks from the selected host with a stable revision', async () => {
  rpc.mockReset();
  const bytes = Uint8Array.from({ length: 49160 }, (_, i) => i % 256);
  const base = (offset: number) => ({
    path: '/tmp/image.png',
    mime: 'image/png',
    size: bytes.length,
    revision: 'same',
    offset,
    data: btoa(String.fromCharCode(...bytes.slice(offset, offset + 49152))),
  });
  rpc.mockResolvedValueOnce(base(0)).mockResolvedValueOnce(base(49152));
  const image = await readHostImage('devbox', '/tmp/image.png');
  expect(Uint8Array.from(atob(image.src.split(',')[1]), (c) => c.charCodeAt(0))).toEqual(bytes);
  expect(rpc.mock.calls[1]).toEqual([
    'devbox',
    'image_read',
    { path: '/tmp/image.png', offset: 49152, revision: 'same' },
  ]);
});
it('rejects oversized, incomplete and changed image transfers', async () => {
  const chunk = {
    path: '/tmp/image.png',
    mime: 'image/png',
    size: 49160,
    revision: 'same',
    offset: 0,
    data: btoa('x'.repeat(49152)),
  };
  rpc.mockReset();
  rpc.mockResolvedValueOnce({ ...chunk, size: 17 * 1024 * 1024 });
  await expect(readHostImage('h', '/tmp/image.png')).rejects.toThrow('invalid image');
  rpc.mockReset();
  rpc.mockResolvedValueOnce({ ...chunk, data: 'eA==' });
  await expect(readHostImage('h', '/tmp/image.png')).rejects.toThrow('Incomplete');
  rpc.mockReset();
  rpc
    .mockResolvedValueOnce(chunk)
    .mockResolvedValueOnce({ ...chunk, offset: 49152, revision: 'changed' });
  await expect(readHostImage('h', '/tmp/image.png')).rejects.toThrow('invalid image');
});

it('opens wrapped terminal image paths in the viewer with ordinary or modified clicks', async () => {
  const { registerImageLinks } = await import('../src/lib/images');
  const lines = ['  /tmp/my-image-', 'mockup.png      '];
  let provider: any;
  const terminal = {
    cols: 40,
    hasSelection: vi.fn(() => false),
    registerLinkProvider: (value: any) => {
      provider = value;
      return { dispose() {} };
    },
    buffer: {
      active: {
        length: lines.length,
        getLine: (index: number) =>
          lines[index] === undefined
            ? undefined
            : {
                isWrapped: index === 1,
                length: lines[index].length,
                translateToString: () => lines[index],
                getCell: (x: number) => ({
                  getWidth: () => 1,
                  getChars: () => (lines[index][x] === ' ' ? '' : lines[index][x]),
                }),
              },
      },
    },
  };
  const open = vi.fn();
  registerImageLinks(terminal as any, open);
  let links: any[] = [];
  provider.provideLinks(2, (value: any[]) => (links = value));
  expect(links[0].text).toBe('/tmp/my-image-mockup.png');
  expect(links[0].range).toEqual({ start: { x: 3, y: 1 }, end: { x: 10, y: 2 } });
  links[0].activate({ ctrlKey: false, metaKey: false });
  expect(open).toHaveBeenCalledWith('/tmp/my-image-mockup.png');
  open.mockClear();
  links[0].activate({ ctrlKey: true });
  expect(open).toHaveBeenCalledWith('/tmp/my-image-mockup.png');
  open.mockClear();
  terminal.hasSelection.mockReturnValue(true);
  links[0].activate({ ctrlKey: false, metaKey: false });
  expect(open).not.toHaveBeenCalled();
});

it('recognises supported HTML, Markdown, source and image files with accurate ranges', () => {
  const text =
    '[report](/tmp/my report.html) `/home/user/README.md` "C:\\Users\\User\\demo app.ts" ~/logs/run.log:42 /tmp/config.json /tmp/diagram.svg https://example.com/page.html /tmp/no.htmlx';
  const links = viewerLinks(text);
  expect(links.map((link) => link.path)).toEqual([
    '/tmp/my report.html',
    '/home/user/README.md',
    'C:\\Users\\User\\demo app.ts',
    '~/logs/run.log',
    '/tmp/config.json',
    '/tmp/diagram.svg',
  ]);
  for (const link of links) expect(text.slice(link.start, link.end)).toBe(link.path);
});

it('recognises relative preview links without matching URLs or prose inside filenames', () => {
  const text =
    'Desktop preview (dev/example/screenshots/support-20261006/preview-desktop.png) · Mobile preview (dev/example/screenshots/support-20261006/preview-mobile.png) [page](./preview/index.html) "../my report.md" `screens\\mobile.webp` README.md https://example.com/a.png https://example.com/readme.md ./bad.pngx';
  const links = viewerLinks(text);
  expect(links.map((link) => link.path)).toEqual([
    'dev/example/screenshots/support-20261006/preview-desktop.png',
    'dev/example/screenshots/support-20261006/preview-mobile.png',
    './preview/index.html',
    '../my report.md',
    'screens\\mobile.webp',
    'README.md',
  ]);
  for (const link of links) expect(text.slice(link.start, link.end)).toBe(link.path);
});

it('pins subsequent image chunks to the canonical file on the originating host', async () => {
  rpc.mockReset();
  rpc.mockResolvedValueOnce({
    path: '/home/test/dev/preview.png',
    mime: 'image/png',
    revision: 'same',
    size: 49153,
    offset: 0,
    data: btoa('x'.repeat(49152)),
  });
  rpc.mockResolvedValueOnce({
    path: '/home/test/dev/preview.png',
    mime: 'image/png',
    revision: 'same',
    size: 49153,
    offset: 49152,
    data: btoa('y'),
  });
  await readHostImage('original-host', 'dev/preview.png', '/workspace');
  expect(rpc.mock.calls[0]).toEqual([
    'original-host',
    'image_read',
    { path: 'dev/preview.png', directory: '/workspace', offset: 0 },
  ]);
  expect(rpc.mock.calls[1]).toEqual([
    'original-host',
    'image_read',
    {
      path: '/home/test/dev/preview.png',
      directory: '/workspace',
      offset: 49152,
      revision: 'same',
    },
  ]);
});

it('keeps quoted and Markdown relative filenames with spaces, excluding web destinations', () => {
  const links = viewerLinks(
    '[preview](my report.html) "my screenshot.png" [website](https://example.com/my report.html)',
  );
  expect(links.map((link) => link.path)).toEqual(['my report.html', 'my screenshot.png']);
});

it('routes relative HTML links from a wrapped row only on modified activation', async () => {
  const { registerViewerLinks } = await import('../src/lib/images');
  let provider: any;
  const rows = ['  dev/preview/', 'index.html   '];
  const terminal = {
    cols: 40,
    hasSelection: () => false,
    registerLinkProvider(value: any) {
      provider = value;
      return { dispose() {} };
    },
    buffer: {
      active: {
        length: rows.length,
        getLine(y: number) {
          return rows[y] === undefined
            ? undefined
            : {
                isWrapped: y === 1,
                length: rows[y].length,
                translateToString: () => rows[y],
                getCell(x: number) {
                  return { getWidth: () => 1, getChars: () => rows[y][x] };
                },
              };
        },
      },
    },
  };
  const open = vi.fn();
  registerViewerLinks(terminal as any, open);
  let links: any[] = [];
  provider.provideLinks(2, (value: any[]) => (links = value));
  expect(links[0].text).toBe('dev/preview/index.html');
  expect(links[0].range).toEqual({ start: { x: 3, y: 1 }, end: { x: 10, y: 2 } });
  links[0].activate({ ctrlKey: false, metaKey: false });
  expect(open).not.toHaveBeenCalled();
  links[0].activate({ ctrlKey: true });
  expect(open).toHaveBeenCalledWith('dev/preview/index.html');
});

it('does not steal a relative-looking filename from a hard-wrapped web URL', async () => {
  const { registerViewerLinks } = await import('../src/lib/images');
  const rows = [
    { text: 'The social image (https://server.examplex', wrapped: false },
    { text: '-tests.invalid:8443/assets/brain-        ', wrapped: true },
    { text: 'social-card.jpg) now uses the blended', wrapped: false },
  ];
  let provider: any;
  const terminal = {
    cols: 41,
    registerLinkProvider(value: any) {
      provider = value;
      return { dispose() {} };
    },
    buffer: {
      active: {
        length: rows.length,
        getLine(y: number) {
          return (
            rows[y] && {
              length: 41,
              isWrapped: rows[y].wrapped,
              translateToString: () => rows[y].text,
              getCell(x: number) {
                return { getWidth: () => 1, getChars: () => rows[y].text[x] || '' };
              },
            }
          );
        },
      },
    },
  };
  registerViewerLinks(terminal as any, vi.fn());
  let links: any[] = [];
  provider.provideLinks(3, (value: any[]) => (links = value));
  expect(links).toEqual([]);
});
