import type { ITerminalAddon, ITerminalOptions, ITheme, Terminal } from '@xterm/xterm';
import { remoteClipboardWrite, type TerminalSettings } from './terminals';

// Shared by the workspace terminal and the detached terminal window, so both
// render the same palette, font, limits and add-ons.
export const terminalFont = '"Cascadia Code", "SFMono-Regular", Consolas, monospace';
export const maxScrollback = 100000;
const themeKeys = [
  'background',
  'foreground',
  'cursor',
  'selectionBackground',
  'black',
  'red',
  'green',
  'yellow',
  'blue',
  'magenta',
  'cyan',
  'white',
  'brightBlack',
  'brightRed',
  'brightGreen',
  'brightYellow',
  'brightBlue',
  'brightMagenta',
  'brightCyan',
  'brightWhite',
] as const;
const themeProperty = (key: string) =>
  key === 'selectionBackground'
    ? '--term-selection'
    : `--term-${key.replace(/[A-Z]/g, (letter) => '-' + letter.toLowerCase())}`;
/** The xterm theme from the --term-* custom properties; unset ones keep xterm's default. */
export function themeFromStyle(read: (property: string) => string): ITheme {
  const theme: Record<string, string> = {};
  for (const key of themeKeys) {
    const value = read(themeProperty(key)).trim();
    if (value) theme[key] = value;
  }
  return theme as ITheme;
}
/** The current theme's terminal colours, defined per theme in app.css. */
export function terminalTheme(root?: Element): ITheme {
  if (typeof document === 'undefined') return {};
  const style = getComputedStyle(root || document.documentElement);
  return themeFromStyle((property) => style.getPropertyValue(property));
}
export const clampFontSize = (value: number) =>
  Math.max(8, Math.min(32, Number.isFinite(value) ? value : 13));
export const clampScrollback = (value: number) =>
  Math.max(100, Math.min(maxScrollback, Number.isFinite(value) ? Math.round(value) : 10000));

export function terminalOptions(
  settings: Pick<TerminalSettings, 'fontSize' | 'scrollback' | 'screenReader'>,
  linkHandler: ITerminalOptions['linkHandler'],
): ITerminalOptions {
  return {
    fontFamily: terminalFont,
    fontSize: clampFontSize(settings.fontSize),
    lineHeight: 1.2,
    scrollback: clampScrollback(settings.scrollback),
    screenReaderMode: !!settings.screenReader,
    convertEol: false,
    cursorBlink: true,
    macOptionClickForcesSelection: true,
    allowProposedApi: true,
    linkHandler,
    theme: terminalTheme(),
  };
}
/** `scrollbackLimit` is the terminal's share of the window's memory budget. */
export function applyTerminalSettings(
  term: Pick<Terminal, 'options'>,
  settings: Pick<TerminalSettings, 'fontSize' | 'scrollback' | 'screenReader'>,
  scrollbackLimit = Infinity,
) {
  const fontSize = clampFontSize(settings.fontSize);
  const scrollback = Math.min(clampScrollback(settings.scrollback), scrollbackLimit);
  const screenReader = !!settings.screenReader;
  if (term.options.fontSize !== fontSize) term.options.fontSize = fontSize;
  if (term.options.scrollback !== scrollback) term.options.scrollback = scrollback;
  if (term.options.screenReaderMode !== screenReader) term.options.screenReaderMode = screenReader;
}

type Addon = ITerminalAddon;
interface GpuAddon extends Addon {
  onContextLoss(listener: () => void): unknown;
}
interface ImageCache extends Addon {
  storageLimit: number;
}
export interface AddonFactories {
  gpu: () => GpuAddon;
  /** `limitMb` is the image cache size in MB. */
  images: (limitMb: number) => ImageCache;
  ligatures: () => Addon;
}
// Loads optional add-ons on demand. WebGL stays loaded while the terminal is
// hidden; it is only dropped on context loss, when turned off, or on dispose.
export class OptionalAddons {
  private gpu?: GpuAddon;
  private images?: ImageCache;
  private ligatures?: Addon;
  private imageLimitMb = 32;
  constructor(
    private load: (addon: Addon) => void,
    private create: AddonFactories,
  ) {}
  apply(settings: Pick<TerminalSettings, 'gpu' | 'images' | 'ligatures'>, visible = true) {
    if (settings.gpu && visible && !this.gpu) {
      let addon: GpuAddon | undefined;
      try {
        addon = this.create.gpu();
        addon.onContextLoss(() => {
          if (this.gpu === addon) this.gpu = undefined;
          addon?.dispose();
        });
        this.load(addon);
        this.gpu = addon;
      } catch {
        // The DOM renderer remains available.
        addon?.dispose();
      }
    } else if (!settings.gpu && this.gpu) {
      this.gpu.dispose();
      this.gpu = undefined;
    }
    if (settings.images && !this.images) {
      this.images = this.create.images(this.imageLimitMb);
      this.load(this.images);
    } else if (!settings.images && this.images) {
      this.images.dispose();
      this.images = undefined;
    }
    if (settings.ligatures && !this.ligatures) {
      this.ligatures = this.create.ligatures();
      this.load(this.ligatures);
    } else if (!settings.ligatures && this.ligatures) {
      this.ligatures.dispose();
      this.ligatures = undefined;
    }
  }
  /** The image cache's share of the window's memory budget. */
  set imageLimit(limitMb: number) {
    this.imageLimitMb = limitMb;
    if (this.images && this.images.storageLimit !== limitMb) this.images.storageLimit = limitMb;
  }
  get hasGpu() {
    return !!this.gpu;
  }
  dispose() {
    for (const addon of [this.gpu, this.images, this.ligatures]) addon?.dispose();
    this.gpu = this.images = this.ligatures = undefined;
  }
}

// Fit runs every frame while dragging; the PTY only needs the final size.
export class ResizeSender {
  private timer: ReturnType<typeof setTimeout> | undefined;
  private sent = '';
  private wanted: { cols: number; rows: number } | undefined;
  constructor(
    private send: (cols: number, rows: number) => Promise<unknown>,
    private delay = 100,
  ) {}
  request(cols: number, rows: number, immediate = false) {
    this.wanted = { cols, rows };
    clearTimeout(this.timer);
    this.timer = undefined;
    if (immediate) this.flush();
    else this.timer = setTimeout(() => this.flush(), this.delay);
  }
  private flush() {
    this.timer = undefined;
    const size = this.wanted;
    if (!size) return;
    const key = `${size.cols}:${size.rows}`;
    if (key === this.sent) return;
    this.sent = key;
    void this.send(size.cols, size.rows).catch(() => {
      if (this.sent === key) this.sent = '';
    });
  }
  // Forget the last sent size, e.g. after a new PTY opens.
  reset() {
    this.sent = '';
  }
  // Record a size that was applied elsewhere.
  mark(cols: number, rows: number) {
    this.sent = `${cols}:${rows}`;
  }
  dispose() {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.wanted = undefined;
  }
}

// xterm's selection override: Shift+drag (Option on macOS) selects text even
// when a TUI tracks the mouse, so a link click never reaches the remote app.
export function selectionMouseDown(event: MouseEvent, mac: boolean): MouseEvent {
  return new MouseEvent('mousedown', {
    bubbles: true,
    cancelable: true,
    view: window,
    clientX: event.clientX,
    clientY: event.clientY,
    button: event.button,
    buttons: event.buttons,
    detail: event.detail,
    shiftKey: !mac,
    altKey: mac,
  });
}
export const macPlatform = () =>
  typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/i.test(navigator.platform);

const clipboardLimit = 1048576;
// OSC 52 handlers for the clipboard add-on. Remote writes follow the policy:
// off blocks, notify copies and reports it, write/read-write copy silently.
export function remoteClipboard(
  policy: () => TerminalSettings['clipboard'] | undefined,
  notify: (characters: number) => void,
  clipboard: Pick<Clipboard, 'readText' | 'writeText'> = navigator.clipboard,
) {
  return {
    readText: async () =>
      policy() === 'read-write'
        ? clipboard.readText().then((text) => (text.length <= clipboardLimit ? text : ''))
        : '',
    writeText: async (_selection: unknown, text: string) => {
      const action = remoteClipboardWrite(policy());
      if (action === 'block' || text.length > clipboardLimit) return;
      await clipboard.writeText(text);
      if (action === 'notify') notify(text.length);
    },
  };
}
