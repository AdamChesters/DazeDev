import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import {
  Connections,
  RECONNECT_BACKOFF_MS,
  STALLED_CONNECT_MS,
  type ConnectionsDeps,
} from '../src/lib/state/connections.svelte';
import type { Host } from '../src/lib/types';

const host: Host = {
  id: 'h',
  name: 'Dev',
  kind: 'ssh',
  address: 'dev',
  user: '',
  key: '',
  jump: '',
  distro: '',
  socket: '',
};

function setup(call: ConnectionsDeps['call'] = vi.fn(async () => undefined)) {
  const deps: ConnectionsDeps = {
    desktop: true,
    hosts: () => [host],
    call,
    onready: vi.fn(),
  };
  return { net: new Connections(deps), deps, call: call as Mock<ConnectionsDeps['call']> };
}

const names = (call: Mock<ConnectionsDeps['call']>) => call.mock.calls.map(([name]) => name);

describe('Connections', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('starts a connect attempt and accepts only its events', async () => {
    const { net, deps, call } = setup();
    await net.connect(host);
    expect(net.connections.h).toBe('Connecting');
    expect(names(call)).toEqual(['host_connect']);
    const attempt = net.attempts.get('h')!;
    expect(call.mock.calls[0][1]).toEqual({ host, attempt });
    net.ready({ hostId: 'h', attempt: 'old' });
    expect(deps.onready).not.toHaveBeenCalled();
    net.ready({ hostId: 'h', attempt, identity: { notice: 'Old broker' } });
    expect(deps.onready).toHaveBeenCalledWith('h', 'Old broker');
    net.output({ hostId: 'h', attempt: 'old', text: 'ignored' });
    net.output({ hostId: 'h', attempt, text: 'hello ' });
    net.output({ hostId: 'h', attempt, text: 'world' });
    expect(net.logs.h).toBe('hello world');
  });

  it('keeps only the last 12000 characters of host output', async () => {
    const { net } = setup();
    await net.connect(host);
    const attempt = net.attempts.get('h')!;
    net.output({ hostId: 'h', attempt, text: 'x'.repeat(13000) });
    expect(net.logs.h).toHaveLength(12000);
  });

  it('ignores a second connect while connecting', async () => {
    const { net, call } = setup();
    await net.connect(host);
    await net.connect(host);
    expect(names(call)).toEqual(['host_connect']);
  });

  it('records a failed connect', async () => {
    const { net } = setup(vi.fn().mockRejectedValue('refused'));
    await net.connect(host);
    expect(net.connections.h).toBe('Disconnected');
    expect(net.logs.h).toBe('refused');
  });

  it('marks a closed host only for its current attempt', async () => {
    const { net } = setup();
    await net.connect(host);
    net.connections.h = 'Connected';
    net.closed({ hostId: 'h', attempt: 'old' });
    expect(net.connections.h).toBe('Connected');
    net.closed({ hostId: 'h', attempt: net.attempts.get('h')! });
    expect(net.connections.h).toBe('Disconnected');
    expect(net.logs.h).toBe('Connection closed.');
  });

  it('disconnect stops reconnecting and forgets the attempt', async () => {
    const { net, call } = setup();
    await net.connect(host);
    await net.disconnect('h');
    expect(net.connections.h).toBe('Disconnected');
    expect(net.attempts.has('h')).toBe(false);
    expect(net.reconnect.has('h')).toBe(false);
    expect(names(call)).toEqual(['host_connect', 'host_disconnect']);
    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS);
    await net.check();
    expect(names(call)).toEqual(['host_connect', 'host_disconnect']);
  });

  it('health check reconnects a host that stopped answering', async () => {
    const call = vi.fn(async (name: string) => {
      if (name === 'host_health') throw new Error('timeout');
      return undefined;
    });
    const { net } = setup(call);
    await net.connect(host);
    net.connections.h = 'Connected';
    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS);
    await net.check();
    expect(names(call)).toEqual(['host_connect', 'host_health', 'host_connect']);
    expect(net.connections.h).toBe('Connecting');
  });

  it('waits for the backoff and replaces a stalled attempt', async () => {
    const { net, call } = setup();
    await net.connect(host);
    net.connections.h = 'Disconnected';
    await net.check();
    expect(names(call)).toEqual(['host_connect']);
    net.connections.h = 'Connecting';
    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS + STALLED_CONNECT_MS - 1);
    await net.check();
    expect(names(call)).toEqual(['host_connect']);
    await vi.advanceTimersByTimeAsync(1);
    await net.check();
    expect(names(call)).toEqual(['host_connect', 'host_connect']);
  });

  it('a stale auth failure is not reported', async () => {
    const call = vi.fn(async (name: string) => {
      if (name === 'host_auth') throw new Error('expired');
      return undefined;
    });
    const { net } = setup(call);
    await net.connect(host);
    await expect(net.respond('h', 'pw')).rejects.toThrow('expired');
    const pending = net.respond('h', 'pw');
    net.attempts.set('h', 'newer');
    await expect(pending).resolves.toBeUndefined();
  });
});
