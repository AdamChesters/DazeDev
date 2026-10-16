import { call, rpc } from '../bridge';
import { balanced, ids, split, swap, tile } from '../layout';
import { terminals } from '../terminals';
import { uid, type Pane, type Session, type Workspace } from '../types';
import type { App } from './app';
import { blankForm } from './shell.svelte';

// Starting terminals and sessions: the launch dialog, the tmux/PowerShell
// session picker, agents, renaming and destroying sessions, and cloning repos.
export class Sessions {
  agentTarget = $state<Pane | null>(null);
  /** Pane the session picker replaces, or null for a new workspace. */
  sessionPane = $state<Pane | null>(null);
  sessionHost = $state('');
  sessionStep = $state(0);
  nameError = $state('');
  launchAxis = $state<'row' | 'column' | null>(null);
  launchFromCommand = $state(false);
  launchChooseRepo = $state(false);
  githubRepos = $state<{ full_name: string; description: string }[]>([]);
  #sessionOperation = '';
  #launchWorkspaceId = '';
  #acknowledgedRepo = '';
  #app: App;

  inv = $derived.by(() => {
    const ui = this.#app.ui;
    return ui.inventories[ui.form.hostId || ui.device.activeHost];
  });
  selectedAgent = $derived.by(() => this.inv?.agents.find((a) => a.id === this.#app.ui.form.agent));

  constructor(app: App) {
    this.#app = app;
  }

  agentPicker = async (p: Pane | null = null) => {
    const ui = this.#app.ui;
    this.agentTarget = p;
    if (p) ui.device.activeHost = p.hostId;
    await this.#app.profiles.refresh(p?.hostId || ui.device.activeHost);
    ui.modal = 'agents';
  };

  agentMenu = async (p: Pane, effort = false) => {
    if (!['codex', 'claude', 'gemini', 'opencode'].includes(p.agent)) return;
    const terminal = terminals.get(p.id);
    const expectedContext = terminal?.context();
    if (!terminal || !expectedContext) throw new Error('Wait for the terminal to be ready.');
    const actual = await rpc<string>(p.hostId, 'session_agent', {
      id: p.sessionId,
      stamp: p.stamp,
    });
    if (actual !== p.agent) {
      p.agent = actual;
      const owner = this.#app.panes.owner(p.id);
      if (owner) this.#app.deviceStore.saved(owner.hostId);
      throw new Error(
        'The agent in this terminal changed. Refresh its controls before choosing a model.',
      );
    }
    await terminal.paste(
      effort && p.agent === 'claude' ? '/effort' : p.agent === 'opencode' ? '/models' : '/model',
      true,
      expectedContext,
      'command',
    );
    terminals.get(p.id)?.focus?.();
  };

  startAgentFromPicker = async (agent: string) => {
    const ui = this.#app.ui;
    this.sessionPane = this.agentTarget;
    this.sessionHost = this.agentTarget?.hostId || ui.device.activeHost;
    await this.startSession();
    ui.form.agent = agent;
    ui.form.name = `${agent}-${Date.now().toString(36)}`;
    await this.createSession();
  };

  showLaunch = (axis: 'row' | 'column' | null = null) => {
    const { ui, panes } = this.#app;
    this.launchFromCommand = false;
    this.launchChooseRepo = false;
    this.#launchWorkspaceId = axis ? panes.current?.id || '' : '';
    this.launchAxis = axis;
    const targetHost = axis
      ? panes.activePane?.hostId || ui.device.activeHost
      : ui.device.activeHost;
    ui.form = blankForm(targetHost, axis ? '' : 'New workspace');
    ui.modal = 'launch';
  };

  launch = async () => {
    const { ui, panes, profiles, deviceStore, net } = this.#app;
    const form = ui.form;
    if (!Number.isSafeInteger(form.count) || form.count < 1)
      throw new Error('Choose a positive whole number of terminals.');
    if (!form.name.trim() && !this.launchAxis) throw new Error('Give this workspace a name.');
    if (net.connections[form.hostId] !== 'Connected')
      throw new Error('Connect to the selected host first.');
    const destination = this.launchAxis
      ? panes.workspaces.find((w) => w.id === this.#launchWorkspaceId)
      : undefined;
    if (this.launchAxis && !destination)
      throw new Error('The destination workspace was closed. Reopen the command.');
    const options = $state.snapshot(form);
    if (this.launchChooseRepo && !options.repo)
      throw new Error('Choose a repository before starting.');
    const managed =
      ui.host(options.hostId)?.kind === 'powershell' ||
      (this.launchFromCommand && (options.agent !== 'shell' || options.repo || options.exactName));
    if (
      managed &&
      options.agent !== 'shell' &&
      !ui.inventories[options.hostId]?.agents.find((a) => a.id === options.agent)?.path
    )
      throw new Error(`Install ${options.agent} on this host first using Install agents.`);
    if (managed && options.exactName && !/^[\w-]+$/.test(options.name))
      throw new Error('Use letters, numbers, underscores and hyphens for session names.');
    let workspace: Workspace;
    const newPanes = Array.from({ length: form.count }, (_, i): Pane => ({
      id: uid(),
      hostId: form.hostId,
      hostKey: ui.host(form.hostId)?.fingerprint,
      sessionId: '',
      shell: !managed,
      name: `Terminal ${i + 1}`,
      repo: '',
      identity: '',
      agent: 'shell',
      model: '',
      effort: '',
      operation: uid(),
    }));
    if (this.launchAxis && destination) {
      workspace = {
        ...$state.snapshot(destination),
        panes: [...destination.panes, ...newPanes],
        layout: panes.layoutFor(destination),
      };
      for (const p of newPanes)
        workspace.layout = ids(workspace.layout).filter(Boolean).length
          ? split(
              workspace.layout,
              ids(workspace.layout).includes(destination.focused)
                ? destination.focused
                : ids(workspace.layout)[0],
              p.id,
              this.launchAxis,
            )
          : { kind: 'pane', id: p.id };
      workspace.focused = newPanes[0].id;
      panes.updateWorkspace(workspace);
    } else {
      workspace = {
        id: uid(),
        hostId: form.hostId,
        name: form.name.trim() || 'Workspace',
        panes: newPanes,
        layout: balanced(
          newPanes.map((p) => p.id),
          panes.stageWidth,
        ),
        focused: newPanes[0].id,
      };
      ui.profiles[form.hostId].workspaces.push(workspace);
      deviceStore.saved(form.hostId);
    }
    panes.selectWorkspace(workspace);
    ui.modal = '';
    if (managed) {
      let started = 0;
      const failures: string[] = [];
      for (const pane of newPanes) {
        const target = panes.workspaces
          .find((w) => w.id === workspace.id)
          ?.panes.find((p) => p.id === pane.id);
        if (!target) continue;
        Object.assign(target, {
          name: options.exactName
            ? options.name
            : `${options.agent}-${target.operation.slice(0, 8)}`,
          repo: options.repo,
          identity: options.identity,
          agent: options.agent,
          model: options.model,
          effort: options.effort,
        });
        try {
          const session = await rpc<Session>(options.hostId, 'create', {
            ...target,
            name: options.exactName
              ? options.name
              : `${options.agent}-${target.operation.slice(0, 8)}`,
            exactName: options.exactName,
            path: options.repo,
            install: false,
            brief:
              options.repo && options.agent !== 'shell' && options.brief
                ? profiles.promptFor(target)
                : '',
          });
          Object.assign(target, {
            sessionId: session.id,
            stamp: session.stamp,
            name: session.name,
            error: undefined,
          });
          started++;
        } catch (e) {
          target.error = String(e);
          failures.push(String(e));
        }
        deviceStore.saved(workspace.hostId);
      }
      if (failures.length)
        throw new Error(
          `${started} of ${newPanes.length} sessions started. Failed terminals remain visible for retry. ${failures[0]}`,
        );
    }
  };

  retry = async (p: Pane) => {
    const s = await rpc<Session>(p.hostId, 'create', {
      ...p,
      path: p.repo,
      brief: p.agent !== 'shell' ? this.#app.profiles.promptFor(p) : '',
    });
    const owner = this.#app.panes.owner(p.id);
    if (owner) {
      owner.panes = owner.panes.map((x) =>
        x.id === p.id
          ? { ...x, sessionId: s.id, stamp: s.stamp, name: s.name, error: undefined }
          : x,
      );
      this.#app.deviceStore.saved(owner.hostId);
    }
  };

  refreshSessions = async (hostId: string) => {
    const ui = this.#app.ui;
    const result = await rpc<Session[]>(hostId, 'sessions');
    if (ui.inventories[hostId]) ui.inventories[hostId].sessions = result;
    ui.sessions = result;
  };

  showSessions = async (p: Pane | null = null) => {
    const { ui, net } = this.#app;
    this.sessionPane = p;
    this.sessionHost = p?.hostId || ui.device.activeHost;
    const h = ui.host(this.sessionHost);
    if (!h) throw new Error('Choose a host first.');
    if (net.connections[this.sessionHost] !== 'Connected') {
      void net.connect(h);
      return;
    }
    try {
      await this.refreshSessions(this.sessionHost);
    } catch (e) {
      net.connections[this.sessionHost] = 'Disconnected';
      void net.connect(h);
      throw new Error(`Connection lost. Reconnecting; reopen Tmux when connected. ${e}`, {
        cause: e,
      });
    }
    ui.modal = 'sessions';
  };

  startSession = async () => {
    const ui = this.#app.ui;
    await this.#app.profiles.refresh(this.sessionHost);
    this.sessionStep = 0;
    this.nameError = '';
    this.#sessionOperation = uid();
    ui.form = blankForm(this.sessionHost, '', true);
    ui.modal = 'new-session';
  };

  createSession = async () => {
    const { ui, panes } = this.#app;
    const form = ui.form;
    if (
      form.repo &&
      form.agent !== 'shell' &&
      this.#acknowledgedRepo !== form.repo &&
      panes.workspaces.some(
        (w) =>
          w.hostId === this.sessionHost &&
          w.panes.some((p) => p.repo === form.repo && p.agent !== 'shell'),
      )
    ) {
      ui.modal = 'repo-collision';
      return;
    }
    this.#acknowledgedRepo = '';
    const p = { ...form, id: '', sessionId: '', operation: this.#sessionOperation } as Pane;
    const session = await rpc<Session>(this.sessionHost, 'create', {
      ...p,
      path: form.repo,
      install: form.agent !== 'shell' && !this.selectedAgent?.path,
      brief:
        form.repo && form.agent !== 'shell' && form.brief ? this.#app.profiles.promptFor(p) : '',
    });
    const inventory = ui.inventories[this.sessionHost];
    if (inventory)
      inventory.sessions = [...inventory.sessions.filter((s) => s.id !== session.id), session];
    await this.attach(session, {
      repo: ui.form.repo,
      identity: ui.form.identity,
      agent: ui.form.agent,
      model: ui.form.model,
      effort: ui.form.effort,
    });
  };

  useSharedWorkingCopy = () => {
    this.#acknowledgedRepo = this.#app.ui.form.repo;
    void this.#app.ui.task(this.createSession);
  };

  attach = async (s: Session, options: Partial<Pane> = {}) => {
    const { ui, panes } = this.#app;
    const target = this.sessionPane;
    const w = target ? panes.owner(target.id) : undefined;
    if (target && !w)
      throw new Error('The selected terminal has been closed. Reopen the session picker.');
    const p: Pane = {
      id: uid(),
      hostId: this.sessionHost,
      hostKey: ui.host(this.sessionHost)?.fingerprint,
      sessionId: s.id,
      stamp: s.stamp,
      name: s.name,
      colors: target?.colors,
      repo: '',
      identity: '',
      agent: s.agent || 'shell',
      model: '',
      effort: '',
      operation: uid(),
      ...options,
    };
    if (target && w) {
      await call('terminal_close', { id: target.id });
      panes.updateWorkspace({
        ...w,
        panes: w.panes.map((x) => (x.id === target.id ? p : x)),
        layout: swap(panes.layoutFor(w), target.id, p.id),
        focused: p.id,
      });
      if (ui.maximize === target.id) ui.maximize = p.id;
    } else {
      const workspace: Workspace = {
        id: uid(),
        name: s.name,
        hostId: this.sessionHost,
        panes: [p],
        layout: tile([p.id]),
        focused: p.id,
      };
      ui.profiles[this.sessionHost].workspaces.push(workspace);
      panes.selectWorkspace(workspace);
      this.#app.deviceStore.saved(this.sessionHost);
    }
    ui.modal = '';
    this.sessionPane = null;
  };

  renameSession = async () => {
    const ui = this.#app.ui;
    if (!ui.renameTarget) return;
    await rpc(ui.renameTarget.hostId, 'rename', {
      id: ui.renameTarget.sessionId,
      stamp: ui.renameTarget.stamp,
      name: ui.renameValue,
    });
    for (const w of this.#app.panes.workspaces) {
      let changed = false;
      for (const p of w.panes)
        if (p.hostId === ui.renameTarget.hostId && p.sessionId === ui.renameTarget.sessionId) {
          p.name = ui.renameValue;
          changed = true;
        }
      if (changed) this.#app.deviceStore.saved(w.hostId);
    }
    ui.modal = '';
    await this.#app.profiles.refresh(ui.renameTarget.hostId);
  };

  destroySession = async () => {
    const ui = this.#app.ui;
    if (!ui.destroyTarget) return;
    const targetHost = ui.destroyTarget.hostId || ui.device.activeHost;
    await rpc(targetHost, 'destroy', { id: ui.destroyTarget.id, stamp: ui.destroyTarget.stamp });
    this.sessionHost = targetHost;
    await this.refreshSessions(targetHost);
    ui.modal = 'sessions';
    ui.notice = 'Session deleted. Its running processes have stopped.';
  };

  loadGithubRepos = async () => {
    const ui = this.#app.ui;
    this.githubRepos = (
      await rpc<{ full_name: string; description: string }[][]>(
        ui.device.activeHost,
        'github_repos',
        { identity: ui.form.identity },
      )
    ).flat();
  };

  cloneRepo = async (repo: string) => {
    const ui = this.#app.ui;
    const root = ui.profiles[ui.device.activeHost].preferences.roots[0];
    if (!root) throw new Error('Set a repository root in host settings.');
    await rpc(ui.device.activeHost, 'clone', { repo, root, identity: ui.form.identity });
    await this.#app.profiles.refresh();
  };
}
