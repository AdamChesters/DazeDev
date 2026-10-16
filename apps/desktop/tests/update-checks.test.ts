import { afterEach, expect, it, vi } from 'vitest';
import {
  UpdateCheck,
  pollUpdates,
  UPDATE_CHECK_INTERVAL_MS,
  canPresentUpdate,
} from '../src/lib/update-checks';
afterEach(() => vi.useRealTimers());
it('shares pending checks across settings, manual and periodic callers then allows a fresh check', async () => {
  let resolve!: (value: string) => void;
  const check = vi.fn(
    () =>
      new Promise<string>((done) => {
        resolve = done;
      }),
  );
  const updates = new UpdateCheck<string>();
  const first = updates.run(check);
  expect(updates.run(check)).toBe(first);
  expect(updates.run(check)).toBe(first);
  await Promise.resolve();
  expect(check).toHaveBeenCalledOnce();
  expect(updates.running).toBe(true);
  resolve('alpha21');
  expect(await first).toBe('alpha21');
  expect(updates.running).toBe(false);
  const next = updates.run(check);
  await Promise.resolve();
  expect(check).toHaveBeenCalledTimes(2);
  resolve('alpha21');
  await next;
});
it('failed requests do not lock out retries', async () => {
  const updates = new UpdateCheck();
  await expect(
    updates.run(async () => {
      throw Error('offline');
    }),
  ).rejects.toThrow('offline');
  expect(updates.running).toBe(false);
  await expect(updates.run(async () => 'current')).resolves.toBe('current');
});
it('polls every 30 minutes, respects live opt-out and stops on teardown', async () => {
  vi.useFakeTimers();
  let enabled = true;
  const check = vi.fn(async () => null);
  const stop = pollUpdates(check, () => enabled);
  expect(UPDATE_CHECK_INTERVAL_MS).toBe(1800000);
  await vi.advanceTimersByTimeAsync(UPDATE_CHECK_INTERVAL_MS - 1);
  expect(check).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(check).toHaveBeenCalledOnce();
  enabled = false;
  await vi.advanceTimersByTimeAsync(UPDATE_CHECK_INTERVAL_MS);
  expect(check).toHaveBeenCalledOnce();
  enabled = true;
  await vi.advanceTimersByTimeAsync(UPDATE_CHECK_INTERVAL_MS);
  expect(check).toHaveBeenCalledTimes(2);
  stop();
  await vi.advanceTimersByTimeAsync(UPDATE_CHECK_INTERVAL_MS);
  expect(check).toHaveBeenCalledTimes(2);
});
it('timer catches failures without creating unhandled rejections', async () => {
  vi.useFakeTimers();
  const stop = pollUpdates(
    async () => {
      throw Error('offline');
    },
    () => true,
  );
  await vi.advanceTimersByTimeAsync(UPDATE_CHECK_INTERVAL_MS);
  stop();
  expect(vi.getTimerCount()).toBe(0);
});

it('defers update presentation during dialogs, capture and dragging', () => {
  for (const modal of ['command', 'broadcast', 'settings', 'launch'])
    expect(canPresentUpdate(modal, 'idle', false)).toBe(false);
  for (const capture of ['loading', 'listening', 'finishing', 'transcribing'])
    expect(canPresentUpdate('', capture, false)).toBe(false);
  expect(canPresentUpdate('', 'idle', true)).toBe(false);
  expect(canPresentUpdate('', 'idle', false)).toBe(true);
});
