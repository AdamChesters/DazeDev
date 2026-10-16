import { describe, expect, it, vi, type Mock } from 'vitest';
import {
  DetachedWindows,
  type DetachedWindowsDeps,
} from '../src/lib/state/detached-windows.svelte';
import type { TerminalControls } from '../src/lib/terminals';
import type { WindowTerminalState } from '../src/lib/terminal-window';
import type { Pane, Workspace } from '../src/lib/types';

const pane = (id: string): Pane => ({
  id,
  hostId: 'host',
  sessionId: 's',
  name: id,
  repo: '',
  identity: '',
  agent: 'shell',
  model: '',
  effort: '',
  operation: 'op',
});

function setup(overrides: Partial<DetachedWindowsDeps> = {}) {
  const workspace: Workspace = {
    id: 'w',
    hostId: 'host',
    name: 'Work',
    panes: [pane('a'), pane('b')],
    layout: { kind: 'pane', id: 'a' },
    focused: 'b',
  };
  const terminal = {
    context: vi.fn(() => 'ctx'),
    input: vi.fn(async () => undefined),
    paste: vi.fn(async () => undefined),
    resume: vi.fn(),
    focus: vi.fn(),
    snapshot: vi.fn(async () => 'snap'),
  } as unknown as TerminalControls;
  const appWindow = {
    show: vi.fn(async () => undefined),
    unminimize: vi.fn(async () => undefined),
    setFocus: vi.fn(async () => undefined),
  };
  const deps = {
    desktop: true,
    call: vi.fn(async (_name: string, _args: Record<string, unknown>) => undefined as unknown),
    appWindow: () => appWindow,
    terminal: (id: string) => (id === 'a' ? terminal : undefined),
    workspaces: () => [workspace],
    windowState: vi.fn(() => ({ status: 'ok' }) as unknown as WindowTerminalState),
    title: (p: Pane, w: Workspace) => `${w.name} / ${p.name}`,
    busy: vi.fn(() => false),
    selectWorkspace: vi.fn(),
    focusPane: vi.fn(),
    search: { open: vi.fn(), results: vi.fn(), step: vi.fn(async () => undefined), close: vi.fn() },
    disablePasteConfirmation: vi.fn(),
    reconnect: vi.fn(async () => undefined),
    paneAction: vi.fn(),
    onopen: vi.fn(),
    onfail: vi.fn(),
    ...overrides,
  } satisfies DetachedWindowsDeps;
  return { windows: new DetachedWindows(deps), deps, workspace, terminal, appWindow };
}

const replies = (call: DetachedWindowsDeps['call']) =>
  (call as Mock<DetachedWindowsDeps['call']>).mock.calls
    .filter(([name]) => name === 'terminal_window_publish')
    .map(([, args]) => args);

describe('DetachedWindows', () => {
  it('opens a window for a ready terminal', async () => {
    const { windows, deps } = setup();
    await windows.detach(pane('a'), 10, 20);
    expect(windows.panes.get('a')).toBe('w');
    expect(deps.call).toHaveBeenCalledWith('terminal_window_open', {
      paneId: 'a',
      workspaceId: 'w',
      title: 'Work / a',
      x: 10,
      y: 20,
      dragging: false,
    });
    expect(deps.onopen).toHaveBeenCalled();
  });

  it('refuses to detach while busy and forgets a window that failed to open', async () => {
    const busy = setup({ busy: () => true });
    await expect(busy.windows.detach(pane('a'))).rejects.toThrow('Finish the current action');
    const failing = setup({ call: vi.fn().mockRejectedValue(new Error('no window')) });
    await expect(failing.windows.detach(pane('a'))).rejects.toThrow('no window');
    expect(failing.windows.panes.has('a')).toBe(false);
  });

  it('answers input requests with the terminal context', async () => {
    const { windows, deps, terminal } = setup();
    windows.panes.set('a', 'w');
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'input', args: { data: 'ls' } });
    expect(terminal.input).toHaveBeenCalledWith('ls');
    expect(replies(deps.call)).toEqual([
      { paneId: 'a', value: { requestId: 'r1', result: 'ctx' } },
    ]);
  });

  it('routes snapshot, search and paste requests', async () => {
    const { windows, deps, terminal, appWindow } = setup();
    windows.panes.set('a', 'w');
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'snapshot', args: {} });
    expect(replies(deps.call)[0]).toEqual({
      paneId: 'a',
      value: { requestId: 'r1', result: { state: { status: 'ok' }, snapshot: 'snap' } },
    });
    await windows.request({ paneId: 'a', requestId: 'r2', action: 'search-open', args: {} });
    expect(deps.search.open).toHaveBeenCalledWith('a');
    expect(appWindow.setFocus).toHaveBeenCalled();
    await windows.request({
      paneId: 'a',
      requestId: 'r3',
      action: 'search-step',
      args: { direction: -1 },
    });
    expect(deps.search.step).toHaveBeenCalledWith(-1);
    await windows.request({
      paneId: 'a',
      requestId: 'r4',
      action: 'paste',
      args: { text: 'x', context: 'ctx' },
    });
    expect(terminal.paste).toHaveBeenCalledWith('x', false, 'ctx', 'clipboard');
  });

  it('reports an error for a pane that is not detached', async () => {
    const { windows, deps } = setup();
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'input', args: {} });
    expect(replies(deps.call)).toEqual([
      {
        paneId: 'a',
        value: { requestId: 'r1', error: 'Error: Terminal window is no longer attached.' },
      },
    ]);
  });

  it('rejects unknown actions', async () => {
    const { windows, deps } = setup();
    windows.panes.set('a', 'w');
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'explode', args: {} });
    expect(replies(deps.call)[0]).toEqual({
      paneId: 'a',
      value: { requestId: 'r1', error: 'Error: Unsupported terminal window action.' },
    });
  });

  it('runs pane actions in the workspace window unless busy', async () => {
    const { windows, deps, workspace } = setup();
    windows.panes.set('a', 'w');
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'tmux', args: {} });
    expect(deps.selectWorkspace).toHaveBeenCalledWith(workspace);
    expect(workspace.focused).toBe('a');
    expect(deps.paneAction).toHaveBeenCalledWith('tmux', workspace.panes[0]);
    (deps.busy as Mock<() => boolean>).mockReturnValue(true);
    await windows.request({ paneId: 'a', requestId: 'r2', action: 'close', args: {} });
    expect(deps.paneAction).toHaveBeenCalledTimes(1);
    expect(replies(deps.call).at(-1)).toEqual({
      paneId: 'a',
      value: {
        requestId: 'r2',
        error: 'Error: Finish the current action in the workspace window first.',
      },
    });
  });

  it('reconnects the pane host and focuses through the app', async () => {
    const { windows, deps, terminal, workspace } = setup();
    windows.panes.set('a', 'w');
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'reconnect', args: {} });
    expect(terminal.resume).toHaveBeenCalled();
    expect(deps.reconnect).toHaveBeenCalledWith('host');
    await windows.request({ paneId: 'a', requestId: 'r2', action: 'focus', args: {} });
    expect(deps.focusPane).toHaveBeenCalledWith(workspace, 'a');
  });

  it('docks a window back into its workspace', async () => {
    const { windows, deps, terminal, workspace } = setup();
    windows.panes.set('a', 'w');
    await windows.request({ paneId: 'a', requestId: 'r1', action: 'dock', args: {} });
    expect(windows.panes.has('a')).toBe(false);
    expect(deps.call).toHaveBeenCalledWith('terminal_window_close', { paneId: 'a' });
    expect(deps.selectWorkspace).toHaveBeenCalledWith(workspace);
    expect(workspace.focused).toBe('a');
    expect(terminal.focus).toHaveBeenCalled();
    expect(replies(deps.call)).toEqual([{ paneId: 'a', value: { requestId: 'r1', result: null } }]);
  });

  it('sync publishes state and closes windows for removed panes', () => {
    const { windows, deps } = setup();
    windows.panes.set('a', 'w');
    windows.panes.set('gone', 'w');
    windows.sync();
    expect(windows.panes.has('gone')).toBe(false);
    expect(deps.call).toHaveBeenCalledWith('terminal_window_close', { paneId: 'gone' });
    expect(deps.call).toHaveBeenCalledWith('terminal_window_publish', {
      paneId: 'a',
      value: { kind: 'state', state: { status: 'ok' } },
    });
  });

  it('tracks window focus and closing', () => {
    const { windows } = setup();
    windows.panes.set('a', 'w');
    windows.focused('a', true);
    expect(windows.focus).toBe('a');
    windows.focused('b', true);
    expect(windows.focus).toBe('a');
    windows.closed('a');
    expect(windows.focus).toBe('');
    expect(windows.panes.has('a')).toBe(false);
  });
});
