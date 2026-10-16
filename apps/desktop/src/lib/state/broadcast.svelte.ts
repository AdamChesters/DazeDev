import { tick } from 'svelte';
import {
  broadcastRevision,
  createBroadcastDelivery,
  snapshotBroadcast,
  type BroadcastSnapshot,
} from '../broadcast';
import { rpc } from '../bridge';
import { unknownStatus, type PaneStatus } from '../pane-status';
import { terminals } from '../terminals';
import type { App } from './app';

// Broadcast: one message typed or dictated into many terminals, after review.
export class Broadcast {
  text = $state('');
  allHosts = $state(true);
  allWorkspaces = $state(true);
  host = $state('');
  workspace = $state('');
  hostName = $state('');
  workspaceName = $state('');
  snapshot = $state<BroadcastSnapshot | null>(null);
  busy = $state(false);
  result = $state('');
  #app: App;

  constructor(app: App) {
    this.#app = app;
    // A changed message or scope needs a new review.
    $effect(() => {
      void this.text;
      void this.allHosts;
      void this.allWorkspaces;
      this.snapshot = null;
    });
  }

  focusInput = () => {
    if (this.#app.ui.modal === 'broadcast' && this.#app.appHasFocus())
      document.querySelector<HTMLTextAreaElement>('[data-modal="broadcast"] textarea')?.focus();
  };

  open = () => {
    const { ui, panes, dictation } = this.#app;
    if (dictation.status !== 'idle') return;
    this.text = '';
    this.allHosts = true;
    this.allWorkspaces = true;
    this.host = ui.device.activeHost;
    this.workspace = panes.current?.id || '';
    this.hostName = ui.host(this.host)?.name || 'current host';
    this.workspaceName = panes.current?.name || 'current workspace';
    this.snapshot = null;
    this.result = '';
    ui.modal = 'broadcast';
    void tick().then(this.focusInput);
  };

  #collect() {
    const { ui, panes, net } = this.#app;
    const inputs = panes.workspaces
      .filter((w) => this.allWorkspaces || w.id === this.workspace)
      .flatMap((w) =>
        w.panes
          .filter((p) => this.allHosts || p.hostId === this.host)
          .map((p) => {
            const host = ui.host(p.hostId),
              inventory = ui.inventories[p.hostId];
            return {
              id: p.id,
              hostId: p.hostId,
              sourceHost: inventory?.machine
                ? JSON.stringify([inventory.machine, inventory.user, host?.distro, host?.socket])
                : p.hostId,
              label: `${host?.name || p.hostId} / ${w.name} / ${p.name}`,
              connected: !!terminals.get(p.id)?.context(),
            };
          }),
      );
    return snapshotBroadcast(inputs, terminals, (input, terminal) =>
      net.connections[input.hostId] === 'Connected'
        ? rpc<PaneStatus>(input.hostId, 'terminal_status', { pid: terminal.processId() })
        : Promise.resolve(unknownStatus),
    );
  }

  review = async () => {
    if (this.busy || this.#app.dictation.status !== 'idle' || this.result) return;
    this.busy = true;
    try {
      this.snapshot = await this.#collect();
    } finally {
      this.busy = false;
    }
  };

  send = async () => {
    if (this.busy || this.#app.dictation.status !== 'idle' || this.result || !this.snapshot) return;
    this.busy = true;
    const reviewed = this.snapshot,
      message = this.text;
    try {
      const snapshot = await this.#collect();
      this.snapshot = snapshot;
      if (broadcastRevision(snapshot) !== broadcastRevision(reviewed)) {
        this.#app.ui.notice =
          'Broadcast recipients changed. Review the updated list and confirm again.';
        return;
      }
      const result = await createBroadcastDelivery(snapshot, message)(terminals);
      this.result = `Sent to ${result.delivered.length} terminal sessions. ${snapshot.skipped.length} panes skipped; ${snapshot.mirrors} mirrors deduplicated. No delivery will be retried.`;
      if (result.failed.length)
        this.result += `\nDelivery failed or is uncertain for ${result.failed.length} sessions. Some text may already have arrived:\n${result.failed.join('\n')}`;
    } finally {
      this.busy = false;
    }
  };
}
