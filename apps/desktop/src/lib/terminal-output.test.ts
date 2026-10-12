import { describe, expect, it, vi } from 'vitest';
const events = vi.hoisted(() => ({ receive: undefined as undefined | ((value: {id: string;data: string;generation: number}) => void) }));
vi.mock('./bridge', () => ({on: vi.fn(async (_: string, receive: typeof events.receive) => {events.receive = receive;return () => {};})}));
import { onTerminalOutput } from './terminal-output';
describe('shared terminal output', () => {
  it('follows the current pane identity and removes disposed listeners', async () => {
    let id = 'temporary-pane';
    const receive = vi.fn();
    const dispose = await onTerminalOutput(() => id, receive);
    events.receive!({id, data: 'first', generation: 1});
    id = 'restored-pane';
    events.receive!({id: 'temporary-pane', data: 'stale', generation: 1});
    events.receive!({id, data: 'ready', generation: 2});
    expect(receive.mock.calls.map(([value]) => value.data)).toEqual(['first', 'ready']);
    dispose();
    events.receive!({id, data: 'closed', generation: 2});
    expect(receive).toHaveBeenCalledTimes(2);
  });
});
