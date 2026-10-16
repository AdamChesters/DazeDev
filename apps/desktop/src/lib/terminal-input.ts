import { copySelectedTerminalText } from './terminal-copy';
import { selectionMouseDown } from './terminal-setup';
import { terminalInputChangesContext } from './terminals';

// Key and pointer handling shared by the workspace terminal and the detached
// terminal window.

export interface KeyHandlerOptions {
  selection: () => string;
  copy: (text: string) => Promise<void>;
  report: (message: string) => void;
  /** Ctrl+V / Cmd+V: read the clipboard and paste. */
  paste: () => void;
  /** Other Ctrl/Cmd shortcuts; true when handled, so xterm ignores the key. */
  shortcut?: (event: KeyboardEvent) => boolean;
}

/** The xterm custom key handler: copy with a selection, paste, then app shortcuts. */
export function terminalKeyHandler(options: KeyHandlerOptions) {
  return (event: KeyboardEvent): boolean => {
    if (copySelectedTerminalText(event, options.selection(), options.copy, options.report))
      return false;
    if (event.type !== 'keydown' || !(event.ctrlKey || event.metaKey)) return true;
    if (!event.altKey && event.code === 'KeyV') {
      event.preventDefault();
      options.paste();
      return false;
    }
    if (options.shortcut?.(event)) {
      event.preventDefault();
      return false;
    }
    return true;
  };
}

export interface LinkPressOptions {
  root: HTMLElement;
  mac: boolean;
  modified: (event: MouseEvent) => boolean;
  /** Whether to keep this press away from xterm and the remote application. */
  intercept: (modified: boolean) => boolean;
  /** The link to open on release, or null when the press only selects. */
  record: (modified: boolean) => string | null;
  /** Whether the pointer is still on the pressed link at release. */
  accept: (url: string) => boolean;
  open: (event: MouseEvent, url: string) => void;
  focus: () => void;
}

/**
 * Click handling for links in a terminal. A plain press becomes xterm's
 * selection override, so a link click never reaches a remote TUI; the link
 * opens on release when the pointer has not moved. Returns the cleanup.
 */
export function handleLinkPresses(options: LinkPressOptions): () => void {
  const { root } = options;
  let press: { url: string; x: number; y: number; modified: boolean } | null = null;
  const synthetic = new WeakSet<Event>();
  const down = (event: MouseEvent) => {
    if (synthetic.has(event) || event.button !== 0) return;
    const modified = options.modified(event);
    if (!options.intercept(modified)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const url = options.record(modified);
    if (url !== null) press = { url, x: event.clientX, y: event.clientY, modified };
    if (modified) options.focus();
    else {
      const selection = selectionMouseDown(event, options.mac);
      synthetic.add(selection);
      event.target?.dispatchEvent(selection);
    }
  };
  const up = (event: MouseEvent) => {
    if (!press || event.button !== 0) return;
    const pressed = press;
    press = null;
    if (
      root.contains(event.target as Node) &&
      options.modified(event) === pressed.modified &&
      Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) < 5 &&
      options.accept(pressed.url)
    ) {
      event.preventDefault();
      event.stopImmediatePropagation();
      options.open(event, pressed.url);
    }
  };
  const cancel = () => {
    press = null;
  };
  root.addEventListener('mousedown', down, true);
  window.addEventListener('mouseup', up, true);
  window.addEventListener('blur', cancel);
  return () => {
    root.removeEventListener('mousedown', down, true);
    window.removeEventListener('mouseup', up, true);
    window.removeEventListener('blur', cancel);
  };
}

export interface WindowInputRoutes {
  /** terminal_window_input: straight to the PTY from the terminal window. */
  direct: (data: string) => Promise<unknown>;
  /** terminal_window_binary: base64 bytes straight to the PTY. */
  binary: (data: string) => Promise<unknown>;
  /** The workspace window's 'input' request. */
  relay: (data: string) => Promise<unknown>;
}

/**
 * Input that changes the dictation context goes through the workspace window,
 * which tracks the context epoch; other input goes straight to the PTY.
 */
export const relayedInput = (data: string) => terminalInputChangesContext(data);

/**
 * Input from a detached terminal window. Direct and relayed input share one
 * queue, so keys arrive in the order typed. Input that fails is reported and
 * never resent, and input is dropped until the view is ready again.
 */
export class WindowInputQueue {
  private chain: Promise<void> = Promise.resolve();
  constructor(
    private routes: WindowInputRoutes,
    private ready: () => boolean,
    private failed: (error: unknown) => void,
  ) {}
  input(data: string): Promise<void> {
    return this.enqueue(() =>
      relayedInput(data) ? this.routes.relay(data) : this.routes.direct(data),
    );
  }
  /** `data` is xterm's binary string; it is sent base64-encoded. */
  binary(data: string): Promise<void> {
    return this.enqueue(() => this.routes.binary(btoa(data)));
  }
  private enqueue(send: () => Promise<unknown>): Promise<void> {
    this.chain = this.chain
      .then(async () => {
        if (!this.ready()) throw new Error('Terminal input is paused. Reconnect before typing.');
        await send();
      })
      .catch((error) => this.failed(error));
    return this.chain;
  }
}
