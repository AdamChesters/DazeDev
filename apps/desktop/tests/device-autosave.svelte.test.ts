// @vitest-environment jsdom
import { flushSync } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEVICE_SAVE_DELAY,
  DeviceStore,
  PROFILE_SAVE_DELAY,
} from '../src/lib/state/device-store.svelte';
import {
  blankProfile,
  defaults,
  type Device,
  type Profile,
  type Workspace,
} from '../src/lib/types';

const workspace = (id: string): Workspace => ({
  id,
  hostId: 'host',
  name: id,
  panes: [],
  layout: { kind: 'pane', id: '' },
  focused: '',
});

function setup() {
  const state = $state<{ device: Device; profiles: Record<string, Profile> }>({
    device: defaults(),
    profiles: { host: { ...blankProfile(), revision: 1, workspaces: [workspace('w1')] } },
  });
  let enabled = false;
  const saveDevice = vi.fn(async (_value: unknown) => undefined);
  const saveProfile = vi.fn(async (_hostId: string, profile: Profile) => ({
    ...profile,
    revision: profile.revision + 1,
  }));
  const store = new DeviceStore({
    device: () => state.device,
    profiles: () => state.profiles,
    enabled: () => enabled,
    connected: () => true,
    saveDevice,
    saveProfile,
    ondevicefail: vi.fn(),
    onprofilefail: vi.fn(),
  });
  flushSync();
  enabled = true;
  return { state, store, saveDevice, saveProfile };
}

describe('DeviceStore automatic saving', () => {
  let stop: (() => void) | undefined;
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    stop?.();
    vi.useRealTimers();
  });

  it('saves a device change once, and skips a change back to the saved value', async () => {
    const { state, store, saveDevice } = setup();
    stop = () => store.destroy();
    state.device.theme = 'green';
    flushSync();
    state.device.terminal = { ...state.device.terminal!, fontSize: 18 };
    flushSync();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(saveDevice).toHaveBeenCalledTimes(1);
    expect(saveDevice.mock.calls[0][0]).toMatchObject({ theme: 'green' });
    state.device.theme = 'blue';
    flushSync();
    state.device.theme = 'green';
    flushSync();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(saveDevice).toHaveBeenCalledTimes(1);
  });

  it('does not write the device file back right after loading it', async () => {
    const { state, store, saveDevice } = setup();
    stop = () => store.destroy();
    state.device = { ...defaults(), theme: 'charcoal' };
    store.loaded();
    flushSync();
    await vi.advanceTimersByTimeAsync(DEVICE_SAVE_DELAY);
    expect(saveDevice).not.toHaveBeenCalled();
  });

  it('saves a changed host profile but not a focus change or a fresh host copy', async () => {
    const { state, store, saveProfile } = setup();
    stop = () => store.destroy();
    state.profiles.host.workspaces[0].focused = 'pane';
    flushSync();
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    expect(saveProfile).not.toHaveBeenCalled();

    state.profiles.host.workspaces[0].name = 'Renamed';
    flushSync();
    expect(store.saveState.host).toBe('Pending');
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    await store.settled('host');
    expect(saveProfile).toHaveBeenCalledTimes(1);
    // The new revision from the host does not count as a change.
    expect(state.profiles.host.revision).toBe(2);
    flushSync();
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    expect(saveProfile).toHaveBeenCalledTimes(1);

    state.profiles.host = { ...blankProfile(), revision: 9, workspaces: [workspace('w2')] };
    store.adopt('host');
    flushSync();
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    expect(saveProfile).toHaveBeenCalledTimes(1);
  });

  it('saves a profile that appears after start', async () => {
    const { state, store, saveProfile } = setup();
    stop = () => store.destroy();
    state.profiles.other = { ...blankProfile(), revision: 1, workspaces: [workspace('w3')] };
    flushSync();
    await vi.advanceTimersByTimeAsync(PROFILE_SAVE_DELAY);
    await store.settled('other');
    expect(saveProfile.mock.calls.map(([hostId]) => hostId)).toEqual(['other']);
  });
});
