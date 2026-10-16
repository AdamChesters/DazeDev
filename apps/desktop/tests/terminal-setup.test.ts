import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  OptionalAddons,
  ResizeSender,
  applyTerminalSettings,
  clampScrollback,
  remoteClipboard,
  terminalOptions,
  themeFromStyle,
} from '../src/lib/terminal-setup';
import { remoteClipboardWrite } from '../src/lib/terminals';

afterEach(() => vi.useRealTimers());

describe('shared terminal setup', () => {
  it('reads the terminal colours from the theme tokens', () => {
    const tokens: Record<string, string> = {
      '--term-background': ' #151616',
      '--term-cursor': '#39bdd7',
      '--term-selection': '#39bdd744',
      '--term-bright-magenta': '#e3c1ea',
    };
    expect(themeFromStyle((property) => tokens[property] || '')).toEqual({
      background: '#151616',
      cursor: '#39bdd7',
      selectionBackground: '#39bdd744',
      brightMagenta: '#e3c1ea',
    });
  });
  it('defines every terminal colour for each theme in app.css', () => {
    const css = readFileSync(new URL('../src/app.css', import.meta.url), 'utf8');
    for (const name of ['black', 'red', 'bright-white', 'background', 'foreground', 'cursor'])
      expect(css).toContain(`--term-${name}:`);
    expect(css.match(/--term-selection: #[0-9a-f]{8};/g)).toHaveLength(3);
  });
  it('clamps font size and history in both new and existing terminals', () => {
    const options = terminalOptions({ fontSize: 99, scrollback: 5e6 }, undefined);
    expect(options.fontSize).toBe(32);
    expect(options.scrollback).toBe(100000);
    expect(clampScrollback(Number.NaN)).toBe(10000);
    const term = { options: { fontSize: 13, scrollback: 1000, screenReaderMode: false } };
    applyTerminalSettings(term as never, { fontSize: 2, scrollback: 50, screenReader: true });
    expect(term.options).toEqual({ fontSize: 8, scrollback: 100, screenReaderMode: true });
    applyTerminalSettings(term as never, { fontSize: 13, scrollback: 50000 }, 20000);
    expect(term.options.scrollback).toBe(20000);
  });
});

describe('optional add-ons', () => {
  function setup() {
    const made: { kind: string; disposed: boolean; lose?: () => void }[] = [];
    const make = (kind: string) => () => {
      const addon = {
        kind,
        disposed: false,
        activate() {},
        dispose() {
          this.disposed = true;
        },
        onContextLoss(listener: () => void) {
          addon.lose = listener;
        },
      } as {
        kind: string;
        disposed: boolean;
        lose?: () => void;
        activate(): void;
        dispose(): void;
        onContextLoss(l: () => void): void;
      };
      made.push(addon);
      return addon;
    };
    const loaded: unknown[] = [];
    const addons = new OptionalAddons((addon) => loaded.push(addon), {
      gpu: make('gpu'),
      images: (limit: number) => Object.assign(make('images')(), { storageLimit: limit }),
      ligatures: make('ligatures'),
    });
    return { addons, made, loaded };
  }
  const settings = { gpu: true, images: false, ligatures: false };
  it('keeps WebGL loaded while the terminal is hidden', () => {
    const { addons, made } = setup();
    addons.apply(settings, true);
    addons.apply(settings, false);
    addons.apply(settings, true);
    expect(made).toHaveLength(1);
    expect(made[0].disposed).toBe(false);
  });
  it('creates WebGL only once visible and again after a lost context', () => {
    const { addons, made } = setup();
    addons.apply(settings, false);
    expect(made).toHaveLength(0);
    addons.apply(settings, true);
    made[0].lose!();
    expect(made[0].disposed).toBe(true);
    addons.apply(settings, false);
    expect(made).toHaveLength(1);
    addons.apply(settings, true);
    expect(made).toHaveLength(2);
  });
  it('creates the image cache with its budget share and follows later changes', () => {
    const { addons, made } = setup();
    addons.imageLimit = 8;
    addons.apply({ gpu: false, images: true, ligatures: false });
    const images = made.find((a) => a.kind === 'images') as unknown as { storageLimit: number };
    expect(images.storageLimit).toBe(8);
    addons.imageLimit = 4;
    expect(images.storageLimit).toBe(4);
  });
  it('removes add-ons that are turned off and disposes everything on unmount', () => {
    const { addons, made } = setup();
    addons.apply({ gpu: true, images: true, ligatures: true });
    addons.apply({ gpu: false, images: true, ligatures: true });
    expect(made.find((a) => a.kind === 'gpu')!.disposed).toBe(true);
    addons.dispose();
    expect(made.every((a) => a.disposed)).toBe(true);
  });
});

describe('PTY resize', () => {
  it('sends only the final size after dragging pauses', () => {
    vi.useFakeTimers();
    const send = vi.fn(async () => {});
    const sender = new ResizeSender(send, 100);
    for (let cols = 80; cols < 140; cols++) {
      sender.request(cols, 24);
      vi.advanceTimersByTime(16);
    }
    expect(send).not.toHaveBeenCalled();
    vi.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledExactlyOnceWith(139, 24);
    sender.request(139, 24);
    vi.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledOnce();
  });
  it('sends at once after a new PTY opens and retries a failed size', async () => {
    vi.useFakeTimers();
    const send = vi.fn().mockRejectedValueOnce(new Error('closed')).mockResolvedValue(undefined);
    const sender = new ResizeSender(send, 100);
    sender.request(100, 30, true);
    await Promise.resolve();
    await Promise.resolve();
    sender.request(100, 30, true);
    expect(send).toHaveBeenCalledTimes(2);
    sender.reset();
    sender.request(100, 30, true);
    expect(send).toHaveBeenCalledTimes(3);
    sender.mark(90, 20);
    sender.request(90, 20, true);
    expect(send).toHaveBeenCalledTimes(3);
  });
});

describe('remote clipboard (OSC 52)', () => {
  function clipboard() {
    return { readText: vi.fn(async () => 'local'), writeText: vi.fn(async () => {}) };
  }
  it('blocks, notifies or allows remote writes by policy', async () => {
    for (const [policy, writes, notices] of [
      ['off', 0, 0],
      ['notify', 1, 1],
      ['write', 1, 0],
      ['read-write', 1, 0],
    ] as const) {
      const board = clipboard();
      const notify = vi.fn();
      await remoteClipboard(() => policy, notify, board).writeText('c', 'copied');
      expect(board.writeText, policy).toHaveBeenCalledTimes(writes);
      expect(notify, policy).toHaveBeenCalledTimes(notices);
    }
  });
  it('notifies by default and reads only with paste access', async () => {
    expect(remoteClipboardWrite(undefined)).toBe('notify');
    const board = clipboard();
    expect(await remoteClipboard(() => 'notify', vi.fn(), board).readText()).toBe('');
    expect(await remoteClipboard(() => 'read-write', vi.fn(), board).readText()).toBe('local');
  });
  it('ignores oversized remote writes', async () => {
    const board = clipboard();
    await remoteClipboard(() => 'write', vi.fn(), board).writeText('c', 'x'.repeat(1048577));
    expect(board.writeText).not.toHaveBeenCalled();
  });
});
