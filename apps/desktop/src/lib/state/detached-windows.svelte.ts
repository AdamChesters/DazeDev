import { tick } from 'svelte';
import { SvelteMap } from 'svelte/reactivity';
import type { TerminalControls } from '../terminals';
import type { WindowTerminalState } from '../terminal-window';
import type { Pane, Workspace } from '../types';

export type PaneAction = 'repo' | 'tmux' | 'host' | 'model' | 'close';
export type WindowRequest = {
  paneId: string;
  requestId: string;
  action: string;
  args: Record<string, unknown>;
};

export interface DetachedWindowsDeps {
  desktop: boolean;
  call: (name: string, args: Record<string, unknown>) => Promise<unknown>;
  appWindow: () => {
    show(): Promise<void>;
    unminimize(): Promise<void>;
    setFocus(): Promise<void>;
  };
  terminal: (paneId: string) => TerminalControls | undefined;
  workspaces: () => Workspace[];
  windowState: (pane: Pane, workspace: Workspace) => WindowTerminalState;
  title: (pane: Pane, workspace: Workspace) => string;
  /** True while a dialog or dictation must finish first. */
  busy: () => boolean;
  selectWorkspace: (workspace: Workspace) => void;
  /** Makes the pane the focused one in its workspace and the active workspace. */
  focusPane: (workspace: Workspace, paneId: string) => void;
  search: {
    open: (paneId: string) => void;
    results: () => void;
    step: (direction: 1 | -1) => Promise<void> | undefined;
    close: () => void;
  };
  disablePasteConfirmation: () => void;
  reconnect: (hostId: string) => Promise<void>;
  paneAction: (action: PaneAction, pane: Pane) => Promise<void> | void;
  /** Called after a window opens, e.g. to close the pane menu. */
  onopen: () => void;
  onfail: (e: unknown) => void;
}

const paneActions: string[] = ['repo', 'tmux', 'host', 'model', 'close'];

// Terminals shown in their own windows, and the requests those windows send.
export class DetachedWindows {
  /** Pane id to owning workspace id. */
  panes = new SvelteMap<string, string>();
  /** Pane whose window has focus, or ''. */
  focus = $state('');
  #deps: DetachedWindowsDeps;

  constructor(deps: DetachedWindowsDeps) {
    this.#deps = deps;
  }

  #owner(paneId: string) {
    return this.#deps.workspaces().find((w) => w.panes.some((p) => p.id === paneId));
  }

  publish(paneId: string, value: unknown) {
    if (this.panes.has(paneId))
      void this.#deps.call('terminal_window_publish', { paneId, value }).catch(() => {});
  }

  /** Sends fresh state to every window and closes windows whose pane is gone. */
  sync() {
    for (const [id] of this.panes) {
      const owner = this.#owner(id);
      const pane = owner?.panes.find((p) => p.id === id);
      if (!owner || !pane) {
        this.panes.delete(id);
        void this.#deps.call('terminal_window_close', { paneId: id }).catch(this.#deps.onfail);
      } else this.publish(id, { kind: 'state', state: this.#deps.windowState(pane, owner) });
    }
  }

  async detach(pane: Pane, x?: number, y?: number, dragging = false) {
    const deps = this.#deps;
    if (!deps.desktop) throw new Error('Terminal windows are available in the desktop app.');
    if (deps.busy()) throw new Error('Finish the current action before moving this terminal.');
    const owner = this.#owner(pane.id);
    if (!owner || !deps.terminal(pane.id)) throw new Error('Wait for the terminal to be ready.');
    this.panes.set(pane.id, owner.id);
    try {
      await deps.call('terminal_window_open', {
        paneId: pane.id,
        workspaceId: owner.id,
        title: deps.title(pane, owner),
        x: x ?? null,
        y: y ?? null,
        dragging,
      });
      deps.onopen();
    } catch (error) {
      this.panes.delete(pane.id);
      throw error;
    }
  }

  async dock(id: string) {
    const owner = this.#owner(id);
    this.panes.delete(id);
    await this.#deps.call('terminal_window_close', { paneId: id });
    if (owner) {
      this.#deps.selectWorkspace(owner);
      owner.focused = id;
    }
    await tick();
    this.#deps.terminal(id)?.focus?.();
  }

  /** The window's focus changed. */
  focused(paneId: string, focused: boolean) {
    if (focused && this.panes.has(paneId)) this.focus = paneId;
    else if (this.focus === paneId) {
      this.focus = '';
      window.dispatchEvent(new Event('blur'));
    }
  }

  /** The window was closed from outside the app. */
  closed(paneId: string) {
    this.panes.delete(paneId);
    if (this.focus === paneId) this.focus = '';
  }

  async request(event: WindowRequest) {
    const deps = this.#deps;
    const { paneId, requestId, action, args } = event;
    const owner = this.#owner(paneId);
    const pane = owner?.panes.find((p) => p.id === paneId);
    const terminal = deps.terminal(paneId);
    const appWindow = deps.appWindow;
    try {
      if (!this.panes.has(paneId) || !owner || !pane || !terminal)
        throw new Error('Terminal window is no longer attached.');
      let result: unknown = null;
      if (action === 'snapshot')
        result = { state: deps.windowState(pane, owner), snapshot: await terminal.snapshot?.() };
      else if (action === 'search-open' || action === 'search-results') {
        if (action === 'search-results') deps.search.results();
        else deps.search.open(paneId);
        await appWindow().show();
        await appWindow().setFocus();
      } else if (action === 'search-step')
        await deps.search.step(Number(args.direction) < 0 ? -1 : 1);
      else if (action === 'search-close') deps.search.close();
      else if (action === 'input') {
        await terminal.input?.(String(args.data || ''));
        result = terminal.context();
      } else if (action === 'binary') await terminal.binary?.(String(args.data || ''));
      else if (action === 'resize') await terminal.resize?.(Number(args.cols), Number(args.rows));
      else if (action === 'paste') {
        // The terminal window already asked about multi-line text.
        await terminal.paste(
          String(args.text || ''),
          false,
          String(args.context || ''),
          'clipboard',
        );
        result = terminal.context();
      } else if (action === 'paste-confirm-off') {
        deps.disablePasteConfirmation();
      } else if (action === 'reconnect') {
        terminal.resume?.();
        await deps.reconnect(pane.hostId);
      } else if (action === 'recover') {
        if (!terminal.recover)
          throw new Error('Terminal recovery is unavailable. Return to the workspace and retry.');
        await terminal.recover();
      } else if (action === 'focus') {
        deps.focusPane(owner, paneId);
      } else if (action === 'dock') {
        await deps.call('terminal_window_publish', { paneId, value: { requestId, result: null } });
        await this.dock(paneId);
        return;
      } else if (action === 'key') {
        const options = {
          key: String(args.key || ''),
          code: String(args.code || ''),
          ctrlKey: !!args.ctrlKey,
          altKey: !!args.altKey,
          shiftKey: !!args.shiftKey,
          metaKey: !!args.metaKey,
          repeat: !!args.repeat,
        };
        window.dispatchEvent(
          new KeyboardEvent(args.type === 'keyup' ? 'keyup' : 'keydown', options),
        );
      } else if (action === 'pointer') {
        window.dispatchEvent(
          new PointerEvent(args.type === 'pointerup' ? 'pointerup' : 'pointerdown', {
            button: Number(args.button),
          }),
        );
      } else if (paneActions.includes(action)) {
        if (deps.busy())
          throw new Error('Finish the current action in the workspace window first.');
        deps.selectWorkspace(owner);
        owner.focused = paneId;
        await appWindow().show();
        await appWindow().unminimize();
        await appWindow().setFocus();
        await deps.paneAction(action as PaneAction, pane);
      } else throw new Error('Unsupported terminal window action.');
      await deps.call('terminal_window_publish', { paneId, value: { requestId, result } });
    } catch (error) {
      await deps
        .call('terminal_window_publish', {
          paneId,
          value: { requestId, error: String(error) },
        })
        .catch(() => {});
    }
  }
}
