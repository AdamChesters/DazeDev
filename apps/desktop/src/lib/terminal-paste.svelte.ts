import { readClipboardContent } from './clipboard-upload';
import { CODEX_PASTE_LIMIT, needsPasteConfirmation, needsPastePreview, pasteLength } from './paste';

// Paste handling shared by the workspace terminal and the detached terminal
// window. Each view supplies how files and text reach its terminal.

/**
 * Multi-line text asks first and text over the inline limit opens the
 * editable preview; the view shows PasteConfirm or the preview pane while
 * `request` is set. The answer is the text to paste (the preview may have
 * edited it) or null when the user declined.
 */
export class PasteConfirmation {
  request = $state<{
    text: string;
    preview: boolean;
    resolve: (text: string | null) => void;
  } | null>(null);
  constructor(
    private enabled: () => boolean | undefined,
    private dontAsk: () => void,
    private done: () => void,
  ) {}
  confirm(text: string): Promise<string | null> {
    const preview = needsPastePreview(text, this.enabled());
    if (!preview && !needsPasteConfirmation(text, this.enabled())) return Promise.resolve(text);
    this.request?.resolve(null);
    return new Promise((resolve) => (this.request = { text, preview, resolve }));
  }
  answer(text: string | null, dontAsk = false) {
    const pending = this.request;
    this.request = null;
    if (text !== null && dontAsk) this.dontAsk();
    pending?.resolve(text);
    this.done();
  }
  /** The view is closing: a waiting paste is declined. */
  cancel() {
    this.request?.resolve(null);
  }
}

/**
 * Whether the app's paste path takes this text rather than xterm: line
 * breaks and long text may need asking, and text that Codex would collapse
 * may be split (see pastePieces).
 */
export function appHandlesPaste(text: string, confirm: boolean | undefined) {
  return (
    needsPasteConfirmation(text, confirm) ||
    needsPastePreview(text, confirm) ||
    pasteLength(text) > CODEX_PASTE_LIMIT
  );
}

export interface PasteTargets {
  files: (files: File[]) => Promise<void>;
  text: (text: string, destination: string) => Promise<void>;
}

/**
 * Ctrl+V / Paste menu: read the clipboard, then paste files or text if the
 * terminal is still the one that was focused when reading began.
 */
export async function pasteFromClipboard(
  destination: () => string,
  targets: PasteTargets,
  clipboard: Clipboard = navigator.clipboard,
) {
  const expected = destination();
  const content = await readClipboardContent(clipboard);
  if (!expected || destination() !== expected)
    throw new Error('Terminal changed while reading the clipboard. Paste again.');
  if (content.files.length) await targets.files(content.files);
  else await targets.text(content.text, expected);
}

/**
 * The DOM paste event. Short single-line text is left to xterm; files and
 * text the app handles (see appHandlesPaste) take the app's paste path.
 */
export function pasteEventHandler(
  confirm: () => boolean | undefined,
  targets: { files: (files: File[]) => Promise<void>; text: (text: string) => Promise<void> },
  fail: (error: unknown) => void,
) {
  return (event: ClipboardEvent) => {
    const files = Array.from(event.clipboardData?.files || []);
    const text = event.clipboardData?.getData('text/plain') || '';
    if (!files.length && !appHandlesPaste(text, confirm())) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (files.length) void targets.files(files).catch(fail);
    else void targets.text(text).catch(fail);
  };
}
