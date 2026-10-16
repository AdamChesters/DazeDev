import { afterEach, expect, it, vi } from 'vitest';
import {
  connectionDeadline,
  HOST_HEALTH_TIMEOUT,
  ReadinessDeadline,
  TERMINAL_READY_TIMEOUT,
  ConnectionOperations,
} from '../src/lib/connection-health';
afterEach(() => vi.useRealTimers());
it('expires a stalled health IPC even if native teardown never settles', async () => {
  vi.useFakeTimers();
  let resolve!: () => void;
  const native = new Promise<void>((done) => {
    resolve = done;
  });
  const result = connectionDeadline(native, HOST_HEALTH_TIMEOUT, 'Host unavailable');
  const failed = expect(result).rejects.toThrow('Host unavailable');
  await vi.advanceTimersByTimeAsync(HOST_HEALTH_TIMEOUT);
  await failed;
  resolve();
  await Promise.resolve();
  expect(vi.getTimerCount()).toBe(0);
});
it('successful and failed probes clean their deadline timers', async () => {
  vi.useFakeTimers();
  await expect(connectionDeadline(Promise.resolve('healthy'), 12000, 'expired')).resolves.toBe(
    'healthy',
  );
  await expect(
    connectionDeadline(Promise.reject(new Error('closed')), 12000, 'expired'),
  ).rejects.toThrow('closed');
  expect(vi.getTimerCount()).toBe(0);
});
it('spawn without readiness eventually offers recovery; ready shells do not expire', async () => {
  vi.useFakeTimers();
  const deadline = new ReadinessDeadline();
  const expired = vi.fn();
  deadline.arm(expired);
  await vi.advanceTimersByTimeAsync(TERMINAL_READY_TIMEOUT - 1);
  expect(expired).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(expired).toHaveBeenCalledOnce();
  deadline.arm(expired);
  deadline.clear();
  await vi.advanceTimersByTimeAsync(TERMINAL_READY_TIMEOUT);
  expect(expired).toHaveBeenCalledOnce();
});
it('replacement attachment cancels previous readiness timeout and teardown cancels replacement', async () => {
  vi.useFakeTimers();
  const deadline = new ReadinessDeadline();
  const old = vi.fn(),
    replacement = vi.fn();
  deadline.arm(old);
  await vi.advanceTimersByTimeAsync(50000);
  deadline.arm(replacement);
  await vi.advanceTimersByTimeAsync(10000);
  expect(old).not.toHaveBeenCalled();
  expect(replacement).not.toHaveBeenCalled();
  deadline.clear();
  await vi.advanceTimersByTimeAsync(TERMINAL_READY_TIMEOUT);
  expect(replacement).not.toHaveBeenCalled();
});

it('delayed open and close finish before replacement, without blocking another host', async () => {
  const lifecycle = new ConnectionOperations();
  const seen: string[] = [];
  let opened!: () => void, closed!: () => void;
  const first = lifecycle.run('pane', async () => {
    seen.push('open');
    await new Promise<void>((r) => {
      opened = r;
    });
  });
  const close = lifecycle.run('pane', async () => {
    seen.push('close');
    await new Promise<void>((r) => {
      closed = r;
    });
  });
  const retry = lifecycle.run('pane', async () => {
    seen.push('retry');
  });
  await lifecycle.run('other', async () => {
    seen.push('other');
  });
  expect(seen).toEqual(['open', 'other']);
  opened();
  await first;
  await Promise.resolve();
  await Promise.resolve();
  expect(seen).toEqual(['open', 'other', 'close']);
  closed();
  await close;
  await retry;
  expect(seen).toEqual(['open', 'other', 'close', 'retry']);
});
it('failed lifecycle operation still allows explicit replacement', async () => {
  const lifecycle = new ConnectionOperations();
  await expect(
    lifecycle.run('host', async () => {
      throw Error('spawn failed');
    }),
  ).rejects.toThrow('spawn failed');
  await expect(lifecycle.run('host', async () => 'connected')).resolves.toBe('connected');
});
