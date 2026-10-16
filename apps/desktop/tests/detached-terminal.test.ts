// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { calls, emit, frame, handlers, resetTauri } from './support/tauri';
import { Terminal as FakeTerminal } from './support/xterm';
import { installDom, pasteEvent } from './support/dom';
import DetachedTerminal from '../src/components/DetachedTerminal.svelte';
import { terminalDefaults } from '../src/lib/terminals';
import type { Channel } from '@tauri-apps/api/core';
import type { TerminalSnapshot, WindowTerminalState } from '../src/lib/terminal-window';

vi.mock('@tauri-apps/api/core', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/event', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/window', () => import('./support/tauri'));
vi.mock('@xterm/xterm', () => import('./support/xterm'));
vi.mock('@xterm/addon-fit', () => import('./support/addons'));
vi.mock('@xterm/addon-webgl', () => import('./support/addons'));
vi.mock('@xterm/addon-image', () => import('./support/addons'));
vi.mock('@xterm/addon-ligatures', () => import('./support/addons'));

const noShortcut = { key: 0, ctrl: false, alt: false, shift: false, meta: false, label: '' };
const state: WindowTerminalState = {
  pane: {
    id: 'p1',
    hostId: 'h1',
    sessionId: 's1',
    name: 'shell',
    repo: '',
    identity: '',
    agent: '',
    model: '',
    effort: '',
    operation: '',
  },
  workspaceName: 'Main',
  theme: 'blue',
  settings: terminalDefaults,
  inputShortcut: noShortcut,
  commandShortcut: noShortcut,
  voiceStatus: 'idle',
  voiceMode: '',
  voiceTarget: '',
  dictation: '',
  status: '',
  ready: true,
};

// The workspace window answers each request through 'terminal-window-message'.
function workspace(snapshots: Omit<TerminalSnapshot, 'generation' | 'context' | 'ready'>[]) {
  const requests: string[] = [];
  handlers.set('terminal_window_request', (args) => {
    const { requestId, action } = args as { requestId: string; action: string };
    requests.push(action);
    const reply = (result: unknown) =>
      queueMicrotask(() => emit('terminal-window-message', { requestId, result }));
    if (action === 'snapshot') {
      const snapshot = snapshots.shift();
      if (!snapshot) throw new Error('unexpected snapshot');
      reply({ state, snapshot: { generation: 1, context: 'ctx', ready: true, ...snapshot } });
    } else reply(action === 'input' || action === 'paste' ? 'ctx' : null);
    return undefined;
  });
  return requests;
}

async function opened(snapshots: Parameters<typeof workspace>[0]) {
  const requests = workspace(snapshots);
  render(DetachedTerminal);
  await waitFor(() => expect(calls('terminal_view_open')).toHaveLength(1));
  const view = calls('terminal_view_open')[0];
  expect(view.paneId).toBe('p1');
  const output = view.output as Channel<ArrayBuffer>;
  await waitFor(() => expect(requests).toContain('snapshot'));
  const term = FakeTerminal.instances.at(-1)!;
  const send = async (sequence: number, text: string) => {
    output.onmessage(frame(sequence, text));
    await term.flush();
  };
  return { term, send, requests };
}

beforeEach(() => {
  installDom();
  resetTauri();
  history.replaceState(null, '', '/?terminal=p1');
});
afterEach(() => {
  FakeTerminal.instances.length = 0;
  history.replaceState(null, '', '/');
});

describe('detached terminal output', () => {
  it('draws the snapshot, then frames that follow its sequence number', async () => {
    const { term, send } = await opened([{ sequence: 5, data: 'snapshot', cols: 80, rows: 24 }]);
    await waitFor(() => expect(term.text()).toBe('snapshot'));
    await send(5, ' old');
    await send(6, ' next');
    await send(6, ' duplicate');
    await send(7, ' last');
    expect(term.text()).toBe('snapshot next last');
  });
  it('holds frames that arrive before the snapshot and replays only the newer ones', async () => {
    const requests = workspace([{ sequence: 2, data: 'base', cols: 80, rows: 24 }]);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const reply = handlers.get('terminal_window_request')!;
    handlers.set('terminal_window_request', async (args) => {
      if ((args as { action: string }).action === 'snapshot') await gate;
      return reply(args);
    });
    render(DetachedTerminal);
    await waitFor(() => expect(calls('terminal_view_open')).toHaveLength(1));
    const output = calls('terminal_view_open')[0].output as Channel<ArrayBuffer>;
    output.onmessage(frame(2, ' covered'));
    output.onmessage(frame(3, ' after'));
    release();
    const term = FakeTerminal.instances.at(-1)!;
    await waitFor(async () => {
      await term.flush();
      expect(term.text()).toBe('base after');
    });
    expect(requests.filter((action) => action === 'snapshot')).toHaveLength(1);
  });
  it('asks for a new snapshot when a sequence number is missing', async () => {
    const { term, send, requests } = await opened([
      { sequence: 1, data: 'one', cols: 80, rows: 24 },
      { sequence: 3, data: 'one two three', cols: 80, rows: 24 },
    ]);
    await waitFor(() => expect(term.text()).toBe('one'));
    await send(3, ' three');
    expect(term.text()).toBe('one');
    await waitFor(() => expect(requests.filter((action) => action === 'snapshot')).toHaveLength(2));
    await waitFor(async () => {
      await term.flush();
      expect(term.text()).toBe('one two three');
    });
    await send(4, ' four');
    expect(term.text()).toBe('one two three four');
  });
});

describe('detached terminal paste', () => {
  it('confirms a multi-line paste, then sends it to the workspace window once', async () => {
    const { requests } = await opened([{ sequence: 1, data: '', cols: 80, rows: 24 }]);
    const root = document.querySelector<HTMLElement>('.xterm')!.parentElement!;
    await waitFor(() => expect(FakeTerminal.instances.at(-1)!.focusCount).toBeGreaterThan(0));
    root.dispatchEvent(pasteEvent('make\nmake test'));
    await fireEvent.click(await screen.findByRole('checkbox', { name: "Don't ask again" }));
    await fireEvent.click(screen.getByRole('button', { name: 'Paste' }));
    await waitFor(() => expect(requests).toContain('paste'));
    expect(requests).toContain('paste-confirm-off');
    const paste = calls('terminal_window_request').find((args) => args.action === 'paste')!;
    expect(paste.args).toEqual({ text: 'make\nmake test', context: 'ctx' });
    expect(requests.filter((action) => action === 'paste')).toHaveLength(1);
  });
});
