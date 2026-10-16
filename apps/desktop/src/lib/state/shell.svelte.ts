import { diagnostic } from '../diagnostics';
import type { PaneStatus } from '../pane-status';
import { terminalDefaults } from '../terminals';
import {
  defaults,
  uid,
  type Device,
  type Host,
  type Inventory,
  type Pane,
  type Profile,
  type Session,
} from '../types';

export type LaunchForm = {
  hostId: string;
  name: string;
  repo: string;
  identity: string;
  agent: string;
  model: string;
  effort: string;
  count: number;
  brief: boolean;
  exactName: boolean;
};

export function blankForm(hostId = '', name = '', exactName = false): LaunchForm {
  return {
    hostId,
    name,
    repo: '',
    identity: '',
    agent: 'shell',
    model: '',
    effort: '',
    count: 1,
    brief: true,
    exactName,
  };
}

export function hostDefaults(): Host {
  return {
    id: uid(),
    name: '',
    kind: 'ssh',
    address: '',
    user: '',
    key: '',
    jump: '',
    distro: '',
    socket: '',
  };
}

// State the whole shell shares: the device file, host profiles and
// inventories, the open dialog and the status messages.
export class Shell {
  device = $state<Device>({
    ...defaults(),
    inputMode: 'toggle',
    highlightMotion: 'animate',
    terminal: { ...terminalDefaults },
  });
  profiles = $state<Record<string, Profile>>({});
  inventories = $state<Record<string, Inventory>>({});
  terminalState = $state<Record<string, string>>({});
  paneStatus = $state<Record<string, PaneStatus>>({});
  terminalProgress = $state<Record<string, { state: number; value: number }>>({});
  modal = $state('');
  error = $state('');
  notice = $state('');
  busy = $state(false);
  maximize = $state('');
  search = $state('');
  sessions = $state<Session[]>([]);
  form = $state<LaunchForm>(blankForm());
  renameValue = $state('');
  renameTarget = $state<Pane | null>(null);
  destroyTarget = $state<(Session & { hostId?: string }) | null>(null);
  paneMenu = $state<{ pane: Pane; x: number; y: number; selection?: string } | null>(null);
  colorPane = $state<Pane | null>(null);
  dictionarySelection = $state<string | null>(null);
  /** Pane to refocus when the dialog it opened closes. */
  terminalReturnFocus = '';
  /** False before mount and after teardown. */
  mounted = false;
  #pendingTasks = 0;

  fail = (e: unknown) => {
    this.error = String(e);
    diagnostic('operation.failed');
  };

  task = async (fn: () => Promise<void>) => {
    this.error = '';
    this.#pendingTasks++;
    this.busy = true;
    try {
      await fn();
    } catch (e) {
      this.fail(e);
    } finally {
      this.busy = --this.#pendingTasks > 0;
    }
  };

  host(hostId: string) {
    return this.device.hosts.find((h) => h.id === hostId);
  }

  nativePane = (p: Pick<Pane, 'hostId'>) => this.host(p.hostId)?.kind === 'powershell';
}
