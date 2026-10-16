import { call, rpc } from '../bridge';
import { migrateClipboardPolicy } from '../device-state';
import { refreshHostIdentity } from '../host-identity';
import { rebindOwner } from '../layout';
import { MixedHostRecovery, type HostRecovery } from '../mixed-host-recovery';
import { defaultPrompt, renderPrompt, type PromptValues } from '../prompt';
import { terminalDefaults, terminals } from '../terminals';
import {
  blankProfile,
  defaults,
  uid,
  type Device,
  type Host,
  type Inventory,
  type Pane,
  type Profile,
  type Session,
} from '../types';
import { HOSTLESS } from '../workspace-groups';
import type { App } from './app';

export type DeviceFile = Device & { recoveryNotice?: string; recovery?: Record<string, Profile> };

// Host profiles: loading them when a host joins, recovery copies and
// conflicts, inventories, connecting and removing hosts, and host preferences.
export class Profiles {
  recoveryPrompts = $state<HostRecovery[]>([]);
  connectionTarget = $state<Host | null>(null);
  deleteHostTarget = $state<Host | null>(null);
  /** Host whose preferences Settings shows. */
  settingsHost = $state('');
  roots = $state('');
  guidance = $state('');
  promptTemplate = $state(defaultPrompt);
  #mixedRecovery = new MixedHostRecovery();
  #app: App;

  constructor(app: App) {
    this.#app = app;
  }

  /** Applies a loaded device file. */
  load(data: DeviceFile) {
    const ui = this.#app.ui;
    if (data.recoveryNotice) ui.notice = data.recoveryNotice;
    ui.device = migrateClipboardPolicy({
      ...defaults(),
      ...data,
      voice: { ...defaults().voice, ...data.voice },
      highlightMotion:
        data.highlightMotion === 'static'
          ? 'static'
          : data.motionPreferenceVersion
            ? data.highlightMotion
            : 'animate',
      motionPreferenceVersion: 1,
      inputMode: data.inputMode || 'toggle',
      terminal: { ...terminalDefaults, ...data.terminal },
    });
    this.#app.deviceStore.recovery = data.recovery || {};
    ui.profiles = {
      ...this.#app.deviceStore.recovery,
      [HOSTLESS]: ui.device.localWorkspaces || blankProfile(),
    };
    for (const hostId in ui.profiles) this.#app.deviceStore.adopt(hostId);
    this.#app.deviceStore.loaded();
  }

  #forget(hostId: string) {
    this.#mixedRecovery.answer(hostId);
    this.recoveryPrompts = this.recoveryPrompts.filter((p) => p.hostId !== hostId);
  }

  #resume(hostId: string) {
    for (const w of this.#app.panes.workspaces)
      for (const p of w.panes) if (p.hostId === hostId) terminals.get(p.id)?.resume?.();
  }

  #offerMixedRecovery(owner: string) {
    const { ui, net } = this.#app;
    this.recoveryPrompts = [
      ...this.recoveryPrompts,
      ...this.#mixedRecovery.plan(
        owner,
        ui.profiles[owner]?.workspaces || [],
        ui.device.hosts,
        new Set(
          ui.device.hosts
            .filter((h) => ['Connected', 'Connecting'].includes(net.connections[h.id]))
            .map((h) => h.id),
        ),
      ),
    ];
  }

  answerRecovery = async (prompt: HostRecovery, accept: boolean) => {
    const { ui, net } = this.#app;
    this.#forget(prompt.hostId);
    if (!accept) return;
    const host = ui.host(prompt.hostId);
    if (host && !['Connected', 'Connecting'].includes(net.connections[host.id])) {
      this.#resume(host.id);
      await net.connect(host);
    }
  };

  showHostConnection = (p: Pane) => {
    this.connectionTarget = this.#app.ui.host(p.hostId) || null;
    if (this.connectionTarget) this.#app.ui.modal = 'terminal-host-connection';
  };

  disconnectHostTerminals = async (hostId: string) => {
    this.#forget(hostId);
    await this.#app.net.disconnect(hostId);
    await Promise.all(
      this.#app.panes.workspaces
        .flatMap((w) => w.panes)
        .filter((p) => p.hostId === hostId)
        .map((p) => terminals.get(p.id)?.disconnect?.()),
    );
    this.#app.ui.modal = '';
  };

  connectAllHosts = async () => {
    const { ui, net } = this.#app;
    await Promise.allSettled(
      ui.device.hosts
        .filter(
          (host) =>
            net.connections[host.id] !== 'Connected' && net.connections[host.id] !== 'Connecting',
        )
        .map(async (host) => {
          this.#forget(host.id);
          this.#resume(host.id);
          await net.connect(host);
        }),
    );
  };

  connectHostTerminals = async (host: Host) => {
    this.#resume(host.id);
    this.#app.ui.modal = '';
    if (this.#app.net.connections[host.id] !== 'Connected') await this.#app.net.connect(host);
  };

  refresh = async (id = this.#app.ui.device.activeHost) => {
    const { ui, net } = this.#app;
    const attempt = net.attempts.get(id);
    const result = await rpc<Inventory>(id, 'inventory');
    if (attempt !== net.attempts.get(id) || !net.reconnect.has(id)) return;
    ui.inventories[id] = result;
    // Repository scan notes stay in the picker, not the global title bar.
    if (ui.profiles[id])
      ui.profiles[id].discovery = {
        observedAt: Date.now(),
        machine: result.machine,
        user: result.user,
        agents: result.agents.map((a) => ({
          id: a.id,
          path: a.path,
          version: a.version,
          models: a.models,
        })),
        repos: result.repos.map((r) => ({ path: r.path, branch: r.branch, dirty: r.dirty })),
      };
    const joinedHost = ui.host(id);
    // Changed host identities in any profile save automatically.
    if (joinedHost) refreshHostIdentity(joinedHost, result, Object.values(ui.profiles));
    if (id === ui.device.activeHost) ui.sessions = result.sessions;
  };

  replaceProfile(hostId: string, profile: Profile) {
    const ui = this.#app.ui;
    const previous = new Set(ui.profiles[hostId]?.workspaces.map((w) => w.id) || []);
    const retained = new Set(profile.workspaces.map((w) => w.id));
    const displaced = this.#app.panes.viewers.filter(
      (v) => previous.has(v.workspaceId) && !retained.has(v.workspaceId),
    );
    if (displaced.length) {
      profile = { ...profile, workspaces: [...profile.workspaces] };
      let destination = profile.workspaces[0];
      if (!destination) {
        destination = {
          id: uid(),
          hostId,
          name: 'Open viewers',
          panes: [],
          layout: { kind: 'pane', id: '' },
          focused: '',
        };
        profile.workspaces.push(destination);
      }
      for (const viewer of displaced) viewer.workspaceId = destination.id;
      if (previous.has(ui.device.activeWorkspace) && !retained.has(ui.device.activeWorkspace))
        ui.device.activeWorkspace = destination.id;
      ui.notice =
        'The host workspace changed. Your open viewers and edits were kept in ' +
        destination.name +
        '.';
    }
    ui.profiles[hostId] = profile;
    this.#app.deviceStore.adopt(hostId);
  }

  /** The host's management channel is ready: load its profile and inventory. */
  joined = async (id: string, attempt = this.#app.net.attempts.get(id)) => {
    const { ui, net, deviceStore } = this.#app;
    const currentAttempt = () => net.attempts.get(id) === attempt && net.reconnect.has(id);
    if (!currentAttempt()) return;
    try {
      let profile: Profile;
      try {
        profile = rebindOwner(await rpc<Profile>(id, 'profile_load'), id);
        deviceStore.blocked[id] = false;
      } catch (e) {
        deviceStore.blocked[id] = true;
        profile = ui.profiles[id] || blankProfile();
        ui.error = `Host configuration unavailable: ${e}. Changes will be saved on this device.`;
        deviceStore.saveState[id] = 'Saved on this device';
      }
      if (!currentAttempt()) return;
      const recovery = deviceStore.recovery;
      if (recovery[id]) {
        if (recovery[id].revision === profile.revision) {
          this.replaceProfile(id, recovery[id]);
          // The recovery copy holds changes the host has not seen yet.
          deviceStore.saved(id);
        } else {
          this.replaceProfile(id, recovery[id]);
          deviceStore.saveState[id] = 'Conflict';
          ui.error =
            'This host has newer workspace changes. Your local recovery copy is preserved. Reload the host profile in settings to use the remote version.';
        }
      } else this.replaceProfile(id, profile);
      net.connections[id] = 'Connected';
      for (const w of ui.profiles[id].workspaces) {
        const focused = ui.device.localFocus?.[w.id];
        if (focused && w.panes.some((p) => p.id === focused)) w.focused = focused;
      }
      await this.refresh(id);
      if (!currentAttempt()) return;
      if (
        !ui.device.activeWorkspace ||
        !this.#app.panes.workspaces.some((w) => w.id === ui.device.activeWorkspace)
      )
        ui.device.activeWorkspace = profile.workspaces[0]?.id || '';
      this.#offerMixedRecovery(id);
    } catch (e) {
      ui.fail(e);
    }
  };

  /** Fills the Settings host preference fields for the active host. */
  openSettings() {
    const ui = this.#app.ui;
    this.roots = (ui.profiles[ui.device.activeHost]?.preferences.roots || []).join('\n');
    this.settingsHost = ui.device.activeHost;
    this.guidance = ui.profiles[this.settingsHost]?.preferences.guidance || '';
    this.promptTemplate =
      ui.profiles[this.settingsHost]?.preferences.promptTemplate ?? defaultPrompt;
  }

  saveHostPreferences = () => {
    const ui = this.#app.ui;
    const preferences = ui.profiles[this.settingsHost].preferences;
    preferences.roots = this.roots
      .split('\n')
      .map((x) => x.trim())
      .filter(Boolean);
    preferences.guidance = this.guidance;
    preferences.promptTemplate = this.promptTemplate;
    ui.notice = 'Host preferences queued for saving.';
  };

  reloadHostProfile = async () => {
    const { ui, deviceStore } = this.#app;
    const recovery = deviceStore.recovery;
    const local = $state.snapshot(ui.profiles[this.settingsHost] || recovery[this.settingsHost]);
    if (local) {
      (ui.device.profileBackups ||= {})[this.settingsHost] = [
        local,
        ...(ui.device.profileBackups?.[this.settingsHost] || []),
      ].slice(0, 3);
      await deviceStore.saveNow();
    }
    const backup = JSON.stringify(local, null, 2);
    const url = URL.createObjectURL(new Blob([backup], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'dazedev-workspace-recovery.json';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    this.replaceProfile(
      this.settingsHost,
      rebindOwner(await rpc<Profile>(this.settingsHost, 'profile_load'), this.settingsHost),
    );
    deviceStore.restored(this.settingsHost);
  };

  deleteHost = async (h: Host, confirmed = false) => {
    const { ui, net, deviceStore, panes, dictation } = this.#app;
    const device = ui.device;
    if (!confirmed && h.kind === 'powershell') {
      if (net.connections[h.id] !== 'Connected') await net.connect(h);
      const sessions = await rpc<Session[]>(h.id, 'sessions');
      if (
        panes.offerNativeClose(
          sessions.map((s) => ({ hostId: h.id, sessionId: s.id }) as Pane),
          () => this.deleteHost(h, true),
        )
      )
        return;
    }
    const owned = new Set(ui.profiles[h.id]?.workspaces.map((w) => w.id) || []);
    if (panes.viewers.some((v) => v.locked && (owned.has(v.workspaceId) || v.pane.hostId === h.id)))
      throw new Error('Save or discard viewer edits before removing this host.');
    panes.viewers = panes.viewers.filter(
      (v) => !owned.has(v.workspaceId) && v.pane.hostId !== h.id,
    );
    deviceStore.cancel(h.id);
    if (
      panes.workspaces.some((w) =>
        w.panes.some((p) => p.id === dictation.target && p.hostId === h.id),
      ) &&
      dictation.status !== 'idle'
    )
      await dictation.cancel();
    for (const p of panes.workspaces.flatMap((w) => w.panes).filter((p) => p.hostId === h.id))
      await call('terminal_close', { id: p.id });
    await net.disconnect(h.id);
    await deviceStore.settled(h.id);
    const removedActive = ui.profiles[h.id]?.workspaces.some(
      (w) => w.id === device.activeWorkspace,
    );
    delete ui.profiles[h.id];
    deviceStore.forget(h.id);
    delete ui.inventories[h.id];
    delete net.connections[h.id];
    delete net.logs[h.id];
    device.hosts = device.hosts.filter((x) => x.id !== h.id);
    if (device.collapsedHosts) delete device.collapsedHosts[h.id];
    if (removedActive) device.activeWorkspace = panes.workspaces[0]?.id || '';
    if (device.activeHost === h.id)
      device.activeHost =
        panes.workspaces.find((w) => w.id === device.activeWorkspace)?.hostId ||
        device.hosts[0]?.id ||
        '';
    ui.maximize = '';
    ui.modal = '';
    this.deleteHostTarget = null;
    ui.notice = 'Host removed from this device. Remote workspaces and tmux sessions are unchanged.';
  };

  promptValues = (p?: Pane): PromptValues => {
    const ui = this.#app.ui;
    const hostId = p?.hostId || this.settingsHost || ui.device.activeHost;
    const h = ui.host(hostId);
    return {
      host: h?.name || 'this host',
      address: h?.address || 'local',
      user: h?.user || ui.inventories[hostId]?.user || 'current user',
      repo: p?.repo || '/path/to/repository',
      identity: p?.identity || 'the existing host identity',
      agent: p?.agent || 'codex',
      model: p?.model || 'agent default',
      effort: p?.effort || 'agent default',
      guidance: p ? ui.profiles[hostId]?.preferences.guidance || '' : this.guidance,
    };
  };

  promptFor = (p: Pane) =>
    renderPrompt(
      this.#app.ui.profiles[p.hostId]?.preferences.promptTemplate ?? defaultPrompt,
      this.promptValues(p),
    );
}
