import { beforeEach, describe, expect, it, vi } from 'vitest';

const invoke = vi.fn();
vi.mock('@tauri-apps/api/core', () => ({
  invoke: (...args: unknown[]) => invoke(...args),
  isTauri: () => true,
}));

import { loadDevice } from '../src/lib/ipc';

describe('loadDevice', () => {
  beforeEach(() => invoke.mockReset());

  it('returns null on first run, before device.json exists', async () => {
    invoke.mockResolvedValue(null);
    await expect(loadDevice()).resolves.toBeNull();
  });

  it('still checks a saved device file', async () => {
    invoke.mockResolvedValue({ hosts: [] });
    await expect(loadDevice()).resolves.toEqual({ hosts: [] });
    invoke.mockResolvedValue('not a device');
    await expect(loadDevice()).rejects.toThrow('The device_load reply was malformed');
  });
});
