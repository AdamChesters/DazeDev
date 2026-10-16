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
  expect(hostLabel('Workstation', { ...unknownStatus, nestedSsh: true })).toContain(
    'SSH destination unverified',
  );
  expect(hostLabel('Workstation', unknownStatus)).toBe('Connection: Workstation');
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
it('shows remote Markdown images as a link placeholder and local image data inline', () => {
  const remote = markdownPreview('![chart](https://example.com/chart.png?a=1&b=2)');
  expect(remote).not.toContain('<img');
  expect(remote).toContain('data-open-url="https://example.com/chart.png?a=1&amp;b=2"');
  expect(remote).toContain('Open in browser');
  const local = markdownPreview('![dot](data:image/png;base64,iVBORw0KGgo=)');
  expect(local).toContain('<img src="data:image/png;base64,iVBORw0KGgo=" alt="dot">');
  expect(markdownPreview('![x](data:text/html;base64,PHNjcmlwdD4=)')).not.toContain('<img');
  expect(markdownPreview('![x](javascript:alert(1))')).not.toContain('<img');
});
