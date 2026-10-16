// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { handleLinkPresses, terminalKeyHandler } from '../src/lib/terminal-input';
import {
  PasteConfirmation,
  pasteEventHandler,
  pasteFromClipboard,
} from '../src/lib/terminal-paste.svelte';
import { WindowRequests } from '../src/lib/terminal-window';

const key = (code: string, extra: KeyboardEventInit = {}) =>
  new KeyboardEvent('keydown', { code, ctrlKey: true, cancelable: true, ...extra });

describe('terminal key handler', () => {
  const setup = (selection = '', shortcut?: (event: KeyboardEvent) => boolean) => {
    const copy = vi.fn(async () => {});
    const paste = vi.fn();
    const handle = terminalKeyHandler({
      selection: () => selection,
      copy,
      report: vi.fn(),
      paste,
      shortcut,
    });
    return { handle, copy, paste };
  };
  it('copies a selection instead of sending Ctrl+C', async () => {
    const { handle, copy } = setup('text');
    expect(handle(key('KeyC'))).toBe(false);
    await Promise.resolve();
    expect(copy).toHaveBeenCalledWith('text');
  });
  it('leaves Ctrl+C to the terminal without a selection', () => {
    expect(setup().handle(key('KeyC'))).toBe(true);
  });
  it('takes Ctrl+V for the app paste path but not Ctrl+Alt+V', () => {
    const { handle, paste } = setup();
    const event = key('KeyV');
    expect(handle(event)).toBe(false);
    expect(event.defaultPrevented).toBe(true);
    expect(paste).toHaveBeenCalledOnce();
    expect(handle(key('KeyV', { altKey: true }))).toBe(true);
    expect(paste).toHaveBeenCalledOnce();
  });
  it('lets a view claim other Ctrl shortcuts', () => {
    const { handle } = setup('', (event) => event.code === 'KeyF');
    expect(handle(key('KeyF'))).toBe(false);
    expect(handle(key('KeyA'))).toBe(true);
    expect(handle(key('KeyF', { ctrlKey: false }))).toBe(true);
  });
});

describe('link presses', () => {
  const press = (target: EventTarget, type: string, init: MouseEventInit = {}) => {
    const event = new MouseEvent(type, { bubbles: true, cancelable: true, ...init });
    target.dispatchEvent(event);
    return event;
  };
  const setup = (hovered: () => string) => {
    const root = document.createElement('div');
    document.body.append(root);
    const open = vi.fn();
    const focus = vi.fn();
    const selections: MouseEvent[] = [];
    root.addEventListener('mousedown', (event) => selections.push(event));
    const stop = handleLinkPresses({
      root,
      mac: false,
      modified: (event) => event.ctrlKey,
      intercept: (modified) => !!hovered() && modified,
      record: () => hovered(),
      accept: (url) => url === hovered(),
      open,
      focus,
    });
    return { root, open, focus, selections, stop };
  };
  it('opens a modified click on the link released where it was pressed', () => {
    const { root, open, focus, stop } = setup(() => 'a.png');
    expect(press(root, 'mousedown', { ctrlKey: true }).defaultPrevented).toBe(true);
    expect(focus).toHaveBeenCalled();
    press(root, 'mouseup', { ctrlKey: true, clientX: 3 });
    expect(open).toHaveBeenCalledWith(expect.any(MouseEvent), 'a.png');
    stop();
  });
  it('does not open after a drag or a modifier change', () => {
    const { root, open, stop } = setup(() => 'a.png');
    press(root, 'mousedown', { ctrlKey: true });
    press(root, 'mouseup', { ctrlKey: true, clientX: 20 });
    press(root, 'mousedown', { ctrlKey: true });
    press(root, 'mouseup');
    expect(open).not.toHaveBeenCalled();
    stop();
  });
  it('leaves presses it does not intercept to xterm', () => {
    const { root, open, stop } = setup(() => '');
    expect(press(root, 'mousedown', { ctrlKey: true }).defaultPrevented).toBe(false);
    press(root, 'mouseup', { ctrlKey: true });
    expect(open).not.toHaveBeenCalled();
    stop();
  });
});

describe('paste', () => {
  it('asks once for multi-line text and remembers "don\'t ask"', async () => {
    const dontAsk = vi.fn();
    const done = vi.fn();
    const confirmation = new PasteConfirmation(() => true, dontAsk, done);
    expect(await confirmation.confirm('one line')).toBe(true);
    const answer = confirmation.confirm('a\nb');
    expect(confirmation.request?.text).toBe('a\nb');
    confirmation.answer(true, true);
    expect(await answer).toBe(true);
    expect(dontAsk).toHaveBeenCalledOnce();
    expect(done).toHaveBeenCalledOnce();
    expect(confirmation.request).toBeNull();
  });
  it('declines a waiting paste when the view closes', async () => {
    const confirmation = new PasteConfirmation(() => true, vi.fn(), vi.fn());
    const answer = confirmation.confirm('a\nb');
    confirmation.cancel();
    expect(await answer).toBe(false);
  });
  it('pastes clipboard text only into the terminal that asked', async () => {
    const clipboard = { readText: async () => 'hello' } as unknown as Clipboard;
    const text = vi.fn(async () => {});
    const files = vi.fn(async () => {});
    await pasteFromClipboard(() => 'ctx', { files, text }, clipboard);
    expect(text).toHaveBeenCalledWith('hello', 'ctx');
    let destination = 'one';
    const moving = { readText: async () => ((destination = 'two'), 'x') } as unknown as Clipboard;
    await expect(pasteFromClipboard(() => destination, { files, text }, moving)).rejects.toThrow(
      'Terminal changed',
    );
    await expect(pasteFromClipboard(() => '', { files, text }, clipboard)).rejects.toThrow();
    expect(text).toHaveBeenCalledOnce();
  });
  it('leaves single-line paste events to xterm and takes multi-line text', () => {
    const text = vi.fn(async () => {});
    const handle = pasteEventHandler(() => true, { files: vi.fn(async () => {}), text }, vi.fn());
    const event = (value: string) => {
      const data = { files: [], getData: () => value };
      const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
      Object.defineProperty(paste, 'clipboardData', { value: data });
      handle(paste);
      return paste;
    };
    expect(event('one').defaultPrevented).toBe(false);
    expect(event('one\ntwo').defaultPrevented).toBe(true);
    expect(text).toHaveBeenCalledWith('one\ntwo');
  });
});

describe('window requests', () => {
  it('resolves by request id and rejects open requests on close', async () => {
    const sent: Record<string, unknown>[] = [];
    const requests = new WindowRequests(async (_name, args) => void sent.push(args));
    const first = requests.send('snapshot');
    const second = requests.send('input', { data: 'a' });
    await Promise.resolve();
    requests.answer({ requestId: sent[0].requestId as string, result: 'ok' });
    expect(await first).toBe('ok');
    requests.close();
    await expect(second).rejects.toThrow('closed');
    await expect(requests.send('focus')).rejects.toThrow('closed');
  });
  it('reports uncertain input after the timeout without retrying', async () => {
    vi.useFakeTimers();
    const call = vi.fn(async () => {});
    const requests = new WindowRequests(call, 50);
    const job = requests.send('input', { data: 'a' });
    vi.advanceTimersByTime(60);
    await expect(job).rejects.toThrow('Delivery uncertain');
    expect(call).toHaveBeenCalledOnce();
    vi.useRealTimers();
  });
});
