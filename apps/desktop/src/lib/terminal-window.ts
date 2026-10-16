import type { Host, Pane } from './types';
import type { TerminalSettings } from './terminals';
import type { PaneStatus } from './pane-status';
import { VoiceCaptureKeys } from './voice-capture-keys';
import { matches, mouseMatches } from './shortcut';

export interface TerminalSnapshot {
  sequence: number;
  generation: number;
  data: string;
  cols: number;
  rows: number;
  context: string;
  ready: boolean;
}
export interface WindowTerminalState {
  pane: Pane;
  host?: Host;
  workspaceName: string;
  workspaceColors?: import('./colors').TabColors;
  theme: string;
  settings: TerminalSettings;
  inputShortcut: import('./shortcut').Shortcut;
  commandShortcut: import('./shortcut').Shortcut;
  searchShortcut?: import('./shortcut').Shortcut;
  voiceStatus: string;
  voiceMode: string;
  voiceTarget: string;
  dictation: string;
  status: string;
  ready: boolean;
  metadata?: PaneStatus;
}
/** Output for a terminal window, numbered by the desktop app across reconnects. */
export interface TerminalOutput {
  sequence: number;
  data: Uint8Array;
}

export class TerminalStream {
  sequence = 0;
  private initialized = false;
  private pending = new Map<number, TerminalOutput>();
  get hasGap() {
    return this.initialized && this.pending.size > 0 && !this.pending.has(this.sequence + 1);
  }
  receive(output: TerminalOutput): TerminalOutput[] {
    if (output.sequence <= this.sequence) return [];
    this.pending.set(output.sequence, output);
    return this.drain();
  }
  restore(snapshot: TerminalSnapshot): TerminalOutput[] {
    this.sequence = snapshot.sequence;
    this.initialized = true;
    for (const sequence of this.pending.keys())
      if (sequence <= this.sequence) this.pending.delete(sequence);
    return this.drain();
  }
  private drain(): TerminalOutput[] {
    const result: TerminalOutput[] = [];
    if (!this.initialized) return result;
    while (this.pending.has(this.sequence + 1)) {
      result.push(this.pending.get(++this.sequence)!);
      this.pending.delete(this.sequence);
    }
    return result;
  }
}

type Call = (name: string, args: Record<string, unknown>) => Promise<unknown>;
export interface WindowReply {
  requestId: string;
  result?: unknown;
  error?: string;
}

/**
 * Requests from a terminal window to the workspace window. The workspace
 * window answers each one with a 'terminal-window-message' carrying its id.
 */
export class WindowRequests {
  private pending = new Map<
    string,
    {
      resolve: (value: unknown) => void;
      reject: (reason: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  private closed = false;
  constructor(
    private call: Call,
    private timeout = 20000,
  ) {}
  send<T>(action: string, args: Record<string, unknown> = {}): Promise<T> {
    if (this.closed) return Promise.reject(new Error('Terminal window is closed.'));
    const requestId = crypto.randomUUID();
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(
          new Error(
            action === 'input' || action === 'paste'
              ? 'Delivery uncertain. Input was not retried.'
              : 'The workspace window did not respond.',
          ),
        );
      }, this.timeout);
      this.pending.set(requestId, { resolve: (value) => resolve(value as T), reject, timer });
      void this.call('terminal_window_request', { requestId, action, args }).catch((error) => {
        clearTimeout(timer);
        this.pending.delete(requestId);
        reject(new Error(String(error)));
      });
    });
  }
  answer(message: WindowReply) {
    const job = this.pending.get(message.requestId);
    if (!job) return;
    clearTimeout(job.timer);
    this.pending.delete(message.requestId);
    if (message.error) job.reject(new Error(message.error));
    else job.resolve(message.result);
  }
  close() {
    this.closed = true;
    for (const job of this.pending.values()) {
      clearTimeout(job.timer);
      job.reject(new Error('Terminal window was closed.'));
    }
    this.pending.clear();
  }
}

/**
 * Voice and command shortcuts pressed in a terminal window act in the
 * workspace window, which owns voice capture. Returns the cleanup.
 */
export function forwardShortcuts(
  state: () => WindowTerminalState | null,
  request: (action: string, args: Record<string, unknown>) => Promise<unknown>,
  fail: (error: unknown) => void,
): () => void {
  const captureKeys = new VoiceCaptureKeys();
  const bindings = (view: WindowTerminalState) => [view.inputShortcut, view.commandShortcut];
  const key = (event: KeyboardEvent) => {
    const view = state();
    if (!view) return;
    const control =
      event.type === 'keyup'
        ? captureKeys.up(event.key)
        : captureKeys.down(event, view.voiceStatus, document.hasFocus()).consume;
    if (!control && !bindings(view).some((binding) => matches(event, binding))) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    void request('key', {
      type: event.type,
      key: event.key,
      code: event.code,
      ctrlKey: event.ctrlKey,
      altKey: event.altKey,
      shiftKey: event.shiftKey,
      metaKey: event.metaKey,
      repeat: event.repeat,
    }).catch(fail);
  };
  const pointer = (event: PointerEvent) => {
    const view = state();
    if (!view || !bindings(view).some((binding) => mouseMatches(event, binding))) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    void request('pointer', { type: event.type, button: event.button }).catch(fail);
  };
  const mouse = (event: MouseEvent) => {
    const view = state();
    if (view && bindings(view).some((binding) => mouseMatches(event, binding)))
      event.preventDefault();
  };
  const listeners = [
    ['keydown', key],
    ['keyup', key],
    ['auxclick', mouse],
    ['pointerdown', pointer],
    ['pointerup', pointer],
  ] as const;
  for (const [name, listener] of listeners)
    window.addEventListener(name, listener as EventListener, true);
  return () => {
    for (const [name, listener] of listeners)
      window.removeEventListener(name, listener as EventListener, true);
  };
}
