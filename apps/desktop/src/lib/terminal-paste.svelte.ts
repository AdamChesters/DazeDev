import { readClipboardContent } from './clipboard-upload';
import { needsPasteConfirmation } from './paste';

// Paste handling shared by the workspace terminal and the detached terminal
// window. Each view supplies how files and text reach its terminal.

/** Multi-line text asks first; the view shows PasteConfirm while `request` is set. */
export class PasteConfirmation {
  request = $state<{ text: string; resolve: (accepted: boolean) => void } | null>(null);
  constructor(
    private enabled: () => boolean | undefined,
    private dontAsk: () => void,
    private done: () => void,
  ) {}
  confirm(text: string): Promise<boolean> {
    if (!needsPasteConfirmation(text, this.enabled())) return Promise.resolve(true);
    this.request?.resolve(false);
    return new Promise((resolve) => (this.request = { text, resolve }));
  }
  answer(accepted: boolean, dontAsk = false) {
    const pending = this.request;
    this.request = null;
    if (accepted && dontAsk) this.dontAsk();
    pending?.resolve(accepted);
    this.done();
  }
  /** The view is closing: a waiting paste is declined. */
  cancel() {
    this.request?.resolve(false);
  }
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
 * The DOM paste event. Single-line text is left to xterm; files and text that
 * need confirmation take the app's paste path instead.
 */
export function pasteEventHandler(
  confirm: () => boolean | undefined,
  targets: { files: (files: File[]) => Promise<void>; text: (text: string) => Promise<void> },
  fail: (error: unknown) => void,
) {
  return (event: ClipboardEvent) => {
    const files = Array.from(event.clipboardData?.files || []);
    const text = event.clipboardData?.getData('text/plain') || '';
    if (!files.length && !needsPasteConfirmation(text, confirm())) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (files.length) void targets.files(files).catch(fail);
    else void targets.text(text).catch(fail);
  };
}
