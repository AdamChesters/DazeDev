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
    const text = escape(heading?.[2] ?? line)
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
