import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEVICE_SAVE_DELAY,
  DeviceStore,
  PROFILE_SAVE_DELAY,
  type DeviceStoreDeps,
} from '../src/lib/state/device-store.svelte';
import { blankProfile, defaults, type Device, type Profile } from '../src/lib/types';
import { HOSTLESS } from '../src/lib/workspace-groups';

function setup(overrides: Partial<DeviceStoreDeps> = {}) {
  const device: Device = defaults();
  const profiles: Record<string, Profile> = { host: { ...blankProfile(), revision: 1 } };
  const saveDevice = vi.fn(async (_value: unknown) => undefined);
  const saveProfile = vi.fn(async (_hostId: string, profile: Profile) => ({
    ...profile,
    revision: profile.revision + 1,
  }));
  const deps: DeviceStoreDeps = {
    device: () => device,
    profiles: () => profiles,
    enabled: () => true,
    connected: () => true,
    saveDevice,
    saveProfile,
    ondevicefail: vi.fn(),
    onprofilefail: vi.fn(),
    ...overrides,
  };
  return { store: new DeviceStore(deps), device, profiles, saveDevice, saveProfile, deps };
}

describe('DeviceStore', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('debounces device writes into one save', async () => {
    const { store, device, saveDevice } = setup();
    store.persist();
    device.theme = 'green';
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY - 1);
    expect(saveDevice).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(saveDevice).toHaveBeenCalledTimes(1);
    expect(saveDevice.mock.calls[0][0]).toMatchObject({ theme: 'green', recovery: {} });
  });

  it('skips a write when nothing changed since the last save', async () => {
    const { store, device, saveDevice } = setup();
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(saveDevice).toHaveBeenCalledTimes(1);
    device.sidebar = false;
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(saveDevice).toHaveBeenCalledTimes(2);
  });

  it('retries an unchanged value after a failed write', async () => {
    const saveDevice = vi.fn().mockRejectedValueOnce(new Error('disk')).mockResolvedValue(undefined);
    const { store, deps } = setup({ saveDevice });
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(deps.ondevicefail).toHaveBeenCalledTimes(1);
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(saveDevice).toHaveBeenCalledTimes(2);
  });

  it('does not write before mount', async () => {
    const { store, saveDevice } = setup({ enabled: () => false });
    store.persist();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY * 2);
    expect(saveDevice).not.toHaveBeenCalled();
  });

  it('saveNow cancels the pending debounced write', async () => {
    const { store, saveDevice } = setup();
    store.persist();
    await store.saveNow();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY * 2);
    expect(saveDevice).toHaveBeenCalledTimes(1);
  });

  it('saves a host profile and clears its recovery copy', async () => {
    const { store, profiles, saveProfile } = setup();
    store.saved('host');
    expect(store.saveState.host).toBe('Pending');
    expect(store.recovery.host).toBeDefined();
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    await store.settled('host');
    expect(saveProfile).toHaveBeenCalledTimes(1);
    expect(profiles.host.revision).toBe(2);
    expect(store.saveState.host).toBe('Saved');
    expect(store.recovery.host).toBeUndefined();
  });

  it('keeps changes on the device while the host is disconnected', async () => {
    const { store, saveProfile } = setup({ connected: () => false });
    store.saved('host');
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    await store.settled('host');
    expect(saveProfile).not.toHaveBeenCalled();
    expect(store.saveState.host).toBe('Saved on this device');
    expect(store.recovery.host).toBeDefined();
  });

  it('marks a conflict and reports the failure', async () => {
    const { store, deps } = setup({
      saveProfile: vi.fn().mockRejectedValue(new Error('CONFLICT')),
    });
    store.saved('host');
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    await store.settled('host');
    expect(store.saveState.host).toBe('Conflict');
    expect(deps.onprofilefail).toHaveBeenCalledTimes(1);
    store.saved('host');
    expect(store.saveState.host).toBe('Conflict');
  });

  it('stores hostless workspaces in the device file', () => {
    const { store, device, profiles } = setup();
    profiles[HOSTLESS] = { ...blankProfile(), revision: 7 };
    store.saved(HOSTLESS);
    expect(device.localWorkspaces?.revision).toBe(7);
    expect(store.recovery[HOSTLESS]).toBeUndefined();
  });

  it('cancel stops a queued profile write and forget drops host state', async () => {
    const { store, saveProfile } = setup();
    store.saved('host');
    store.cancel('host');
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY * 2);
    expect(saveProfile).not.toHaveBeenCalled();
    store.blocked.host = true;
    store.forget('host');
    expect(store.recovery.host).toBeUndefined();
    expect(store.saveState.host).toBeUndefined();
    expect(store.blocked.host).toBeUndefined();
    expect(store.settled('host')).toBeUndefined();
  });
});
