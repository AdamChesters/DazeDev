// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { calls, emit, frame, handlers, resetTauri } from './support/tauri';
import { Terminal as FakeTerminal } from './support/xterm';
import { clipboard, installDom, pasteEvent } from './support/dom';
import Terminal from '../src/components/Terminal.svelte';
import { terminals, terminalDefaults, type TerminalSettings } from '../src/lib/terminals';
import type { Channel } from '@tauri-apps/api/core';
import type { Host, Pane } from '../src/lib/types';

vi.mock('@tauri-apps/api/core', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/event', () => import('./support/tauri'));
vi.mock('@xterm/xterm', () => import('./support/xterm'));
vi.mock('@xterm/addon-fit', () => import('./support/addons'));
vi.mock('@xterm/addon-webgl', () => import('./support/addons'));
vi.mock('@xterm/addon-image', () => import('./support/addons'));
vi.mock('@xterm/addon-ligatures', () => import('./support/addons'));
// Monaco does not run in jsdom; the editor panes fall back to a textarea.
vi.mock('../src/lib/monaco', () => {
  throw new Error('Monaco is not loaded in component tests');
});

const host: Host = {
  id: 'h1',
  name: 'Build host',
  kind: 'ssh',
  address: 'build.invalid',
  user: 'dev',
  key: '',
  jump: '',
  distro: '',
  socket: '',
};
const pane: Pane = {
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
};

async function attached(settings: Partial<TerminalSettings> = {}, paneFields: Partial<Pane> = {}) {
  const props = {
    theme: 'blue' as const,
    pane: { ...pane, ...paneFields },
    host,
    active: true,
    visible: true,
    connected: true,
    onfocus: vi.fn(),
    onstate: vi.fn(),
    onshell: vi.fn(),
    onclipboard: vi.fn(),
    onpastesetting: vi.fn(),
    onmetadata: vi.fn(),
    settings: { ...terminalDefaults, ...settings },
  };
  const view = render(Terminal, { props });
  await waitFor(() => expect(calls('terminal_open')).toHaveLength(1));
  const open = calls('terminal_open')[0];
  const output = open.output as Channel<ArrayBuffer>;
  const term = FakeTerminal.instances.at(-1)!;
  let sequence = 0;
  const write = async (text: string) => {
    output.onmessage(frame(++sequence, text));
    await term.flush();
  };
  // The host prints the ready marker once the PTY is attached.
  await write(`\x1b]777;dazedev-ready:${open.readyMarker}:4242\x07`);
  await waitFor(() => expect(props.onstate).toHaveBeenLastCalledWith('Attached'));
  const root = view.container.querySelector<HTMLElement>('.terminal')!;
  return { ...view, props, term, write, root };
}
const typed = () => calls('terminal_input').map((args) => args.data);

beforeEach(() => {
  installDom();
  resetTauri();
  clipboard.writeText.mockClear();
});
afterEach(() => {
  FakeTerminal.instances.length = 0;
});

describe('Terminal paste confirmation', () => {
  it('asks before pasting several lines, then sends them as typed input', async () => {
    const { root, props } = await attached();
    const event = pasteEvent('ls\nrm -rf build\n');
    root.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    const dialog = await screen.findByRole('alertdialog');
    expect(screen.getByRole('heading', { name: 'Paste 2 lines?' })).toBeTruthy();
    expect(dialog.textContent).toContain('rm -rf build');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Paste' }));
    expect(typed()).toEqual([]);
    await fireEvent.click(screen.getByRole('button', { name: 'Paste' }));
    await waitFor(() => expect(typed()).toEqual(['ls\rrm -rf build\r']));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(props.onpastesetting).not.toHaveBeenCalled();
  });
  it('sends nothing when the paste is cancelled or dismissed with Escape', async () => {
    const { root } = await attached();
    root.dispatchEvent(pasteEvent('one\ntwo'));
    await fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    root.dispatchEvent(pasteEvent('three\nfour'));
    await fireEvent.keyDown(await screen.findByRole('alertdialog'), { key: 'Escape' });
    expect(screen.queryByRole('alertdialog')).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(typed()).toEqual([]);
  });
  it('leaves a single line to the normal paste path', async () => {
    const { root } = await attached();
    const event = pasteEvent('git status');
    root.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
  it('turns confirmation off only when "Don\'t ask again" is ticked', async () => {
    const { root, props } = await attached();
    root.dispatchEvent(pasteEvent('a\nb'));
    await fireEvent.click(await screen.findByRole('checkbox', { name: "Don't ask again" }));
    await fireEvent.click(screen.getByRole('button', { name: 'Paste' }));
    expect(props.onpastesetting).toHaveBeenCalledWith(false);
    await waitFor(() => expect(typed()).toEqual(['a\rb']));
  });
  it('does not ask when confirmation is off', async () => {
    const { root } = await attached({ confirmPaste: false });
    const event = pasteEvent('a\nb');
    root.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
  it('wraps the pasted lines when the application asked for bracketed paste', async () => {
    const { root, write } = await attached();
    await write('\x1b[?2004h');
    root.dispatchEvent(pasteEvent('a\nb'));
    await fireEvent.click(await screen.findByRole('button', { name: 'Paste' }));
    await waitFor(() => expect(typed()).toEqual(['\x1b[200~a\rb\x1b[201~']));
  });
});

describe('Terminal long pastes', () => {
  const editor = () => screen.getByRole<HTMLTextAreaElement>('textbox', { name: /Paste/ });
  it('sends text Codex would collapse as pieces it shows inline', async () => {
    const { root, write } = await attached({}, { agent: 'codex' });
    await write('\x1b[?2004h');
    const text = 'x'.repeat(1500);
    root.dispatchEvent(pasteEvent(text));
    await waitFor(() => expect(typed()).toHaveLength(2));
    const pieces = typed().map((value) => {
      const data = String(value);
      expect(data.startsWith('\x1b[200~') && data.endsWith('\x1b[201~')).toBe(true);
      return data.slice(6, -6);
    });
    expect(pieces.join('')).toBe(text);
    expect(pieces.every((piece) => piece.length <= 1000)).toBe(true);
  });
  it('sends the same text to other programs as one paste', async () => {
    const { root, write } = await attached();
    await write('\x1b[?2004h');
    root.dispatchEvent(pasteEvent('y'.repeat(1500)));
    await waitFor(() => expect(typed()).toEqual([`\x1b[200~${'y'.repeat(1500)}\x1b[201~`]));
  });
  it('opens long text in the editable preview and pastes it unchanged', async () => {
    const { root } = await attached({}, { agent: 'codex' });
    // CRLF counts as one character, as agents count it.
    const text = 'line\r\n'.repeat(500);
    root.dispatchEvent(pasteEvent(text));
    await screen.findByRole('dialog', { name: 'Paste 2,500 characters?' });
    await waitFor(() => expect(editor().value).toContain('line'));
    expect(typed()).toEqual([]);
    await fireEvent.click(screen.getByRole('button', { name: 'Paste' }));
    await waitFor(() => expect(typed()).toEqual(['line\r'.repeat(500)]));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
  it('pastes the edited text, or nothing when cancelled', async () => {
    const { root } = await attached();
    root.dispatchEvent(pasteEvent('z'.repeat(2500)));
    await screen.findByRole('dialog');
    await waitFor(() => editor());
    await fireEvent.input(editor(), { target: { value: 'short edit' } });
    expect(screen.getByText(/10 characters · edited/)).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Paste' }));
    await waitFor(() => expect(typed()).toEqual(['short edit']));
    root.dispatchEvent(pasteEvent('q'.repeat(2500)));
    await screen.findByRole('dialog');
    await waitFor(() => editor());
    await fireEvent.keyDown(editor(), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(typed()).toEqual(['short edit']);
  });
});

describe('Terminal OSC 52 clipboard writes', () => {
  const osc52 = (text: string) => `\x1b]52;c;${btoa(text)}\x07`;
  it('copies and notifies with the default "notify" setting', async () => {
    const { write, props } = await attached({ clipboard: 'notify' });
    await write(osc52('secret'));
    await waitFor(() => expect(clipboard.writeText).toHaveBeenCalledWith('secret'));
    await waitFor(() => expect(props.onclipboard).toHaveBeenCalledWith(6));
  });
  it('copies silently with "write"', async () => {
    const { write, props } = await attached({ clipboard: 'write' });
    await write(osc52('quiet'));
    await waitFor(() => expect(clipboard.writeText).toHaveBeenCalledWith('quiet'));
    expect(props.onclipboard).not.toHaveBeenCalled();
  });
  it('blocks the write with "off"', async () => {
    const { write, props } = await attached({ clipboard: 'off' });
    await write(osc52('blocked'));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(clipboard.writeText).not.toHaveBeenCalled();
    expect(props.onclipboard).not.toHaveBeenCalled();
  });
});

describe('Terminal status polling', () => {
  const statusPolls = () =>
    calls('host_rpc').filter((args) => args.method === 'terminal_status').length;
  it('treats a reply that is not a status object as unknown status', async () => {
    // The first poll runs 500 ms after mounting. A missing reply used to
    // become the pane status and throw in the ended-session banner.
    handlers.set('host_rpc', () => undefined);
    const { props } = await attached();
    await waitFor(() => expect(statusPolls()).toBeGreaterThan(0), { timeout: 3000 });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(props.onmetadata).not.toHaveBeenCalledWith(undefined);
    expect(screen.queryByLabelText('Session ended')).toBeNull();
  });
  it('reports a real status reply', async () => {
    const status = { attachment: 'tmux', nestedSsh: false, hostname: 'build', paneDead: false };
    handlers.set('host_rpc', (args) => (args.method === 'terminal_status' ? status : undefined));
    const { props } = await attached();
    await waitFor(() => expect(props.onmetadata).toHaveBeenCalledWith(status), { timeout: 3000 });
  });
});

describe('Terminal output', () => {
  it('writes output frames in order and acknowledges the bytes', async () => {
    const { write, term } = await attached();
    await write('hello ');
    await write('world');
    expect(term.text()).toContain('hello world');
    await waitFor(() => expect(calls('terminal_ack').length).toBeGreaterThan(0));
  });
  it('shows the retained output and a reconnect banner when the PTY exits', async () => {
    const { write } = await attached();
    await write('last line');
    const generation = calls('terminal_open')[0].generation;
    emit('terminal-exit', { id: 'p1', generation });
    const banner = await screen.findByRole('button', { name: /Disconnected|Reconnecting/ });
    expect(banner).toBeTruthy();
    expect(
      screen.getByRole('textbox', { name: /Last received terminal output/ }).textContent,
    ).toContain('last line');
  });
});

describe('input typed in the detached window', () => {
  it('moves the dictation context on for this pane only, as relayed input does', async () => {
    await attached();
    const controls = terminals.get('p1')!;
    const before = controls.context();
    expect(before).not.toBe('');
    emit('terminal-window-input', { paneId: 'p1', data: '\x1b[I' });
    expect(controls.context()).toBe(before);
    emit('terminal-window-input', { paneId: 'other', data: 'x' });
    expect(controls.context()).toBe(before);
    emit('terminal-window-input', { paneId: 'p1', data: 'x' });
    const after = controls.context();
    expect(after).not.toBe(before);
    await expect(controls.submitReviewed!(before)).rejects.toThrow('terminal changed');
    expect(typed()).toEqual([]);
  });
  it('sends the new paste context to the detached window', async () => {
    const { rerender, props } = await attached();
    await rerender({ ...props, floating: true, visible: false });
    emit('terminal-window-input', { paneId: 'p1', data: 'x' });
    expect(calls('terminal_window_publish')).toEqual([
      { paneId: 'p1', value: { kind: 'context', context: terminals.get('p1')!.context() } },
    ]);
  });
});

describe('size set by the detached window', () => {
  it('resizes the hidden view of a detached pane without resizing the PTY again', async () => {
    const { term, rerender, props } = await attached();
    await rerender({ ...props, floating: true, visible: false });
    const resizes = calls('terminal_resize').length;
    emit('terminal-window-resize', { paneId: 'other', cols: 90, rows: 30 });
    emit('terminal-window-resize', { paneId: 'p1', cols: 0, rows: 30 });
    expect([term.cols, term.rows]).not.toEqual([90, 30]);
    emit('terminal-window-resize', { paneId: 'p1', cols: 90, rows: 30 });
    expect([term.cols, term.rows]).toEqual([90, 30]);
    expect(calls('terminal_resize')).toHaveLength(resizes);
  });
  it('ignores sizes for a pane shown in the workspace', async () => {
    const { term } = await attached();
    const size = [term.cols, term.rows];
    emit('terminal-window-resize', { paneId: 'p1', cols: 90, rows: 30 });
    expect([term.cols, term.rows]).toEqual(size);
  });
});
