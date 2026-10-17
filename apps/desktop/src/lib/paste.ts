// A line break in pasted text may run a command at once, so multi-line
// clipboard text is confirmed first unless the user turned that off.
export function needsPasteConfirmation(text: string, confirm: boolean | undefined) {
  return confirm !== false && /[\r\n]/.test(text);
}
export function pastePreview(text: string, shown = 3) {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  if (lines.length > 1 && lines.at(-1) === '') lines.pop();
  return {
    count: lines.length,
    lines: lines
      .slice(0, shown)
      .map((line) => line.replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '·'))
      .map((line) => (line.length > 160 ? line.slice(0, 160) + '…' : line)),
    more: Math.max(0, lines.length - shown),
  };
}

/** Pastes up to this many characters are shown inline in the agent's prompt. */
export const INLINE_PASTE_LIMIT = 2000;
/**
 * Codex replaces one paste of more than 1,000 characters with a
 * "[Pasted Content N chars]" placeholder (LARGE_PASTE_CHAR_THRESHOLD in
 * codex-rs/tui chat_composer.rs). It counts characters after turning CRLF
 * into LF, and handles each bracketed paste on its own.
 */
export const CODEX_PASTE_LIMIT = 1000;

/** Characters as an agent counts them: code points, with CRLF as one. */
export function pasteLength(text: string) {
  let count = 0;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (code === 13 && text.charCodeAt(i + 1) === 10) i++;
    else if (code >= 0xd800 && code <= 0xdbff && /[\udc00-\udfff]/.test(text[i + 1] || '')) i++;
    count++;
  }
  return count;
}

/** Text longer than the inline limit opens the editable preview first. */
export function needsPastePreview(text: string, confirm: boolean | undefined) {
  return confirm !== false && pasteLength(text) > INLINE_PASTE_LIMIT;
}

/**
 * Split text into pieces of at most `limit` characters that join back to
 * exactly `text`. A piece ends after a line break where possible, then after
 * a space, and never between CR and LF or inside a surrogate pair.
 */
export function splitPaste(text: string, limit = CODEX_PASTE_LIMIT): string[] {
  const pieces: string[] = [];
  let start = 0;
  while (start < text.length) {
    let end = start;
    let count = 0;
    let lineBreak = -1;
    let space = -1;
    while (end < text.length && count < limit) {
      const code = text.charCodeAt(end);
      let step = 1;
      if (code === 13 && text.charCodeAt(end + 1) === 10) step = 2;
      else if (code >= 0xd800 && code <= 0xdbff && /[\udc00-\udfff]/.test(text[end + 1] || ''))
        step = 2;
      end += step;
      count++;
      if (code === 10 || code === 13) lineBreak = end;
      else if (code === 32 || code === 9) space = end;
    }
    if (end < text.length) end = lineBreak > start ? lineBreak : space > start ? space : end;
    pieces.push(text.slice(start, end));
    start = end;
  }
  return pieces;
}

/**
 * How clipboard text is handed to the terminal. Codex collapses one large
 * bracketed paste into a placeholder that Ctrl+G cannot expand without an
 * editor. Text within the inline limit is sent to Codex as several
 * bracketed pastes under its threshold, so its prompt shows the text itself.
 * Other programs, and terminals without bracketed paste, get one paste.
 */
export function pastePieces(text: string, agent: string | null | undefined, bracketed: boolean) {
  const length = pasteLength(text);
  if (agent !== 'codex' || !bracketed || length <= CODEX_PASTE_LIMIT || length > INLINE_PASTE_LIMIT)
    return [text];
  return splitPaste(text);
}
