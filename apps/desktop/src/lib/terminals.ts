import type { BufferMatch, SearchOptions } from './terminal-search';
import { ConnectionOperations } from './connection-health';
export interface TerminalControls {
  focus?(): void;
  input?(data: string): Promise<void>;
  binary?(data: string): Promise<void>;
  resize?(cols: number, rows: number): Promise<void>;
  snapshot?(): Promise<{
    sequence: number;
    generation: number;
    data: string;
    cols: number;
    rows: number;
    context: string;
    ready: boolean;
  }>;
  disconnect?(): Promise<void>;
  resume?(): void;
  recover?(): Promise<void>;
  pasteClipboard?(): Promise<void>;
  paste(
    text: string,
    enter?: boolean,
    expectedContext?: string,
    source?: PasteSource,
  ): Promise<void>;
  pasteBroadcast?(text: string, expectedContext: string): Promise<void>;
  pasteDictation(text: string, enter: boolean): Promise<void>;
  pasteForReview(text: string, expectedContext: string): Promise<void>;
  submitReviewed?(expectedContext: string): Promise<void>;
  context(): string;
  processId(): number;
  copy(): Promise<void>;
  selectAll(): void;
  search(): void;
  findMatches?(options: SearchOptions, limit?: number): Promise<BufferMatch[]>;
  revealMatch?(id: string): boolean;
  releaseSearch?(): void;
  searchSelection?():
    { start: { x: number; y: number }; end: { x: number; y: number } } | undefined;
  exportOutput(): void;
  clear(): void;
  text(): string;
  selection?(): string;
}
// Components own these instances until unmount; layout changes keep them mounted.
export const terminals = new Map<string, TerminalControls>();
// Open and close for one pane id run in order across component mounts, so a
// late close from an unmounted view cannot end the PTY its replacement opened.
export const paneLifecycle = new ConnectionOperations();
// clipboard: user clipboard text; normal paste rules (multi-line asks first).
// command: app-generated text; must be a single line without control characters.
export type PasteSource = 'clipboard' | 'command';
export function checkCommandText(text: string) {
  if (/[\x00-\x1f\x7f]/.test(text))
    throw new Error('This text contains line breaks or control characters and was not pasted.');
}
// Focus reports are terminal protocol traffic, including when opening a dialog.
// They do not change the input being composed in the remote application.
export function terminalInputChangesContext(data: string): boolean {
  return data !== '\x1b[I' && data !== '\x1b[O';
}
export function reviewPasteText(text: string, bracketed: boolean): string {
  if (/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(text))
    throw new Error(
      'The prompt contains terminal control characters. Edit the prompt template first.',
    );
  // Without bracketed paste, line breaks may execute a shell command. Use one
  // line for review; retain the original layout when the receiving app supports it.
  return (bracketed ? text : text.replace(/[\r\n\t]+/g, ' ')).trim();
}
export interface TerminalSettings {
  screenReader?: boolean;
  fontSize: number;
  scrollback: number;
  gpu: boolean;
  ligatures: boolean;
  images: boolean;
  // off: block remote copy; notify: copy and show a notice; write: copy silently.
  clipboard: 'off' | 'notify' | 'write' | 'read-write';
  confirmPaste?: boolean;
}
export function remoteClipboardWrite(policy: TerminalSettings['clipboard'] | undefined) {
  return policy === 'off' ? 'block' : policy === 'write' || policy === 'read-write' ? 'allow' : 'notify';
}
export const terminalDefaults: TerminalSettings = {
  fontSize: 13,
  scrollback: 10000,
  gpu: true,
  ligatures: false,
  images: true,
  clipboard: 'notify',
  confirmPaste: true,
};
export function webUrl(value: string): string | null {
  try {
    const u = new URL(value);
    return ['http:', 'https:'].includes(u.protocol) ? u.href : null;
  } catch {
    return null;
  }
}

let opening = 0;
const waiting: (() => void)[] = [];
export async function terminalSlot(): Promise<() => void> {
  if (opening >= 4) await new Promise<void>((resolve) => waiting.push(resolve));
  else opening++;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    const next = waiting.shift();
    if (next) next();
    else opening--;
  };
}

// Foreground/busy detection is advisory and must never gate dictation delivery.
export async function deliverDictation(
  terminal: Pick<TerminalControls, 'pasteDictation'>,
  text: string,
  automatic: boolean,
): Promise<void> {
  await terminal.pasteDictation(text, automatic);
}
