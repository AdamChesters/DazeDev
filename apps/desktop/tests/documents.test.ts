import { expect, it } from 'vitest';
import { markdownPreview, shellPath, encodeChunk } from '../src/lib/documents';
import { hostLabel, unknownStatus } from '../src/lib/pane-status';
it('renders Markdown without executing HTML or loading remote content', () => {
  const html = markdownPreview(
    '# Title\n<script>alert(1)</script>\n![image](https://example.com/tracker)\n```html\n<img src=x onerror=alert(1)>',
  );
  expect(html).toContain('<h1>Title</h1>');
  expect(html).not.toContain('<script>');
  expect(html).not.toContain('<img');
  expect(html).toContain('&lt;script&gt;');
  expect(html.endsWith('</code></pre>')).toBe(true);
});
it('quotes uploaded paths literally and preserves arbitrary file bytes', () => {
  expect(shellPath("/tmp/it's $(danger).png")).toBe("'/tmp/it'\\''s $(danger).png'");
  const data = Uint8Array.from({ length: 256 }, (_, i) => i);
  expect(Uint8Array.from(atob(encodeChunk(data)), (c) => c.charCodeAt(0))).toEqual(data);
});
it('labels the original connection honestly when the nested destination is unknown', () => {
  expect(hostLabel('Devbox', { ...unknownStatus, nestedSsh: true })).toContain(
    'SSH destination unverified',
  );
  expect(hostLabel('Devbox', unknownStatus)).toBe('Connection: Devbox');
});

import { previewKind } from '../src/lib/source-viewer';
it('offers rendered previews only for matching file types', () => {
  expect(previewKind('/project/index.HTML')).toBe('html');
  expect(previewKind('C:\\project\\.cursorrules')).toBe('markdown');
  expect(previewKind('/project/README.md')).toBe('markdown');
  expect(previewKind('/project/theme.css')).toBeNull();
  expect(previewKind('/project/index.html.js')).toBeNull();
});
it('quotes Windows paths for PowerShell without POSIX escapes or interpolation', () => {
  expect(shellPath("C:\\User's folder\\$image.png", true)).toBe("'C:\\User''s folder\\$image.png'");
});
