export interface HostDocument {
  path: string;
  scope: string;
  source: string;
  readable: boolean;
  writable: boolean;
}
export interface DocumentText {
  path: string;
  text: string;
  revision: string;
  writable: boolean;
}
const escape = (text: string) =>
  text.replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  );
// Images: local data shows inline; a remote URL becomes a placeholder with an
// "Open in browser" button (data-open-url), never a network request.
function images(text: string) {
  return text.replace(/!\[([^\]]*)\]\(([^()\s]+)\)/g, (whole, alt: string, url: string) => {
    if (/^data:image\/(png|jpeg|gif|webp|bmp|avif);base64,[a-z0-9+/=]+$/i.test(url))
      return `<img src="${url}" alt="${alt}">`;
    if (/^https?:\/\//i.test(url))
      return `<span class="remote-image-ref">Remote image not loaded: <code>${url}</code> <button type="button" data-open-url="${url}">Open in browser</button></span>`;
    return whole;
  });
}
// A deliberately small, inert Markdown preview: no raw HTML, remote media or scripts.
export function markdownPreview(source: string): string {
  let code = false;
  const result: string[] = [];
  for (const line of source.split('\n')) {
    if (/^\s*```/.test(line)) {
      result.push(code ? '</code></pre>' : '<pre><code>');
      code = !code;
      continue;
    }
    if (code) {
      result.push(escape(line) + '\n');
      continue;
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    const text = images(escape(heading?.[2] ?? line))
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    result.push(
      heading
        ? `<h${heading[1].length}>${text}</h${heading[1].length}>`
        : `<p>${text || '<br>'}</p>`,
    );
  }
  if (code) result.push('</code></pre>');
  return result.join('');
}
export function shellPath(path: string, powershell = false) {
  if (powershell) return "'" + path.replace(/'/g, "''") + "'";
  return "'" + path.replace(/'/g, "'\\''") + "'";
}
export function encodeChunk(bytes: Uint8Array) {
  let text = '';
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text);
}
