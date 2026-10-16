import { untrack } from 'svelte';
import type { Device, Profile } from '../types';
import { HOSTLESS } from '../workspace-groups';

export interface DeviceStoreDeps {
  device: () => Device;
  profiles: () => Record<string, Profile>;
  /** False until the device file has loaded and after teardown; nothing is written then. */
  enabled: () => boolean;
  connected: (hostId: string) => boolean;
  saveDevice: (value: unknown) => Promise<unknown>;
  saveProfile: (hostId: string, profile: Profile) => Promise<Profile>;
  /** A failed device file write. */
  ondevicefail: (e: unknown) => void;
  /** A failed host profile write. */
  onprofilefail: (e: unknown) => void;
}

export const DEVICE_SAVE_DELAY = 180;
export const PROFILE_SAVE_DELAY = 250;

/**
 * What decides whether a host profile changed. Focus, host discovery and the
 * revision change without being saved on their own, as before automatic saving.
 */
function profilePrint(profile: Profile) {
  return JSON.stringify({
    ...profile,
    revision: undefined,
    discovery: undefined,
    workspaces: profile.workspaces.map((w) => ({ ...w, focused: undefined })),
  });
}

// Device file and host profile saving. Both save automatically when their
// state changes: device writes are debounced and skipped when nothing changed;
// profile writes queue per host.
export class DeviceStore {
  /** Unsaved host profiles kept in the device file until the host accepts them. */
  recovery: Record<string, Profile> = {};
  /** Hosts whose profile could not load; their changes stay on this device. */
  blocked: Record<string, boolean> = {};
  saveState = $state<Record<string, string>>({});
  #deps: DeviceStoreDeps;
  #deviceTimer: ReturnType<typeof setTimeout> | undefined;
  #lastDeviceSave = '';
  #revisionTimers: Record<string, ReturnType<typeof setTimeout>> = {};
  #saveChains: Record<string, Promise<void>> = {};
  #changeSeq: Record<string, number> = {};
  /** Last seen profilePrint per host. */
  #prints: Record<string, string> = {};
  #stop: () => void;

  constructor(deps: DeviceStoreDeps) {
    this.#deps = deps;
    this.#stop = $effect.root(() => {
      // Reading the whole snapshot makes any device setting change re-run this.
      $effect(() => {
        $state.snapshot(deps.device());
        untrack(() => this.persist());
      });
      let first = true;
      $effect(() => {
        const profiles = $state.snapshot(deps.profiles()) as Record<string, Profile>;
        untrack(() => this.#profilesChanged(profiles, first));
        first = false;
      });
    });
  }

  /** Stops automatic saving. */
  destroy() {
    this.#stop();
  }

  #profilesChanged(profiles: Record<string, Profile>, first: boolean) {
    for (const [hostId, profile] of Object.entries(profiles)) {
      const print = profilePrint(profile);
      const before = this.#prints[hostId];
      this.#prints[hostId] = print;
      if (before === undefined ? !first : before !== print) this.saved(hostId);
    }
  }

  /** The host's profile was just loaded, not changed here: do not save it back. */
  adopt(hostId: string) {
    const profile = this.#deps.profiles()[hostId];
    if (profile) this.#prints[hostId] = profilePrint($state.snapshot(profile) as Profile);
  }

  value() {
    return { ...$state.snapshot(this.#deps.device()), recovery: this.recovery };
  }

  // Debounced; skips the write when nothing in the saved file changed.
  persist() {
    if (!this.#deps.enabled()) return;
    clearTimeout(this.#deviceTimer);
    this.#deviceTimer = setTimeout(() => {
      const value = this.value();
      const text = JSON.stringify(value);
      if (text === this.#lastDeviceSave) return;
      void this.#deps.saveDevice(value).then(() => {
        this.#lastDeviceSave = text;
      }, this.#deps.ondevicefail);
    }, DEVICE_SAVE_DELAY);
  }

  /** The device file was just loaded: treat its current value as saved. */
  loaded() {
    this.#lastDeviceSave = JSON.stringify(this.value());
  }

  /** Cancels a pending debounced write and writes now. */
  async saveNow() {
    clearTimeout(this.#deviceTimer);
    const value = this.value();
    await this.#deps.saveDevice(value);
    this.#lastDeviceSave = JSON.stringify(value);
  }

  saved(hostId: string) {
    const profiles = this.#deps.profiles();
    if (hostId === HOSTLESS) {
      this.#deps.device().localWorkspaces = $state.snapshot(profiles[HOSTLESS]);
      return;
    }
    this.recovery[hostId] = $state.snapshot(profiles[hostId]);
    this.#changeSeq[hostId] = (this.#changeSeq[hostId] || 0) + 1;
    if (this.saveState[hostId] !== 'Conflict') this.saveState[hostId] = 'Pending';
    this.persist();
    clearTimeout(this.#revisionTimers[hostId]);
    this.#revisionTimers[hostId] = setTimeout(() => this.flush(hostId), PROFILE_SAVE_DELAY);
  }

  flush(hostId: string) {
    this.#saveChains[hostId] = (this.#saveChains[hostId] || Promise.resolve()).then(async () => {
      if (!this.#deps.connected(hostId) || this.blocked[hostId]) {
        this.saveState[hostId] = 'Saved on this device';
        return;
      }
      if (this.saveState[hostId] === 'Conflict') return;
      const profiles = this.#deps.profiles();
      const snapshot = $state.snapshot(profiles[hostId]);
      const seq = this.#changeSeq[hostId];
      try {
        const result = await this.#deps.saveProfile(hostId, snapshot);
        profiles[hostId].revision = result.revision;
        if (this.#changeSeq[hostId] === seq) {
          delete this.recovery[hostId];
          this.saveState[hostId] = 'Saved';
        } else {
          this.recovery[hostId] = $state.snapshot(profiles[hostId]);
          this.saveState[hostId] = 'Pending';
        }
        this.persist();
      } catch (e) {
        this.saveState[hostId] = String(e).includes('CONFLICT') ? 'Conflict' : 'Save failed';
        this.#deps.onprofilefail(e);
      }
    });
  }

  /** Stops a queued profile write that has not started. */
  cancel(hostId: string) {
    clearTimeout(this.#revisionTimers[hostId]);
  }

  /** Resolves when the host's started profile writes finish. */
  settled(hostId: string) {
    return this.#saveChains[hostId];
  }

  /** Drops all save state for a removed host. */
  forget(hostId: string) {
    delete this.recovery[hostId];
    delete this.saveState[hostId];
    delete this.blocked[hostId];
    delete this.#changeSeq[hostId];
    delete this.#saveChains[hostId];
    delete this.#prints[hostId];
    this.persist();
  }

  /** The host's own profile replaced the local copy: drop the recovery copy. */
  restored(hostId: string) {
    this.blocked[hostId] = false;
    delete this.recovery[hostId];
    this.saveState[hostId] = 'Restored';
    this.persist();
  }
}
