import { describe, expect, it, vi } from 'vitest';
import {
  ALREADY_EDITING,
  EditorRequests,
  NO_TERMINAL,
  parseEditRequest,
  type EditRequest,
} from '../src/lib/editor-requests';

const id = '0123456789abcdef0123456789abcdef';
const event = (fields: Record<string, unknown> = {}) => ({
  hostId: 'h1',
  id,
  pid: 4242,
  name: 'codex',
  text: 'draft\r\nwith [Pasted Content 1500 chars] expanded',
  ...fields,
});

function setup() {
  let receive: (value: unknown) => void = () => {};
  const stop = vi.fn();
  const listen = vi.fn(async (fn: (value: unknown) => void) => {
    receive = fn;
    return stop;
  });
  const reply = vi.fn(async (..._args: [string, string, Record<string, unknown>]) => {});
  const requests = new EditorRequests(listen, reply);
  return { requests, listen, stop, reply, receive: (value: unknown) => receive(value) };
}

describe('editor requests', () => {
  it('accepts only well-formed requests', () => {
    expect(parseEditRequest(event())).toEqual(event());
    expect(parseEditRequest(event({ name: '' }))?.name).toBe('Editor');
    for (const bad of [
      null,
      'x',
      event({ hostId: '' }),
      event({ id: 'short' }),
      event({ pid: 0 }),
      event({ pid: 1.5 }),
      event({ pid: '4242' }),
      event({ text: undefined }),
    ])
      expect(parseEditRequest(bad)).toBeNull();
  });
  it('opens the request in the terminal running that shell and says so', async () => {
    const { requests, reply, receive } = setup();
    const shown: EditRequest[] = [];
    const other = vi.fn(() => true);
    requests.register({ hostId: () => 'h2', pid: () => 4242, open: other });
    requests.register({ hostId: () => 'h1', pid: () => 7, open: other });
    requests.register({
      hostId: () => 'h1',
      pid: () => 4242,
      open: (request) => (shown.push(request), true),
    });
    receive(event());
    expect(other).not.toHaveBeenCalled();
    expect(shown).toEqual([event()]);
    expect(reply).toHaveBeenCalledWith('h1', 'editor_shown', { id });
    await requests.finish(shown[0], { text: 'saved' });
    expect(reply).toHaveBeenLastCalledWith('h1', 'editor_done', { id, text: 'saved' });
    await requests.finish(shown[0], { cancelled: true });
    expect(reply).toHaveBeenLastCalledWith('h1', 'editor_done', { id });
  });
  it('refuses at once when no terminal can show it, so the host uses a terminal editor', () => {
    const { requests, reply, receive } = setup();
    requests.register({ hostId: () => 'h1', pid: () => 0, open: () => true });
    receive(event());
    expect(reply).toHaveBeenCalledWith('h1', 'editor_done', { id, error: NO_TERMINAL });
    requests.register({ hostId: () => 'h1', pid: () => 4242, open: () => false });
    receive(event());
    expect(reply).toHaveBeenLastCalledWith('h1', 'editor_done', { id, error: ALREADY_EDITING });
    expect(reply).not.toHaveBeenCalledWith('h1', 'editor_shown', expect.anything());
  });
  it('listens only while a terminal is registered', async () => {
    const { requests, listen, stop } = setup();
    const first = requests.register({ hostId: () => 'h1', pid: () => 1, open: () => true });
    const second = requests.register({ hostId: () => 'h1', pid: () => 2, open: () => true });
    expect(listen).toHaveBeenCalledOnce();
    first();
    second();
    await Promise.resolve();
    expect(stop).toHaveBeenCalledOnce();
    requests.register({ hostId: () => 'h1', pid: () => 1, open: () => true });
    expect(listen).toHaveBeenCalledTimes(2);
  });
});
