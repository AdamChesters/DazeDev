import { it, expect, vi } from 'vitest';
import {
  snapshotBroadcast,
  broadcastRevision,
  createBroadcastDelivery,
  type BroadcastInput,
} from '../src/lib/broadcast';
import type { TerminalControls } from '../src/lib/terminals';
import { voiceDestination } from '../src/lib/voice-routing';
const input = (id: string, host = 'origin', connected = true): BroadcastInput => ({
  id,
  hostId: host,
  sourceHost: host,
  label: id,
  connected,
});
const terminal = () =>
  ({
    context: vi.fn(() => 'ready'),
    pasteBroadcast: vi.fn(async () => {}),
    processId: () => 1,
  }) as unknown as TerminalControls;
const plain = { attachment: 'plain' as const, nestedSsh: false, hostname: null };
const tmux = { ...plain, attachment: 'tmux' as const, sessionId: '$1', stamp: '123' };
it('deduplicates actual source tmux sessions while retaining different hosts and plain terminals', async () => {
  const inputs = [
    input('a'),
    input('mirror'),
    input('other', 'other-host'),
    input('shell'),
    input('offline', 'origin', false),
  ];
  const terminals = new Map(inputs.map((i) => [i.id, terminal()]));
  const snapshot = await snapshotBroadcast(inputs, terminals, async (i) =>
    i.id === 'shell' ? plain : tmux,
  );
  expect(snapshot.recipients.map((r) => r.id)).toEqual(['a', 'other', 'shell']);
  expect(snapshot.mirrors).toBe(1);
  expect(snapshot.skipped).toEqual(['offline']);
  const send = createBroadcastDelivery(snapshot, 'hello');
  const result = await send(terminals);
  expect(result.delivered).toHaveLength(3);
  expect(terminals.get('mirror')!.pasteBroadcast).not.toHaveBeenCalled();
  expect(terminals.get('shell')!.pasteBroadcast).toHaveBeenCalledWith('hello', 'ready');
  await expect(send(terminals)).rejects.toThrow('already');
});
it('keeps unknown and nested SSH connected panes as independent recipients', async () => {
  const inputs = [input('unknown'), input('nested')],
    terminals = new Map(inputs.map((i) => [i.id, terminal()]));
  const s = await snapshotBroadcast(inputs, terminals, async (i) =>
    i.id === 'nested' ? { ...tmux, nestedSsh: true } : Promise.reject(Error('unsupported')),
  );
  expect(s.recipients).toHaveLength(2);
  expect(s.skipped).toHaveLength(0);
});
it('bounds unresponsive metadata probes', async () => {
  vi.useFakeTimers();
  try {
    const promise = snapshotBroadcast(
      [input('slow')],
      new Map([['slow', terminal()]]),
      () => new Promise(() => {}),
    );
    await vi.advanceTimersByTimeAsync(1500);
    expect((await promise).recipients).toHaveLength(1);
  } finally {
    vi.useRealTimers();
  }
});
it('detects changed recipients and never retries failed or changed deliveries', async () => {
  const t = terminal(),
    terminals = new Map([['a', t]]);
  const first = await snapshotBroadcast([input('a')], terminals, async () => tmux);
  const changed = await snapshotBroadcast([input('a')], terminals, async () => ({
    ...tmux,
    sessionId: '$2',
  }));
  expect(broadcastRevision(first)).not.toBe(broadcastRevision(changed));
  vi.mocked(t.context).mockReturnValue('new input');
  expect((await createBroadcastDelivery(first, 'message')(terminals)).failed).toHaveLength(1);
  expect(t.pasteBroadcast).not.toHaveBeenCalled();
  vi.mocked(t.context).mockReturnValue('ready');
  vi.mocked(t.pasteBroadcast!).mockRejectedValue(Error('uncertain'));
  const send = createBroadcastDelivery(first, 'message');
  expect((await send(terminals)).failed).toHaveLength(1);
  await expect(send(terminals)).rejects.toThrow();
  expect(t.pasteBroadcast).toHaveBeenCalledTimes(1);
});
it.each(['command', 'broadcast'] as const)(
  'routes either voice shortcut to the open %s modal',
  (modal) => {
    expect(voiceDestination('dictation', modal)).toBe(modal);
    expect(voiceDestination('command', modal)).toBe(modal);
    expect(voiceDestination('dictation', '')).toBe('dictation');
  },
);
it('retains a live PTY when the independent management metadata channel is unavailable', async () => {
  const live = terminal(),
    terminals = new Map([['live', live]]);
  const snapshot = await snapshotBroadcast([input('live')], terminals, async () => {
    throw Error('management channel disconnected');
  });
  expect(snapshot.recipients.map((r) => r.id)).toEqual(['live']);
  expect((await createBroadcastDelivery(snapshot, 'hello')(terminals)).delivered).toEqual(['live']);
});
