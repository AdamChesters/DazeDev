import { tick } from 'svelte';
import { rpc } from '../bridge';
import { consumeReview, reviewCommand, type CommandReview } from '../command-review';
import { parseCommand, type Action } from '../commands';
import type { App } from './app';

// The IDE command palette: interpret typed or dictated text, show the review,
// then run the reviewed action. Also the worktree dialog it can open.
export class Commands {
  text = $state('');
  review = $state<CommandReview | null>(null);
  error = $state('');
  branch = $state('');
  destination = $state('');
  // The host asks before running a repository's own worktree script.
  worktreeQuestion = $state<{ script: string } | null>(null);
  #worktreeRequest: { hostId: string; args: Record<string, unknown> } | null = null;
  #app: App;

  constructor(app: App) {
    this.#app = app;
    $effect(() => {
      if (app.ui.modal === 'command') void tick().then(this.focusInput);
    });
  }

  focusInput = () => {
    if (this.#app.ui.modal !== 'command' || !this.#app.appHasFocus()) return;
    const input = document.querySelector<HTMLTextAreaElement>('[data-modal="command"] textarea');
    if (!input || document.activeElement === input) return;
    // Preserve an explicit edit elsewhere; recover focus lost when the Stop button disappears.
    const active = document.activeElement;
    if (
      active?.matches('input, textarea, [contenteditable="true"]') &&
      input.closest('[role="dialog"]')?.contains(active)
    )
      return;
    input.focus();
  };

  #context() {
    const { ui, panes } = this.#app;
    const current = panes.current;
    const activePane = panes.activePane;
    return {
      hostId: ui.device.activeHost,
      hostName: ui.host(ui.device.activeHost)?.name || 'selected host',
      workspaceId: current?.id || '',
      workspaceName: current?.name || '',
      paneId: activePane?.id || '',
      paneName: activePane?.name || '',
      paneContext: activePane
        ? `${activePane.hostId}:${activePane.sessionId}:${activePane.stamp || ''}`
        : '',
    };
  }

  interpret = () => {
    const ui = this.#app.ui;
    this.review = null;
    this.error = '';
    try {
      const action = parseCommand(
        this.text,
        ui.device.hosts,
        ui.device.activeHost,
        ui.inventories[ui.device.activeHost],
        ui.inventories,
      );
      this.review = reviewCommand(
        this.text,
        action,
        this.#context(),
        action.kind === 'new' ? ui.host(action.hostId)?.name || 'selected host' : undefined,
      );
    } catch (e) {
      this.error = String(e);
    }
  };

  submit = () => {
    const { ui, dictation } = this.#app;
    if (dictation.status === 'listening' && dictation.mode === 'command')
      void ui.task(() => dictation.record('command'));
    else if (dictation.status === 'idle' && !this.review) this.interpret();
  };

  implement = async () => {
    if (!this.review || this.#app.ui.busy || this.#app.dictation.status !== 'idle') return;
    try {
      const action = consumeReview(this.review, this.text, this.#context());
      this.review = null;
      await this.#execute(action);
    } catch (e) {
      this.error = String(e);
      this.review = null;
    }
  };

  async #execute(a: Action) {
    const { ui, panes, sessions } = this.#app;
    const activePane = panes.activePane;
    if (a.kind === 'sessions') {
      await sessions.showSessions(activePane || null);
    } else if (a.kind === 'split') {
      panes.addTerminal(a.axis === 'row' ? 'right' : 'bottom');
    } else if (a.kind === 'new') {
      if (a.destination === 'current' && !panes.current)
        throw new Error('Open a workspace first, or say “open a new workspace”.');
      sessions.showLaunch(a.destination === 'current' ? 'row' : null);
      sessions.launchFromCommand = true;
      sessions.launchChooseRepo = a.chooseRepo;
      ui.form = { ...ui.form, ...a, brief: true };
      ui.modal = 'launch';
    } else if (a.kind === 'switch') {
      const w = panes.workspaces.find((w) => w.name.toLowerCase() === a.name.toLowerCase());
      if (!w) throw new Error('Workspace not found');
      panes.selectWorkspace(w);
      ui.modal = '';
    } else if (a.kind === 'rename' && activePane) {
      if (!activePane.sessionId) throw new Error('Join or create a tmux session first.');
      ui.renameTarget = activePane;
      ui.renameValue = a.name;
      ui.modal = 'rename';
    } else if (a.kind === 'destroy' && activePane) {
      if (!activePane.sessionId) throw new Error('Join or create a tmux session first.');
      sessions.sessionPane = activePane;
      sessions.sessionHost = activePane.hostId;
      await sessions.refreshSessions(sessions.sessionHost);
      ui.destroyTarget = {
        id: activePane.sessionId,
        hostId: activePane.hostId,
        stamp: activePane.stamp,
        name: activePane.name,
        windows: 1,
        attached: 1,
      };
      ui.modal = 'destroy';
    } else if (a.kind === 'worktree') {
      this.branch = a.branch;
      this.worktreeQuestion = null;
      ui.modal = 'worktree';
    }
  }

  sendWorktree = async (runScript?: boolean) => {
    const request = this.#worktreeRequest;
    if (!request) throw new Error('Start the worktree again.');
    const result = await rpc<{ needsConfirmation?: boolean; script?: string } | null>(
      request.hostId,
      'worktree',
      runScript === undefined ? request.args : { ...request.args, runScript },
    );
    if (result?.needsConfirmation && runScript === undefined) {
      this.worktreeQuestion = { script: result.script || 'scripts/new-worktree.sh' };
      return;
    }
    this.worktreeQuestion = null;
    this.#worktreeRequest = null;
    await this.#app.profiles.refresh();
    this.#app.ui.modal = 'repos';
  };

  createWorktree = async () => {
    const activePane = this.#app.panes.activePane;
    if (!activePane?.repo) throw new Error('Select a repository terminal first.');
    this.#worktreeRequest = {
      hostId: activePane.hostId,
      args: {
        path: activePane.repo,
        branch: this.branch,
        destination: this.destination,
        identity: activePane.identity,
      },
    };
    await this.sendWorktree();
  };
}
