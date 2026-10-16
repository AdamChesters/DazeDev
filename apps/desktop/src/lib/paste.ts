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
