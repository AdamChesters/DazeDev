// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { calls, getCurrentWindow, resetTauri } from './support/tauri';
import { installDom } from './support/dom';
import { RECORDING_TAIL_MS } from '../src/lib/recording-tail';
import { Dictation } from '../src/lib/state/dictation.svelte';
import { terminals, type TerminalControls } from '../src/lib/terminals';
import type { App } from '../src/lib/state/app';

vi.mock('@tauri-apps/api/core', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/event', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/window', () => import('./support/tauri'));

type FakeCapture = {
  start: ReturnType<typeof vi.fn>;
  stop: ReturnType<typeof vi.fn>;
  onerror: (e: unknown) => void;
};
const captures = vi.hoisted(() => [] as FakeCapture[]);
vi.mock('../src/lib/audio', () => ({
  Capture: class {
    onerror: (e: unknown) => void = () => {};
    start = vi.fn(async (_device: string, onerror: (e: unknown) => void) => {
      this.onerror = onerror;
    });
    stop = vi.fn(async () => {});
    level = () => null;
    waveform = () => null;
    constructor() {
      captures.push(this as unknown as FakeCapture);
    }
  },
}));

const appWindow = getCurrentWindow();

function setup({ inputMode = 'toggle' }: { inputMode?: 'toggle' | 'hold' } = {}) {
  let focused = true;
  const pane = { id: 'p1', name: 'shell', hostId: 'h1' };
  const workspace = { id: 'w1', hostId: 'h1', name: 'Main', panes: [pane], focused: 'p1' };
  const ui = {
    device: {
      inputMode,
      listenOnboarded: true,
      activeWorkspace: 'w1',
      activeHost: 'h1',
      collapsedHosts: {},
      appShortcuts: {},
      voice: { model: 'm', backend: 'b', cleanup: false, automatic: true, microphone: '' },
    },
    modal: '',
    paneMenu: null,
    maximize: '',
    notice: '',
    error: '',
    fail: vi.fn(),
    task: async (fn: () => Promise<void>) => {
      try {
        await fn();
      } catch (e) {
        ui.fail(e);
      }
    },
  };
  const app = {
    ui,
    panes: {
      workspaces: [workspace],
      activePane: pane,
      owner: () => workspace,
      filesLocked: false,
    },
    voiceModels: { cacheTask: undefined as Promise<void> | undefined, switching: false },
    detached: { panes: new Map<string, string>(), focus: '' },
    broadcast: { busy: false, result: '', text: '', snapshot: null, focusInput: vi.fn() },
    commands: { text: '', review: null, error: '', focusInput: vi.fn() },
    appHasFocus: () => focused,
  };
  const terminal = {
    pasteDictation: vi.fn(async (_text: string, _enter: boolean) => {}),
    context: () => 'prompt',
  };
  terminals.set('p1', terminal as unknown as TerminalControls);
  let dictation!: Dictation;
  const stop = $effect.root(() => {
    dictation = new Dictation(app as unknown as App);
  });
  return {
    app,
    ui,
    dictation,
    terminal,
    stop,
    leave: () => (focused = false),
    returnToApp: () => (focused = true),
  };
}

/** The id the desktop app was given for the latest recording. */
const recordingId = () => String(calls('voice_start').at(-1)?.recording);

describe('dictation when DazeDev loses focus', () => {
  let stop: (() => void) | undefined;
  beforeEach(() => {
    installDom();
    vi.useFakeTimers();
    resetTauri();
    captures.length = 0;
    vi.mocked(appWindow.setFocus).mockClear();
    // jsdom has no layout; give the receiving pane a size.
    const pane = document.createElement('div');
    pane.dataset.paneId = 'p1';
    pane.getBoundingClientRect = () => ({ width: 400 }) as DOMRect;
    const input = document.createElement('textarea');
    input.className = 'xterm-helper-textarea';
    pane.append(input);
    document.body.append(pane);
  });
  afterEach(() => {
    stop?.();
    stop = undefined;
    terminals.clear();
    document.body.replaceChildren();
    vi.useRealTimers();
  });

  it('keeps listening in toggle mode until the user returns and stops it', async () => {
    const view = setup();
    stop = view.stop;
    const { dictation, terminal } = view;
    await dictation.record('dictation');
    expect(dictation.status).toBe('listening');

    view.leave();
    await dictation.focusLost();
    await vi.advanceTimersByTimeAsync(5000);
    expect(dictation.status).toBe('listening');
    expect(captures[0].stop).not.toHaveBeenCalled();
    expect(calls('voice_cancel')).toHaveLength(0);
    expect(calls('voice_stop')).toHaveLength(0);

    view.returnToApp();
    const finishing = dictation.record('dictation');
    await vi.advanceTimersByTimeAsync(RECORDING_TAIL_MS);
    await finishing;
    expect(calls('voice_stop')).toHaveLength(1);
    dictation.onText({ recording: recordingId(), text: 'hello there', final: true });
    // Automatic Enter still applies when the user stopped it themselves.
    await vi.waitFor(() =>
      expect(terminal.pasteDictation).toHaveBeenCalledExactlyOnceWith('hello there', true),
    );
    await vi.waitFor(() => expect(dictation.status).toBe('idle'));
  });

  it('finishes a hold-to-talk recording on focus loss and leaves the text for review', async () => {
    const view = setup({ inputMode: 'hold' });
    stop = view.stop;
    const { dictation, terminal } = view;
    await dictation.inputEdge(true, 'dictation');
    expect(dictation.status).toBe('listening');
    expect(dictation.shortcutHeld).toBe('dictation');
    const raisedBefore = vi.mocked(appWindow.setFocus).mock.calls.length;

    view.leave();
    const finishing = dictation.focusLost();
    expect(dictation.shortcutHeld).toBeNull();
    await vi.advanceTimersByTimeAsync(RECORDING_TAIL_MS);
    await finishing;
    expect(calls('voice_stop')).toHaveLength(1);
    expect(calls('voice_cancel')).toHaveLength(0);

    dictation.onText({ recording: recordingId(), text: 'hello there', final: true });
    // Delivered, but Enter is not pressed even though automatic Enter is on.
    await vi.waitFor(() =>
      expect(terminal.pasteDictation).toHaveBeenCalledExactlyOnceWith('hello there', false),
    );
    await vi.waitFor(() => expect(dictation.status).toBe('idle'));
    expect(dictation.recent?.submitContext).toBe('prompt');
    // Delivery did not pull DazeDev in front of the app the user switched to.
    expect(vi.mocked(appWindow.setFocus).mock.calls.length).toBe(raisedBefore);
  });

  it('does not cancel a start during model warm-up or leave it loading', async () => {
    const view = setup();
    stop = view.stop;
    const { app, dictation } = view;
    let warm!: () => void;
    app.voiceModels.cacheTask = new Promise<void>((resolve) => (warm = resolve));
    const start = dictation.record('dictation');
    expect(dictation.status).toBe('loading');

    view.leave();
    await dictation.focusLost();
    expect(dictation.status).toBe('loading');
    warm();
    await start;
    expect(dictation.status).toBe('listening');
    expect(calls('voice_start')).toHaveLength(1);
    expect(calls('voice_cancel')).toHaveLength(0);
    // The start did not steal focus back from the other app.
    expect(appWindow.setFocus).not.toHaveBeenCalled();
    expect(app.ui.fail).not.toHaveBeenCalled();
  });

  it('finishes a held start that was still warming up once it is listening', async () => {
    const view = setup({ inputMode: 'hold' });
    stop = view.stop;
    const { app, dictation } = view;
    let warm!: () => void;
    app.voiceModels.cacheTask = new Promise<void>((resolve) => (warm = resolve));
    const press = dictation.inputEdge(true, 'dictation');
    expect(dictation.status).toBe('loading');

    view.leave();
    await dictation.focusLost();
    warm();
    // It starts listening, then finishes straight away as if the key was released.
    await vi.waitFor(() => expect(dictation.status).toBe('finishing-recording'));
    await vi.advanceTimersByTimeAsync(RECORDING_TAIL_MS);
    await press;
    expect(dictation.status).toBe('transcribing');
    expect(dictation.finishWithoutSubmit).toBe(true);
    expect(calls('voice_stop')).toHaveLength(1);
    expect(calls('voice_cancel')).toHaveLength(0);
  });

  it('still finishes the recording if the microphone stops while the user is away', async () => {
    const view = setup();
    stop = view.stop;
    const { dictation, terminal } = view;
    await dictation.record('dictation');
    view.leave();
    await dictation.focusLost();
    captures[0].onerror(new Error('Microphone stopped or was suspended.'));
    await vi.advanceTimersByTimeAsync(RECORDING_TAIL_MS);
    await vi.waitFor(() => expect(calls('voice_stop')).toHaveLength(1));
    expect(dictation.status).toBe('transcribing');
    dictation.onText({ recording: recordingId(), text: 'hello there', final: true });
    // Nobody is watching, so automatic Enter is not pressed.
    await vi.waitFor(() =>
      expect(terminal.pasteDictation).toHaveBeenCalledExactlyOnceWith('hello there', false),
    );
  });

  it('needs focus to start a recording', async () => {
    const view = setup();
    stop = view.stop;
    const { dictation } = view;
    view.leave();
    await dictation.record('dictation');
    await dictation.inputEdge(true, 'dictation');
    expect(dictation.status).toBe('idle');
    expect(calls('voice_start')).toHaveLength(0);
  });

  it('abandons a half-recorded hotkey in Settings', async () => {
    const view = setup();
    stop = view.stop;
    const { dictation, ui } = view;
    ui.modal = 'settings';
    dictation.captureShortcut('dictation');
    expect(dictation.shortcutStatus).toMatch(/Press a key combination/);

    view.leave();
    await dictation.focusLost();
    expect(dictation.capturingShortcut).toBeNull();
    await vi.waitFor(() =>
      expect(dictation.shortcutStatus).toBe('Both hotkeys work only while DazeDev is focused.'),
    );
    expect(calls('input_configure')).toEqual([{ binding: null }]);
  });

  it('cancels a pending review tap so it cannot start a recording later', async () => {
    const view = setup();
    stop = view.stop;
    const { dictation } = view;
    const start = vi.fn();
    dictation.reviewHotkey.tap(start, vi.fn());
    view.leave();
    await dictation.focusLost();
    await vi.advanceTimersByTimeAsync(1000);
    expect(start).not.toHaveBeenCalled();
  });
});
