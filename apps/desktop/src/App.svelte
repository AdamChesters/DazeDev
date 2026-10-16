<script lang="ts">
  import TerminalSearch from './components/TerminalSearch.svelte';
  import type { SearchTarget, SearchHit, SearchNavigation } from './lib/terminal-search';
  import type { WindowTerminalState } from './lib/terminal-window';
  import PinSettings from './components/PinSettings.svelte';
  import TerminalActionMenu from './components/TerminalActionMenu.svelte';
  import BroadcastDialog from './components/BroadcastDialog.svelte';
  import DictionarySettings from './components/DictionarySettings.svelte';
  import { startDictionarySync } from './lib/dictionary-store';
  import PaneActions from './components/PaneActions.svelte';
  import { needsChangelog } from './lib/repositories';
  import FilesDialog from './components/FilesDialog.svelte';
  import ViewerWindow from './components/ViewerWindow.svelte';
  import Licenses from './components/Licenses.svelte';
  import { hostLabel, terminalPresentation, unknownStatus } from './lib/pane-status';
  import { onMount, tick } from 'svelte';
  import { AlertThrottle } from './lib/terminal-alerts';
  import {
    appActionFor,
    appShortcut,
    shortcutConflict,
    terminalOwnsKey,
    type AppAction,
  } from './lib/app-shortcuts';
  import { macPlatform } from './lib/terminal-setup';
  import { fade } from 'svelte/transition';
  import HighlightEffect from './components/HighlightEffect.svelte';
  import { recordingHighlight } from './lib/highlights';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import {
    Plus,
    Terminal as TerminalIcon,
    Server,
    Mic,
    Settings,
    ChevronDown,
    Columns2,
    Rows2,
    Maximize2,
    Minimize2,
    X,
    Command,
    ArrowUpRight,
    FolderGit2,
    Search,
    Download,
    PanelLeftClose,
    PanelLeftOpen,
    Radio,
    LayoutGrid,
    Palette,
    Upload,
  } from '@lucide/svelte';
  import { terminals, terminalDefaults } from './lib/terminals';
  import { tabColors } from './lib/colors';
  import TmuxSettings from './components/TmuxSettings.svelte';
  import Brand from './components/Brand.svelte';
  import SupportDialog from '../donate/SupportDialog.svelte';
  import FeedbackDialog from '../donate/templates/FeedbackDialog.svelte';
  import { content as supportContent } from '../donate/adapter';
  import {
    defaultShortcut,
    defaultCommandShortcut,
    mouseMatches,
    keyboardShortcut,
    matches,
    type Shortcut,
  } from './lib/shortcut';
  import HostTree from './components/HostTree.svelte';
  import LayoutView from './components/Layout.svelte';
  import TerminalView from './components/Terminal.svelte';
  import {
    type Repo,
    type Host,
    type Pane,
    type Workspace,
    type Profile,
    type Session,
    blankProfile,
    uid,
  } from './lib/types';
  import { resize, balanced, columns, ids } from './lib/layout';
  import { reorderWorkspaces } from './lib/pane-transfer';
  import { HOSTLESS } from './lib/workspace-groups';
  import { terminalTabs } from './lib/terminal-tabs';
  import { call, on, rpc, desktop } from './lib/bridge';
  import { pollUpdates, canPresentUpdate } from './lib/update-checks';
  import { Updates, type UpdateInfo } from './lib/state/updates.svelte';
  import { version as appVersion } from '../package.json';
  import { VoiceCaptureKeys } from './lib/voice-capture-keys';
  import { voiceStackReady } from './lib/voice-setup';
  import { changelog, mergeChangelog, type ReleaseEntry } from './lib/changelog';
  import { releaseVersion } from './lib/release-version';
  import { focusDialog } from './lib/focus';
  import type { App } from './lib/state/app';
  import { Shell, hostDefaults } from './lib/state/shell.svelte';
  import { Panes } from './lib/state/panes.svelte';
  import { Profiles, type DeviceFile } from './lib/state/profiles.svelte';
  import { Sessions as Launcher } from './lib/state/sessions.svelte';
  import { Dictation } from './lib/state/dictation.svelte';
  import { Commands } from './lib/state/commands.svelte';
  import { Broadcast } from './lib/state/broadcast.svelte';
  import { DeviceStore } from './lib/state/device-store.svelte';
  import { VoiceModels } from './lib/state/voice.svelte';
  import { Connections } from './lib/state/connections.svelte';
  import { DetachedWindows } from './lib/state/detached-windows.svelte';
  import Agents from './components/modals/Agents.svelte';
  import BrowserRequest from './components/modals/BrowserRequest.svelte';
  import Changelog from './components/modals/Changelog.svelte';
  import CloseFailure from './components/modals/CloseFailure.svelte';
  import CloseNative from './components/modals/CloseNative.svelte';
  import CloseTerminal from './components/modals/CloseTerminal.svelte';
  import CommandDialog from './components/modals/CommandDialog.svelte';
  import DeleteHost from './components/modals/DeleteHost.svelte';
  import DestroySession from './components/modals/DestroySession.svelte';
  import HostColors from './components/modals/HostColors.svelte';
  import HostForm from './components/modals/HostForm.svelte';
  import HostlessConnect from './components/modals/HostlessConnect.svelte';
  import Launch from './components/modals/Launch.svelte';
  import ListenIntro from './components/modals/ListenIntro.svelte';
  import NewSession from './components/modals/NewSession.svelte';
  import PaneColors from './components/modals/PaneColors.svelte';
  import PaneHost from './components/modals/PaneHost.svelte';
  import PaneRepo from './components/modals/PaneRepo.svelte';
  import RenameSession from './components/modals/RenameSession.svelte';
  import RepoCollision from './components/modals/RepoCollision.svelte';
  import Repos from './components/modals/Repos.svelte';
  import Sessions from './components/modals/Sessions.svelte';
  import TerminalHostConnection from './components/modals/TerminalHostConnection.svelte';
  import UpdatePrompt from './components/modals/UpdatePrompt.svelte';
  import VoiceSetup from './components/modals/VoiceSetup.svelte';
  import WorkspaceOptions from './components/modals/WorkspaceOptions.svelte';
  import Worktree from './components/modals/Worktree.svelte';
  import AppearanceSection from './components/modals/settings/AppearanceSection.svelte';
  import ConnectionsSection from './components/modals/settings/ConnectionsSection.svelte';
  import HostPreferencesSection from './components/modals/settings/HostPreferencesSection.svelte';
  import ShortcutsSection from './components/modals/settings/ShortcutsSection.svelte';
  import TerminalSection from './components/modals/settings/TerminalSection.svelte';
  import UpdatesSection from './components/modals/settings/UpdatesSection.svelte';
  import VoiceHotkeysSection from './components/modals/settings/VoiceHotkeysSection.svelte';
  import VoiceModelsSection from './components/modals/settings/VoiceModelsSection.svelte';

  // State lives in the modules under lib/state; see lib/state/app.ts.
  const app = { appHasFocus } as App;
  const ui = (app.ui = new Shell());
  const panes = (app.panes = new Panes(app));
  const profiles = (app.profiles = new Profiles(app));
  const launcher = (app.sessions = new Launcher(app));
  const dictation = (app.dictation = new Dictation(app));
  const commands = (app.commands = new Commands(app));
  const broadcast = (app.broadcast = new Broadcast(app));
  const { fail, task, nativePane } = ui;
  const net = (app.net = new Connections({
    desktop,
    hosts: () => ui.device.hosts,
    call: (name, args) => call(name, args),
    onready: (hostId, hostNotice) => {
      // A native host can report that older sessions keep an old broker running.
      if (hostNotice) ui.notice = `${ui.host(hostId)?.name || 'Host'}: ${hostNotice}`;
      void profiles.joined(hostId);
    },
  }));
  const connections = net.connections,
    logs = net.logs;
  const connect = (h: Host) => net.connect(h);
  const reconnectHost = (h: Host) => net.reconnectHost(h);
  const disconnectHost = (hostId: string) => net.disconnect(hostId);
  const checkConnections = (onlyHost?: string) => net.check(onlyHost);
  const deviceStore = (app.deviceStore = new DeviceStore({
    device: () => ui.device,
    profiles: () => ui.profiles,
    enabled: () => ui.mounted,
    connected: (hostId) => connections[hostId] === 'Connected',
    saveDevice: (value) => call('device_save', { value }),
    saveProfile: (hostId, profile) =>
      rpc<Profile>(hostId, 'profile_save', { profile, revision: profile.revision }),
    ondevicefail: fail,
    onprofilefail: (e) => {
      ui.error = String(e);
    },
  }));
  const voiceModels = (app.voiceModels = new VoiceModels({
    desktop,
    call: (name, args) => call(name, args),
    device: () => ui.device,
    voiceStatus: () => dictation.status,
    persist: () => persistDevice(),
    notice: (message) => {
      ui.notice = message;
    },
    fail: (e) => fail(e),
    mediaDevices: () => navigator.mediaDevices,
  }));
  const downloadProgress = voiceModels.downloadProgress;
  const {
    loadModels,
    changeModel: changeVoiceModel,
    selectModel: selectVoiceModel,
    removeModel,
    downloadModel,
    setup: setupVoice,
    refreshMicrophones,
  } = voiceModels;
  const {
    layoutFor,
    selectPane,
    setLocalFocus,
    updateWorkspace,
    selectWorkspace,
    addTerminal,
    closeViewer,
    menuPaneDrop,
    startPaneDrag,
    openFiles,
    pasteFilePath,
    choosePaneHost,
    switchPaneHost,
    finishNativeClose,
    cancelNativeClose,
    confirmPendingClose,
    offerNativeClose,
    closePane,
    closeWorkspace,
    contextPane,
    captureTerminalSelection,
  } = panes;
  const {
    answerRecovery,
    showHostConnection,
    disconnectHostTerminals,
    connectAllHosts,
    connectHostTerminals,
    refresh,
    saveHostPreferences,
    reloadHostProfile,
    deleteHost,
    promptValues,
    promptFor,
  } = profiles;
  const {
    agentPicker,
    agentMenu,
    startAgentFromPicker,
    showLaunch,
    launch,
    retry,
    showSessions,
    startSession,
    createSession,
    useSharedWorkingCopy,
    attach,
    renameSession,
    destroySession,
    refreshSessions,
    loadGithubRepos,
    cloneRepo,
  } = launcher;
  const {
    bindingFor,
    configureShortcut,
    saveShortcut,
    captureShortcut,
    inputEdge,
    record,
    sendHeld: sendHeldDictation,
    copyHeld: copyHeldDictation,
  } = dictation;
  const cancelVoice = dictation.cancel;
  const { submit: commandSubmit, implement: implementCommand } = commands;
  const { sendWorktree, createWorktree } = commands;
  const { open: openBroadcast, review: reviewBroadcast, send: sendBroadcast } = broadcast;
  const host = $derived(ui.host(ui.device.activeHost));
  let backdropPress = false;
  function appHasFocus() {
    return document.hasFocus() || !!detached.focus;
  }
  const detached = (app.detached = new DetachedWindows({
    desktop,
    call: (name, args) => call(name, args),
    appWindow: getCurrentWindow,
    terminal: (paneId) => terminals.get(paneId),
    workspaces: () => panes.workspaces,
    windowState,
    title: (pane, owner) =>
      `${owner.name} / ${terminalPresentation(pane, ui.paneStatus[pane.id]).name} - DazeDev`,
    busy: () => !!ui.modal || dictation.active,
    selectWorkspace,
    focusPane: (owner, paneId) => {
      owner.focused = paneId;
      setLocalFocus(owner.id, paneId);
      if (ui.device.activeWorkspace !== owner.id || ui.device.activeHost !== owner.hostId) {
        ui.device.activeWorkspace = owner.id;
        ui.device.activeHost = owner.hostId;
        persistDevice();
      }
    },
    search: {
      open: openTerminalSearch,
      results: () => searchPanel?.showResults(),
      step: (direction) => searchPanel?.step(direction),
      close: closeTerminalSearch,
    },
    disablePasteConfirmation: () => setPasteConfirmation(false),
    reconnect: async (hostId) => {
      const host = ui.host(hostId);
      if (host) await net.reconnectHost(host);
    },
    paneAction: async (action, pane) => {
      if (action === 'repo') await chooseRepo(pane);
      if (action === 'tmux') await showSessions(pane);
      if (action === 'host') showHostConnection(pane);
      if (action === 'model') await agentMenu(pane);
      if (action === 'close') closePane(pane);
    },
    onopen: () => {
      ui.paneMenu = null;
    },
    onfail: fail,
  }));
  const detachedPanes = detached.panes;
  function windowState(pane: Pane, workspace: Workspace): WindowTerminalState {
    const device = ui.device;
    return {
      pane: $state.snapshot(pane),
      host: $state.snapshot(ui.host(pane.hostId)),
      workspaceName: workspace.name,
      workspaceColors: $state.snapshot(workspace.colors),
      theme: device.theme || 'blue',
      inputShortcut: device.inputShortcut || defaultShortcut,
      commandShortcut: device.commandShortcut || defaultCommandShortcut,
      searchShortcut: appShortcut(device.appShortcuts, 'search'),
      voiceStatus: dictation.status,
      voiceMode: dictation.mode,
      voiceTarget: dictation.target,
      dictation: dictation.text,
      settings: {
        ...(device.terminal || terminalDefaults),
        clipboard: device.clipboardByHost?.[pane.hostId] || device.terminal?.clipboard || 'notify',
      },
      status: ui.terminalState[pane.id] || '',
      ready: !!terminals.get(pane.id)?.context(),
      metadata: ui.paneStatus[pane.id],
    };
  }
  $effect(() => {
    detached.sync();
  });
  function restoreTerminalFocus() {
    const id = ui.terminalReturnFocus;
    ui.terminalReturnFocus = '';
    if (!id) return false;
    void tick().then(() => terminals.get(id)?.focus?.());
    return true;
  }
  let repoTarget = $state<Pane | null>(null);
  let repoPath = $state('');
  let repoHost = $state('');
  let repoContext = '';
  let repoChoices = $state<Repo[]>([]);
  let repoWarnings = $state<string[]>([]);
  let repoRequest = 0;
  async function refreshRepoChoices() {
    const request = ++repoRequest;
    const host = repoHost;
    repoChoices = [];
    repoWarnings = [];
    const result = await rpc<{ repos: Repo[]; warnings: string[] }>(host, 'repositories');
    if (request !== repoRequest || host !== repoHost || ui.modal !== 'pane-repo') return;
    repoChoices = result.repos;
    repoWarnings = [...new Set(result.warnings)];
  }
  async function chooseRepo(p: Pane) {
    repoTarget = p;
    repoHost = p.hostId;
    repoPath = p.repo;
    repoContext = terminals.get(p.id)?.context() || '';
    ui.search = '';
    ui.modal = 'pane-repo';
    await refreshRepoChoices();
    if (repoTarget?.id === p.id && ui.modal === 'pane-repo') repoPath = p.repo;
  }
  async function pasteRepoPrompt() {
    const owner = panes.owner(repoTarget?.id);
    const target = owner?.panes.find((p) => p.id === repoTarget?.id);
    const terminal = target && terminals.get(target.id);
    if (!target || !owner || !terminal || !repoContext || terminal.context() !== repoContext)
      throw new Error('The terminal changed. Reopen the Repo picker for the current terminal.');
    if (!repoChoices.some((r) => r.path === repoPath))
      throw new Error('Choose a repository on this terminal’s connected host.');
    await terminal.pasteForReview(
      promptFor({ ...target, hostId: repoHost, repo: repoPath }),
      repoContext,
    );
    if (repoHost === target.hostId) target.repo = repoPath;
    saved(owner.hostId);
    ui.terminalReturnFocus = target.id;
    ui.modal = '';
    ui.notice = 'Repository prompt pasted for review. Press Enter in the terminal when ready.';
  }
  let alertAudio: AudioContext | undefined;
  const alertThrottle = new AlertThrottle();
  panes.onprune = (live) => alertThrottle.retain(live);
  async function softAlert() {
    alertAudio ||= new AudioContext();
    await alertAudio.resume();
    const oscillator = alertAudio.createOscillator();
    const gain = alertAudio.createGain();
    oscillator.frequency.value = 660;
    gain.gain.setValueAtTime(0, alertAudio.currentTime);
    gain.gain.linearRampToValueAtTime(0.035, alertAudio.currentTime + 0.03);
    gain.gain.linearRampToValueAtTime(0, alertAudio.currentTime + 0.2);
    oscillator.connect(gain);
    gain.connect(alertAudio.destination);
    oscillator.start();
    oscillator.stop(alertAudio.currentTime + 0.22);
    oscillator.onended = () => {
      oscillator.disconnect();
      gain.disconnect();
    };
  }
  function terminalAlert(pane: Pane, message: string) {
    if (!alertThrottle.allow(pane.id)) return;
    if (ui.device.terminalAlerts?.visual) ui.notice = `${pane.name}: ${message}`;
    if (ui.device.terminalAlerts?.sound) void softAlert().catch(() => {});
  }
  let colorHost = $state<Host | null>(null);
  let tmuxHost = $state<Host | null>(null);
  let workspaceTarget = $state<Workspace | null>(null);
  let newHost = $state<Host>(hostDefaults());
  let aliases = $state<string[]>([]);
  let clientInfo = $state({
    version: appVersion,
    dataPath: '',
    migration: '',
    updateSupported: true,
  });
  const updates = (app.updates = new Updates({
    desktop,
    supported: () => clientInfo.updateSupported,
    modal: () => ui.modal,
    setModal: (value) => {
      ui.modal = value;
    },
    canPresent: () => updateCanPresent,
    voiceBusy: () => dictation.status !== 'idle' || !!dictation.text.trim() || !!dictation.held,
    check: () => call<UpdateInfo | null>('update_check'),
    download: (version) => call('update_download', { version }),
    install: () => call('update_install'),
    saveDevice: () => deviceStore.saveNow(),
    onerror: (message) => {
      ui.error = message;
    },
  }));
  const {
    open: openUpdates,
    close: closeUpdates,
    check: checkUpdates,
    install: installUpdate,
  } = updates;
  const updateCanPresent = $derived(
    canPresentUpdate(ui.modal, dictation.status, !!panes.draggingPane),
  );
  $effect(() => {
    updates.presentPending();
  });
  let changelogReturn = $state('settings');
  let releaseHistory = $state<ReleaseEntry[]>(changelog);
  let releaseFilter = $state<'all' | 'desktop' | 'mobile'>('all');
  let historyStatus = $state('');
  let pendingChangelog = $state('');
  $effect(() => {
    if (pendingChangelog && !ui.modal && !ui.busy) {
      ui.device.lastChangelogVersion = pendingChangelog;
      pendingChangelog = '';
      showChangelog('');
      persistDevice();
    }
  });
  let feedbackMessage = $state('');
  let feedbackBusy = $state(false);
  let feedbackError = $state('');
  let feedbackSent = $state(false);
  function openFeedback() {
    ui.device.feedbackContact ||= { name: '', email: '' };
    feedbackSent = false;
    ui.error = '';
    ui.modal = 'feedback';
  }
  async function sendFeedback() {
    if (feedbackBusy) return;
    feedbackError = '';
    feedbackSent = false;
    feedbackBusy = true;
    try {
      await deviceStore.saveNow();
      await call('feedback_send', {
        feedback: { ...$state.snapshot(ui.device.feedbackContact), message: feedbackMessage },
      });
      feedbackMessage = '';
      feedbackSent = true;
      if (ui.modal !== 'feedback') ui.notice = supportContent.feedback.success;
    } catch (e) {
      feedbackError = String(e);
    } finally {
      feedbackBusy = false;
    }
  }
  $effect(() => {
    document.documentElement.dataset.theme = ui.device.theme || 'blue';
    document.documentElement.dataset.highlightMotion = ui.device.highlightMotion || 'animate';
  });
  function addHost() {
    newHost = hostDefaults();
    ui.error = '';
    ui.modal = 'host';
  }
  function showChangelog(origin = 'settings') {
    changelogReturn = origin;
    releaseHistory = mergeChangelog(ui.device.releaseHistory);
    ui.modal = 'changelog';
    if (desktop) {
      historyStatus = 'Checking public release history…';
      void call<unknown>('release_history')
        .then((value) => {
          releaseHistory = mergeChangelog(value, releaseHistory);
          ui.device.releaseHistory = releaseHistory;
          persistDevice();
          historyStatus = '';
        })
        .catch(() => {
          historyStatus = 'Showing saved release history. Public history is currently unavailable.';
        });
    }
  }
  function respond(hostId: string, value: string) {
    return net.respond(hostId, value);
  }
  let capturingAppShortcut = $state<AppAction | null>(null);
  let appShortcutStatus = $state('');
  const mac = macPlatform();
  function saveAppShortcut(action: AppAction, binding: Shortcut) {
    const clash = shortcutConflict(action, binding, ui.device.appShortcuts, [
      { label: 'Terminal dictation', shortcut: bindingFor('dictation') },
      { label: 'IDE voice command', shortcut: bindingFor('command') },
    ]);
    if (clash) {
      appShortcutStatus = `${binding.label} is already used by ${clash}. Press another combination, or Escape to cancel.`;
      return;
    }
    ui.device.appShortcuts = { ...ui.device.appShortcuts, [action]: binding };
    capturingAppShortcut = null;
    appShortcutStatus = '';
    persistDevice();
  }
  function resetAppShortcuts() {
    ui.device.appShortcuts = {};
    capturingAppShortcut = null;
    appShortcutStatus = 'Shortcuts reset to defaults.';
    persistDevice();
  }
  $effect(() => {
    if (capturingAppShortcut && ui.modal !== 'settings') capturingAppShortcut = null;
  });
  let searchOrigin = $state<SearchTarget | null>(null);
  let searchPanel = $state<TerminalSearch>();
  const searchTargets = $derived(
    panes.workspaces.flatMap((w) =>
      w.panes.map((p) => ({
        paneId: p.id,
        hostId: p.hostId,
        host: ui.host(p.hostId)?.name || 'Unavailable host',
        workspaceId: w.id,
        workspace: w.name,
        terminal: p.name,
        session: [
          p.agent,
          ui.paneStatus[p.id]?.sessionId ||
            ui.inventories[p.hostId]?.sessions.find((session) => session.id === p.sessionId)
              ?.name ||
            (p.shell ? 'Plain shell' : p.sessionId),
        ]
          .filter(Boolean)
          .join(' · '),
      })),
    ),
  );
  function openTerminalSearch(paneId = panes.current?.focused || '') {
    if (ui.modal || dictation.active) {
      ui.notice = 'Finish the current action before searching.';
      return;
    }
    const origin =
      searchTargets.find((target) => target.paneId === paneId) ||
      searchTargets.find((target) => !!terminals.get(target.paneId)?.context());
    if (!origin) {
      ui.notice = 'Select a live terminal to search.';
      return;
    }
    ui.paneMenu = null;
    searchOrigin = null;
    void tick().then(() => {
      searchOrigin = origin;
    });
  }
  function closeTerminalSearch() {
    const id = panes.current?.focused;
    searchOrigin = null;
    if (id) void tick().then(() => terminals.get(id)?.focus?.());
  }
  async function jumpSearch(hit: SearchHit) {
    if (dictation.active) throw Error('Stop dictation before switching terminals.');
    const owner = panes.owner(hit.paneId);
    const controls = terminals.get(hit.paneId);
    if (!owner || !controls?.revealMatch?.(hit.id)) return false;
    selectWorkspace(owner);
    owner.focused = hit.paneId;
    setLocalFocus(owner.id, hit.paneId);
    await tick();
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    if (!controls.revealMatch?.(hit.id)) return false;
    if (desktop && !detachedPanes.has(hit.paneId)) {
      await getCurrentWindow().show();
      await getCurrentWindow().unminimize();
      await getCurrentWindow().setFocus();
    }
    if (detachedPanes.has(hit.paneId))
      detached.publish(hit.paneId, {
        kind: 'search-selection',
        selection: controls.searchSelection?.(),
      });
    return true;
  }
  function publishSearchNavigation(value: SearchNavigation | null, hit?: SearchHit) {
    for (const [id] of detachedPanes)
      detached.publish(id, {
        kind: 'search-navigation',
        navigation: id === hit?.paneId ? value : null,
      });
    if (value && hit && detachedPanes.has(hit.paneId))
      requestAnimationFrame(() => {
        void call('terminal_window_focus', { paneId: hit.paneId }).catch(fail);
      });
  }
  function persistDevice() {
    deviceStore.persist();
  }
  function setPasteConfirmation(confirm: boolean) {
    ui.device.terminal = { ...(ui.device.terminal || terminalDefaults), confirmPaste: confirm };
    persistDevice();
    if (!confirm)
      ui.notice = 'Multi-line paste no longer asks first. Change this in Settings > Terminal.';
  }
  function saved(hostId: string) {
    deviceStore.saved(hostId);
  }
  function resizeSidebar(width: number) {
    ui.device.sidebarWidth = Math.round(
      Math.max(180, Math.min(520, window.innerWidth - 360, width)),
    );
  }
  function newHostlessWorkspace() {
    const workspace = panes.newHostlessWorkspace();
    workspaceTarget = workspace;
    ui.renameValue = workspace.name;
    ui.modal = 'workspace';
  }
  async function destroyApp() {
    await deviceStore.saveNow();
    await call('app_close');
  }
  let closeFailure = $state('');
  // Hosts ask to open links in the local browser; the user decides each one.
  let browserRequests = $state<{ requestId: string; hostId: string; url: string }[]>([]);
  let browserTrust = $state(false);
  const browserRequest = $derived(browserRequests[0]);
  async function decideBrowserRequest(allow: boolean) {
    const request = browserRequests[0];
    if (!request) return;
    const trustHost = allow && browserTrust;
    browserRequests = browserRequests.slice(1);
    browserTrust = false;
    await call('browser_open_decision', { requestId: request.requestId, allow, trustHost }).catch(
      fail,
    );
  }
  async function closeAnyway() {
    closeFailure = '';
    await deviceStore.saveNow().catch(() => {});
    await call('app_close');
  }
  async function openSettings() {
    ui.modal = 'settings';
    void checkUpdates(false, true);
    profiles.openSettings();
    await loadModels();
    voiceModels.detectHardware();
    void refreshMicrophones();
  }
  let systemReducedMotion = $state(false);
  const highlightMotionAllowed = $derived(
    ui.device.highlightMotion !== 'static' &&
      (ui.device.highlightMotion === 'animate' || !systemReducedMotion),
  );
  onMount(startDictionarySync);
  onMount(() => {
    const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    const updateMotion = () => {
      systemReducedMotion = motionQuery.matches;
    };
    updateMotion();
    motionQuery.addEventListener('change', updateMotion);
    return () => motionQuery.removeEventListener('change', updateMotion);
  });
  // Asks before closing while PowerShell sessions still run on a local host.
  async function closeRequested(event: { preventDefault(): void }) {
    if (panes.filesLocked || panes.viewerLocked) {
      event.preventDefault();
      ui.notice = 'Save or discard edits, or finish the upload before closing.';
      return;
    }
    event.preventDefault();
    if (dictation.status !== 'idle' || dictation.text.trim()) {
      ui.notice = 'Finish dictation and copy or discard retained text before closing.';
      return;
    }
    closeFailure = '';
    try {
      const native: Pane[] = [];
      for (const host of ui.device.hosts.filter((h) => h.kind === 'powershell')) {
        if (connections[host.id] !== 'Connected') await net.connect(host);
        const running = await rpc<(Session & { running: boolean })[]>(host.id, 'sessions');
        for (const session of running.filter((s) => s.running))
          native.push({ hostId: host.id, sessionId: session.id } as Pane);
      }
      if (offerNativeClose(native, destroyApp)) {
        panes.closingApp = true;
        return;
      }
      await destroyApp();
    } catch (e) {
      // A failed check must never trap the user in the app.
      closeFailure = String(e);
    }
  }
  // ?preview in a plain browser shows sample workspaces.
  function showPreview() {
    const h = { ...hostDefaults(), id: 'preview', name: 'Development', address: 'dev-box' };
    ui.device.hosts = [h];
    ui.device.activeHost = h.id;
    const previewPanes = Array.from({ length: 4 }, (_, i): Pane => ({
      id: uid(),
      hostId: h.id,
      sessionId: '',
      name: `dazedev-${i + 1}`,
      repo: '~/dev/DazeDev',
      identity: 'example-user',
      agent: 'codex',
      model: '',
      effort: '',
      operation: uid(),
    }));
    const w = {
      id: uid(),
      name: 'DazeDev',
      hostId: h.id,
      panes: previewPanes,
      layout: balanced(
        previewPanes.map((p) => p.id),
        panes.stageWidth,
      ),
      focused: previewPanes[0].id,
    };
    ui.profiles[h.id] = { ...blankProfile(), workspaces: [w] };
    ui.device.collapsedHosts = { ...ui.device.collapsedHosts, [w.hostId]: false };
    ui.device.activeWorkspace = w.id;
  }
  onMount(() => {
    ui.mounted = true;
    const unsub: (() => void)[] = [];
    for (const subscription of [
      on<{ paneId: string; requestId: string; action: string; args: Record<string, unknown> }>(
        'terminal-window-request',
        (event) => {
          void detached.request(event);
        },
      ),
      on<{ paneId: string; focused: boolean }>('terminal-window-focus', (event) =>
        detached.focused(event.paneId, event.focused),
      ),
      on<{ requestId: string; hostId: string; url: string }>('browser-open-request', (request) => {
        if (
          typeof request?.requestId === 'string' &&
          typeof request.hostId === 'string' &&
          typeof request.url === 'string'
        ) {
          browserRequests = [...browserRequests, request];
          // The question waits in this window; make sure the user can see it.
          void (async () => {
            const window = getCurrentWindow();
            await window.show();
            await window.unminimize();
            await window.setFocus();
          })().catch(() => {});
        }
      }),
      on<{ paneId: string }>('terminal-window-closed', (event) => detached.closed(event.paneId)),
    ])
      void subscription.then((stop) => {
        if (ui.mounted) unsub.push(stop);
        else stop();
      });
    void (async () => {
      try {
        const data = desktop
          ? await call<DeviceFile>('device_load').catch((e) => {
              fail(e);
              return null;
            })
          : null;
        if (desktop) aliases = await call<string[]>('ssh_aliases').catch(() => []);
        if (data) profiles.load(data);
        const listeners = await Promise.all([
          on<{ done: number; total: number }>(
            'update-progress',
            (v) => (updates.progress = Math.round((v.done / v.total) * 100)),
          ),
          on<{ hostId: string; attempt: string; identity?: { notice?: string } }>(
            'host-ready',
            (v) => net.ready(v),
          ),
          on<{ hostId: string; text: string; attempt: string }>('host-output', (v) =>
            net.output(v),
          ),
          on<{ hostId: string; attempt: string }>('host-closed', (v) => net.closed(v)),
          on<{ id: string; done: number; total: number }>(
            'model-progress',
            (v) =>
              (downloadProgress[v.id] =
                v.total > 0
                  ? `${Math.min(100, Math.round((v.done / v.total) * 100))}%`
                  : 'Downloading'),
          ),
          on('voice-phase', dictation.onPhase),
          on('voice-text', dictation.onText),
        ]);
        unsub.push(...listeners);
        if (desktop) {
          clientInfo = await call('client_info');
          await configureShortcut();
          if (clientInfo.updateSupported && ui.device.checkUpdates !== false) void checkUpdates();
          await loadModels();
          if (
            !ui.device.voiceSetupDismissed &&
            !voiceStackReady(ui.device.voice, voiceModels.models)
          )
            ui.modal = 'voice-setup';
          if (
            needsChangelog(
              clientInfo.version,
              ui.device.lastChangelogVersion,
              !!data?.hosts?.length,
            )
          ) {
            pendingChangelog = clientInfo.version;
          } else if (ui.device.lastChangelogVersion !== clientInfo.version) {
            ui.device.lastChangelogVersion = clientInfo.version;
            persistDevice();
          }
        }
        if (desktop) unsub.push(await getCurrentWindow().onCloseRequested(closeRequested));
        if (!desktop && new URLSearchParams(location.search).has('preview')) showPreview();
      } catch (e) {
        fail(e);
      }
    })();
    const captureKeys = new VoiceCaptureKeys();
    const key = (e: KeyboardEvent) => {
      if (!dictation.capturingShortcut) {
        const control = captureKeys.down(e, dictation.status, appHasFocus());
        if (control.consume) {
          e.preventDefault();
          e.stopImmediatePropagation();
          if (control.action) {
            // Retire hold-to-talk ownership so its eventual release cannot stop twice.
            dictation.shortcutHeld = null;
            dictation.releasePending = true;
            if (control.action === 'cancel') void task(cancelVoice);
            else {
              dictation.finishWithoutSubmit = true;
              void task(() => record(dictation.mode));
            }
          }
          return;
        }
      }

      if (capturingAppShortcut) {
        e.preventDefault();
        e.stopImmediatePropagation();
        if (e.key === 'Escape') {
          capturingAppShortcut = null;
          appShortcutStatus = '';
          return;
        }
        const binding = keyboardShortcut(e);
        if (binding) saveAppShortcut(capturingAppShortcut, binding);
        return;
      }
      if (browserRequest && e.key === 'Escape') {
        e.preventDefault();
        e.stopImmediatePropagation();
        void decideBrowserRequest(false);
        return;
      }
      // Ctrl+letter inside a terminal is a control key for the shell or editor.
      const inTerminal = !!(e.target as HTMLElement)?.closest?.('.xterm');
      const appAction =
        dictation.capturingShortcut || (inTerminal && terminalOwnsKey(e))
          ? undefined
          : appActionFor(e, ui.device.appShortcuts, mac);
      if (appAction === 'fullscreen' && desktop) {
        e.preventDefault();
        e.stopImmediatePropagation();
        void task(async () => {
          const w = getCurrentWindow();
          await w.setFullscreen(!(await w.isFullscreen()));
        });
        return;
      }
      if (dictation.capturingShortcut) {
        e.preventDefault();
        e.stopImmediatePropagation();
        if (e.key === 'Escape') {
          dictation.capturingShortcut = null;
          void task(() => configureShortcut());
          return;
        }
        const binding = keyboardShortcut(e);
        if (binding) void task(() => saveShortcut(binding));
        return;
      }
      const mode = (['dictation', 'command'] as const).find((mode) => matches(e, bindingFor(mode)));
      if (mode) {
        e.preventDefault();
        e.stopImmediatePropagation();
        if (!e.repeat) void inputEdge(true, mode).catch(fail);
        return;
      }
      if (
        appAction === 'search' &&
        !ui.modal &&
        !(e.target as HTMLElement)?.closest?.('.source-viewer')
      ) {
        e.preventDefault();
        e.stopImmediatePropagation();
        openTerminalSearch();
        return;
      }
      if ((e.target as HTMLElement)?.closest?.('.search-popup')) return;
      // Monaco owns its find/completion shortcuts; use the dialog Close button to exit.
      if ((e.target as HTMLElement)?.closest?.('.source-viewer')) return;
      // Let the native actions popup consume Escape before app-level actions.
      if (e.key === 'Escape' && document.querySelector('.pane-buttons:popover-open')) return;
      if (e.key === 'Escape' && (e.target as HTMLElement)?.closest?.('.pin-settings form')) return;
      if (e.key === 'Escape' && ui.dictionarySelection !== null) {
        ui.dictionarySelection = null;
        e.preventDefault();
        return;
      }
      if (e.key === 'Escape' && ui.paneMenu) {
        ui.paneMenu = null;
        return;
      }

      if (
        e.key === 'Escape' &&
        !e.isComposing &&
        e.keyCode !== 229 &&
        !e.ctrlKey &&
        !e.altKey &&
        !e.shiftKey &&
        !e.metaKey
      ) {
        if (updates.prompt && !ui.modal && !updates.busy) {
          closeUpdates();
          return;
        }
        if (!ui.busy && !panes.filesLocked && !broadcast.busy)
          ui.modal = ui.modal === 'changelog' ? changelogReturn : '';
      }
      if (appAction === 'command' && !panes.filesLocked && !broadcast.busy) {
        e.preventDefault();
        ui.modal = 'command';
      }
    };
    const mouseMode = (e: MouseEvent) =>
      (['dictation', 'command'] as const).find((mode) => mouseMatches(e, bindingFor(mode)));
    const pointer = (e: PointerEvent) => {
      const mode = mouseMode(e);
      if (dictation.capturingShortcut || !mode || !appHasFocus()) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      void inputEdge(true, mode).catch(fail);
    };
    const releaseKey = (e: KeyboardEvent) => {
      if (captureKeys.up(e.key)) {
        e.preventDefault();
        e.stopImmediatePropagation();
        return;
      }
      const held = dictation.shortcutHeld;
      if (
        held &&
        (keyboardShortcut(e)?.key === bindingFor(held).key ||
          ['Control', 'Alt', 'Shift', 'Meta'].includes(e.key))
      )
        void inputEdge(false, held).catch(fail);
    };
    const releasePointer = (e: PointerEvent) => {
      const held = dictation.shortcutHeld;
      if (held && [1, 4, 2, 5, 6][e.button] === bindingFor(held).key)
        void inputEdge(false, held).catch(fail);
    };
    let blurTimer: ReturnType<typeof setTimeout>;
    const blur = () => {
      clearTimeout(blurTimer);
      blurTimer = setTimeout(() => {
        if (appHasFocus()) return;
        dictation.reviewHotkey.cancel();
        captureKeys.reset();
        dictation.shortcutHeld = null;
        dictation.releasePending = true;
        dictation.capturingShortcut = null;
        if (
          dictation.status === 'loading' ||
          dictation.status === 'listening' ||
          dictation.status === 'finishing-recording'
        ) {
          ui.notice = 'Voice capture cancelled because DazeDev lost focus.';
          void cancelVoice().catch(fail);
        }
      }, 100);
    };
    window.addEventListener('keyup', releaseKey, true);
    window.addEventListener('pointerup', releasePointer, true);
    window.addEventListener('blur', blur);
    const auxiliary = (e: MouseEvent) => {
      if (appHasFocus() && mouseMode(e)) e.preventDefault();
    };
    window.addEventListener('pointerdown', pointer, true);
    window.addEventListener('auxclick', auxiliary, true);
    const deviceChanged = () => void refreshMicrophones(false);
    navigator.mediaDevices?.addEventListener('devicechange', deviceChanged);
    window.addEventListener('keydown', key, true);
    const recoverConnections = () => void checkConnections();
    const healthTimer = window.setInterval(recoverConnections, 15000);
    const stopUpdatePolling = pollUpdates(
      () => checkUpdates(),
      () => desktop && clientInfo.updateSupported && ui.device.checkUpdates !== false,
    );
    window.addEventListener('online', recoverConnections);
    window.addEventListener('focus', recoverConnections);
    return () => {
      panes.cancelPaneDrag?.();
      dictation.destroy();
      clearInterval(healthTimer);
      stopUpdatePolling();
      window.removeEventListener('online', recoverConnections);
      window.removeEventListener('focus', recoverConnections);
      net.reconnect.clear();
      void alertAudio?.close();
      ui.mounted = false;
      unsub.forEach((f) => f());
      window.removeEventListener('keydown', key, true);
      window.removeEventListener('keyup', releaseKey, true);
      window.removeEventListener('pointerup', releasePointer, true);
      window.removeEventListener('blur', blur);
      clearTimeout(blurTimer);
      window.removeEventListener('pointerdown', pointer, true);
      window.removeEventListener('auxclick', auxiliary, true);
      navigator.mediaDevices?.removeEventListener('devicechange', deviceChanged);
    };
  });
</script>

<svelte:head><title>DazeDev - agent workspace</title></svelte:head>
<div class="app">
  {#if dictation.listening}<div
      class="voice-focus-dimmer"
      aria-hidden="true"
      transition:fade={{ duration: highlightMotionAllowed ? 200 : 0 }}
    ></div>{/if}
  {#if searchOrigin}<TerminalSearch
      bind:this={searchPanel}
      targets={searchTargets}
      origin={searchOrigin}
      onjump={jumpSearch}
      onclose={closeTerminalSearch}
      onnavigation={publishSearchNavigation}
    />{/if}
  <header class="titlebar">
    <button
      class="icon"
      title="Toggle sidebar"
      aria-label="Toggle sidebar"
      onclick={() => {
        ui.device.sidebar = !ui.device.sidebar;
        persistDevice();
      }}
      >{#if ui.device.sidebar}<PanelLeftClose size={17} />{:else}<PanelLeftOpen
          size={17}
        />{/if}</button
    >
    <Brand />
    <div class="layout-actions">
      {#if panes.current}<button
          class="icon"
          aria-label="Split right"
          title="Split right"
          onclick={() => addTerminal('right')}><Columns2 size={17} /></button
        ><button
          class="icon"
          aria-label="Split below"
          title="Split below"
          onclick={() => addTerminal('bottom')}><Rows2 size={17} /></button
        ><button
          class="icon"
          aria-label="Balance layout"
          title="Balance layout"
          onclick={() =>
            updateWorkspace({
              ...panes.current!,
              layout: balanced(ids(layoutFor(panes.current!)), panes.stageWidth),
            })}><LayoutGrid size={17} /></button
        ><button
          class="icon"
          aria-label="Arrange in columns"
          title="Arrange in columns"
          onclick={() =>
            updateWorkspace({ ...panes.current!, layout: columns(ids(layoutFor(panes.current!))) })}
          ><Columns2 size={17} /></button
        >{/if}
    </div>
    <div class="title-notification" class:error={!!ui.error}>
      {#if ui.error || ui.notice}<span
          role={ui.error ? 'alert' : 'status'}
          title={ui.error || ui.notice}>{ui.error || ui.notice}</span
        ><button
          class="icon"
          aria-label={ui.error ? 'Dismiss error' : 'Dismiss notification'}
          onclick={() => {
            if (ui.error) ui.error = '';
            else ui.notice = '';
          }}><X size={14} /></button
        >{/if}
    </div>
    <div class="title-actions">
      <button
        class="icon"
        aria-label="Search terminals"
        title={`Search terminals (${appShortcut(ui.device.appShortcuts, 'search').label})`}
        onclick={() => openTerminalSearch()}><Search size={17} /></button
      >
      {#if voiceModels.modelsLoaded && !voiceModels.ready}<button
          class="voice-setup-alert"
          onclick={() => (ui.modal = 'voice-setup')}
          ><Download size={14} />Voice stack not set up, click here to install</button
        >{/if}
      <button class="quiet" onclick={() => (ui.modal = 'command')}
        ><Command size={14} /> Commands <kbd>⌘ / Ctrl K</kbd></button
      ><button
        class="icon"
        aria-label="Cycle theme"
        title={`Theme: ${ui.device.theme || 'blue'}`}
        onclick={() => {
          const themes = ['blue', 'charcoal', 'green'] as const;
          ui.device.theme = themes[(themes.indexOf(ui.device.theme || 'blue') + 1) % themes.length];
          persistDevice();
        }}><Palette size={17} /></button
      ><button class="icon" aria-label="Settings" onclick={() => void task(openSettings)}
        ><Settings size={17} /></button
      >
    </div>
  </header>
  {#if dictation.held}
    <section class="held-dictation" role="alert" aria-label="Dictation not sent">
      <p>
        <strong>Dictation not sent.</strong>
        {dictation.held.error}
        <span class="held-preview">“{dictation.held.text}”</span>
      </p>
      <div class="held-actions">
        <button
          class="held-send"
          disabled={dictation.status !== 'idle'}
          onclick={() => void task(sendHeldDictation)}>Send anyway</button
        ><button onclick={copyHeldDictation}>Copy</button><button
          onclick={() => (dictation.held = null)}>Discard</button
        >
      </div>
    </section>
  {/if}
  <div class="body">
    {#if ui.device.sidebar}<aside
        class="sidebar"
        style:width={`${ui.device.sidebarWidth || 238}px`}
      >
        <button
          class="new-workspace new-host"
          disabled={!ui.device.hosts.length}
          onclick={() => void connectAllHosts()}
          ><ArrowUpRight size={16} /> Connect all hosts</button
        >
        <button class="new-workspace new-host" onclick={addHost}><Plus size={16} /> New host</button
        >
        <section class="local-workspaces" aria-label="Hostless workspaces">
          <button class="new-workspace new-host" onclick={newHostlessWorkspace}
            ><Plus size={16} /> Hostless workspace</button
          >
          {#each ui.profiles[HOSTLESS]?.workspaces || [] as w (w.id)}
            <div
              class="local-workspace-row"
              class:active={ui.device.activeWorkspace === w.id}
              class:drop-empty={panes.dragHover === w.id}
            >
              <button
                data-workspace-tab={w.id}
                onclick={() => selectWorkspace(w)}
                title={`${w.name} · saved on this device`}>{w.name}</button
              >
              <button
                aria-label={`${w.name} options`}
                onclick={() => {
                  workspaceTarget = w;
                  ui.renameValue = w.name;
                  ui.modal = 'workspace';
                }}>⋯</button
              >
            </div>
          {/each}
        </section>
        <HostTree
          hosts={ui.device.hosts}
          profiles={ui.profiles}
          {connections}
          {logs}
          attempts={net.attempts}
          onauth={respond}
          oncancel={(h) => disconnectHost(h.id)}
          highlight={recordingHighlight(ui.device.recordingHighlight)}
          highlightColor={ui.device.recordingHighlightColor || '#39bdd7'}
          activeHost={ui.device.activeHost}
          activeWorkspace={ui.device.activeWorkspace}
          dragHover={panes.dragHover}
          collapsed={ui.device.collapsedHosts || {}}
          ontoggle={(h) => {
            ui.device.collapsedHosts = {
              ...ui.device.collapsedHosts,
              [h.id]: !ui.device.collapsedHosts?.[h.id],
            };
            persistDevice();
          }}
          onhost={(h) => {
            ui.device.activeHost = h.id;
            persistDevice();
          }}
          onhostreorder={(source, target, after) => {
            const list = ui.device.hosts.filter((h) => h.id !== source.id);
            const index = list.findIndex((h) => h.id === target.id);
            if (index < 0 || source.id === target.id) return;
            list.splice(index + (after ? 1 : 0), 0, source);
            ui.device.hosts = list;
            persistDevice();
          }}
          onreconnect={(h) => void reconnectHost(h)}
          oncolors={(h) => {
            colorHost = h;
            ui.modal = 'host-colors';
          }}
          ontmux={(h) => {
            tmuxHost = h;
            ui.modal = 'tmux-settings';
          }}
          ondocuments={(h) => openFiles({ id: '', hostId: h.id, repo: '' }, 'documents')}
          onedit={(h) => {
            newHost = { ...h, nativeShell: h.nativeShell || 'powershell' };
            ui.modal = 'host';
          }}
          onnew={(h) => {
            ui.device.activeHost = h.id;
            showLaunch();
          }}
          onreorder={(source, target, after) => {
            const profile = ui.profiles[source.hostId];
            if (!profile || dictation.active) return;
            const reordered = reorderWorkspaces(profile.workspaces, source.id, target.id, after);
            if (reordered !== profile.workspaces) {
              profile.workspaces = reordered;
              saved(source.hostId);
            }
          }}
          reorderDisabled={dictation.active || !!panes.draggingPane}
          onselect={selectWorkspace}
          onoptions={(w) => {
            workspaceTarget = w;
            ui.renameValue = w.name;
            ui.modal = 'workspace';
          }}
          ondeletehost={(h) => {
            profiles.deleteHostTarget = h;
            ui.modal = 'delete-host';
          }}
          ondelete={closeWorkspace}
        />
        {#each profiles.recoveryPrompts.filter((p) => !['Connected', 'Connecting'].includes(connections[p.hostId])) as prompt (prompt.hostId)}
          <section class="mixed-host-recovery" aria-label="Workspace host recovery">
            <p>
              Workspace {prompt.workspaces.join(', ')} has a terminal on {ui.device.hosts.find(
                (h) => h.id === prompt.hostId,
              )?.name}. Connect to that host as well?
            </p>
            <button onclick={() => void answerRecovery(prompt, true).catch(fail)}>Connect</button>
            <button onclick={() => void answerRecovery(prompt, false)}>Keep disconnected</button>
          </section>
        {/each}
        <div class="sidebar-bottom">
          <button
            onclick={() =>
              void task(async () => {
                await showSessions(panes.activePane || null);
              })}
            ><TerminalIcon size={16} />
            {ui.device.hosts.find((h) => h.id === ui.device.activeHost)?.kind === 'powershell'
              ? 'PowerShell sessions'
              : 'tmux sessions'}
            <span>{ui.inventories[ui.device.activeHost]?.sessions.length ?? '—'}</span></button
          ><button
            onclick={() => {
              ui.modal = 'repos';
            }}><FolderGit2 size={16} /> Repositories</button
          ><button onclick={() => void task(() => agentPicker())}
            ><Radio size={16} /> Install agents</button
          ><button
            onclick={() => {
              if (dictation.status === 'idle') ui.modal = 'command';
            }}><Mic size={16} /> Voice command</button
          >
          <button disabled={dictation.status !== 'idle'} onclick={openBroadcast}
            ><Radio size={16} /> Broadcast</button
          >
          <button onclick={() => void task(openSettings)}><Settings size={16} /> Settings</button>
          <button
            class="update-sidebar"
            class:recording-highlight={ui.device.supportGlow !== false}
            data-highlight={recordingHighlight(ui.device.recordingHighlight)}
            style:--voice-highlight-color={ui.device.recordingHighlightColor || '#39bdd7'}
            disabled={panes.filesLocked ||
              dictation.status !== 'idle' ||
              updates.state === 'installing'}
            onclick={() => {
              ui.modal = 'support';
              void checkUpdates();
            }}
            aria-label={`Feedback, support and about DazeDev ${releaseVersion(clientInfo.version)}: ${updates.label}`}
          >
            {#if ui.device.supportGlow !== false && recordingHighlight(ui.device.recordingHighlight) !== 'static'}
              <HighlightEffect kind={recordingHighlight(ui.device.recordingHighlight)} />
            {/if}
            <strong>FEEDBACK / SUPPORT</strong>
            <span class="support-sidebar-version">DazeDev {releaseVersion(clientInfo.version)}</span
            >
            <i
              class="update-dot"
              data-state={updates.available ? 'available' : updates.state}
              aria-hidden="true"
            ></i>
            <span>{updates.label}</span>
          </button>
        </div>
      </aside>
      <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions (Focusable ARIA window splitter with arrow-key resizing.) -->
      <div
        class="sidebar-resizer"
        role="separator"
        aria-label="Resize sidebar"
        aria-orientation="vertical"
        aria-valuemin={180}
        aria-valuemax={520}
        aria-valuenow={ui.device.sidebarWidth || 238}
        tabindex="0"
        onpointerdown={(e) => {
          e.preventDefault();
          e.currentTarget.setPointerCapture(e.pointerId);
        }}
        onpointermove={(e) => {
          if (e.currentTarget.hasPointerCapture(e.pointerId)) resizeSidebar(e.clientX);
        }}
        onpointerup={(e) => {
          e.currentTarget.releasePointerCapture(e.pointerId);
          persistDevice();
        }}
        onlostpointercapture={persistDevice}
        ondblclick={() => {
          ui.device.sidebarWidth = 238;
          persistDevice();
        }}
        onkeydown={(e) => {
          if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            e.preventDefault();
            resizeSidebar((ui.device.sidebarWidth || 238) + (e.key === 'ArrowLeft' ? -16 : 16));
            persistDevice();
          }
        }}
      ></div>{/if}
    <main>
      {#if !desktop}<div class="preview-banner">
          UI preview · open the desktop build for SSH, terminals and offline voice.
        </div>{/if}
      <div class="workspace-stage" bind:clientWidth={panes.stageWidth}>
        {#if panes.current && panes.current.panes.some((p) => detachedPanes.has(p.id))}
          <div class="detached-terminal-bar" aria-label="Terminals on other screens">
            {#each panes.current.panes.filter((p) => detachedPanes.has(p.id)) as pane}
              <button
                onclick={() => void call('terminal_window_focus', { paneId: pane.id }).catch(fail)}
                >Show {terminalPresentation(pane, ui.paneStatus[pane.id]).name}</button
              >
              <button
                onclick={() => void detached.dock(pane.id).catch(fail)}
                aria-label={`Return ${pane.name} to workspace`}>Return here</button
              >
            {/each}
          </div>
        {/if}
        <div class="workspace-canvas">
          {#each panes.workspaces as w (w.id)}
            <div class="layout-handles" class:hidden={panes.current?.id !== w.id || !!ui.maximize}>
              <LayoutView
                node={layoutFor(w)}
                onresize={(id, ratio) =>
                  updateWorkspace({ ...w, layout: resize(layoutFor(w), id, ratio) })}
                >{#snippet pane(_id)}{/snippet}</LayoutView
              >
            </div>
            {#if !ids(layoutFor(w)).filter(Boolean).length && panes.current?.id === w.id}
              <div
                class="empty-workspace"
                data-empty-workspace={w.id}
                class:drop-empty={!!panes.draggingPane && panes.dropTarget?.id === ''}
              >
                {#if w.hostId === HOSTLESS}
                  <strong>This is a hostless workspace.</strong>
                  <p>
                    Connect to a host, create a workspace and open terminals, then hold Ctrl and
                    drag terminal windows here. Their original views stay in place.
                  </p>
                  <small>This group is saved on this device.</small>
                {:else}
                  Drop a terminal here or create a new terminal.
                  <button onclick={() => addTerminal('right', { hostId: w.hostId } as Pane)}
                    >New terminal</button
                  >
                {/if}
              </div>
            {/if}
          {/each}
          {#each panes.workspaces.flatMap((w) => w.panes.map((p) => ({ w, p }))) as { w, p } (p.id)}
            {@const boxes = panes.workspaceBoxes[w.id]}
            {@const presentation = terminalPresentation(p, ui.paneStatus[p.id])}

            {@const id = p.id}
            {@const tabs = terminalTabs(w, p.id)}
            {@const activeTab = tabs?.panes.find((id) => !!boxes[id])}
            {@const box =
              detachedPanes.has(p.id) || ui.maximize === p.id
                ? { x: 0, y: 0, width: 100, height: 100 }
                : boxes[p.id] || (activeTab ? boxes[activeTab] : undefined)}
            {#if box}<section
                role="group"
                aria-label={`Terminal ${p.name}`}
                data-pane-id={p.id}
                class:hidden={detachedPanes.has(p.id) ||
                  (!!tabs && activeTab !== p.id) ||
                  panes.current?.id !== w.id ||
                  (!!ui.maximize && ui.maximize !== p.id)}
                style={`position:absolute;left:calc(${box.x}% + 4px);top:calc(${box.y}% + 2px);width:calc(${box.width}% - 8px);height:calc(${box.height}% - 4px)`}
                onpointerdowncapture={(e) => {
                  if (
                    e.button === 0 &&
                    !e.shiftKey &&
                    !(e.target as HTMLElement).closest('.pane-header, button')
                  )
                    selectPane(p.id, w);
                }}
                oncontextmenu={(e) => contextPane(e, p)}
                use:captureTerminalSelection={p}
                class="pane"
                class:focused={w.focused === id}
                class:drag-selected={panes.selectedPanes.includes(id)}
                class:voice-listening={dictation.target === p.id && dictation.listening}
                style:transform-origin={`${box.x === 0 ? 'left' : box.x + box.width >= 99.9 ? 'right' : 'center'} ${box.y === 0 ? 'top' : box.y + box.height >= 99.9 ? 'bottom' : 'center'}`}
                style:--voice-pop-scale={box.width === 100 && box.height === 100 ? 1 : 1.012}
                onfocusin={() => {
                  w.focused = id;
                  setLocalFocus(w.id, id);
                }}
              >
                {#if dictation.target === p.id && dictation.listening}
                  <div
                    class="voice-pane-border recording-highlight"
                    data-highlight={recordingHighlight(ui.device.recordingHighlight)}
                    style:--voice-highlight-color={ui.device.recordingHighlightColor || '#39bdd7'}
                    aria-hidden="true"
                    transition:fade={{ duration: highlightMotionAllowed ? 200 : 0 }}
                  >
                    {#if recordingHighlight(ui.device.recordingHighlight) !== 'static'}<HighlightEffect
                        kind={recordingHighlight(ui.device.recordingHighlight)}
                      />{/if}
                  </div>
                {/if}
                <div
                  class="pane-header"
                  style:background-color={tabColors(
                    p.colors,
                    w.colors,
                    ui.device.hosts.find((h) => h.id === p.hostId)?.colors,
                  )?.background}
                  style:color={tabColors(
                    p.colors,
                    w.colors,
                    ui.device.hosts.find((h) => h.id === p.hostId)?.colors,
                  )?.foreground}
                  role="toolbar"
                  tabindex="0"
                  aria-label={`Terminal controls for ${p.name}${panes.selectedPanes.includes(p.id) ? ', selected for dragging' : ''}`}
                  title="Drag to move. Shift-click terminal headers to select several."
                  onkeydown={(e) => {
                    if ((e.key === ' ' || e.key === 'Enter') && e.target === e.currentTarget) {
                      e.preventDefault();
                      selectPane(p.id, w, e.shiftKey);
                    } else if (e.key === 'Escape' && e.target === e.currentTarget) {
                      panes.selectedPanes = [];
                    } else if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) {
                      e.preventDefault();
                      const box = e.currentTarget.getBoundingClientRect();
                      ui.paneMenu = {
                        pane: p,
                        selection: terminals.get(p.id)?.selection?.()?.trim().slice(0, 160) || '',
                        x: box.left + 8,
                        y: Math.min(box.top + 40, innerHeight - 440),
                      };
                    }
                  }}
                  onpointerdown={(e) => startPaneDrag(e, p, w)}
                  ondblclick={(e) => {
                    if (!(e.target as HTMLElement).closest('button, .pane-actions'))
                      ui.maximize = ui.maximize === p.id ? '' : p.id;
                  }}
                >
                  <div class="pane-title">
                    <span class="terminal-name" title={presentation.name}>{presentation.name}</span>
                    <span
                      class="terminal-type"
                      title={`${presentation.type}${presentation.model ? ' / ' + presentation.model : ''}`}
                      >{presentation.type}</span
                    >
                  </div>
                  {#if p.hostId !== w.hostId}
                    <button
                      onclick={() => showHostConnection(p)}
                      aria-label={`Connection options for ${ui.device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable host'}`}
                      class="cross-host-note recording-highlight"
                      style:background-color={tabColors(
                        ui.device.hosts.find((h) => h.id === p.hostId)?.colors,
                      )?.background}
                      style:color={tabColors(ui.device.hosts.find((h) => h.id === p.hostId)?.colors)
                        ?.foreground}
                      data-highlight={recordingHighlight(ui.device.recordingHighlight)}
                      style:--voice-highlight-color={ui.device.recordingHighlightColor || '#39bdd7'}
                      title={`Connection options for ${ui.device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable'}; workspace host: ${ui.device.hosts.find((h) => h.id === w.hostId)?.name || 'Unavailable'}`}
                    >
                      {#if recordingHighlight(ui.device.recordingHighlight) !== 'static'}
                        <HighlightEffect kind={recordingHighlight(ui.device.recordingHighlight)} />
                      {/if}
                      <Server size={12} /><span
                        >Host: {ui.device.hosts.find((h) => h.id === p.hostId)?.name ||
                          'Unavailable'}</span
                      >
                    </button>
                  {/if}
                  <PaneActions
                    name={p.name}
                    visible={panes.current?.id === w.id && (!ui.maximize || ui.maximize === p.id)}
                  >
                    {#snippet repo()}
                      <button
                        title="Paste a repository orientation prompt into this terminal"
                        aria-label={`Choose repository for ${p.name}`}
                        onclick={() => void task(() => chooseRepo(p))}
                      >
                        <FolderGit2 size={14} /> Repo
                      </button>
                    {/snippet}
                    {#snippet session()}
                      <button
                        title={nativePane(p)
                          ? 'Join or create a PowerShell session'
                          : 'Join or create a tmux session'}
                        aria-label={`${nativePane(p) ? 'PowerShell' : 'tmux'} sessions for ${p.name}`}
                        onclick={() => void task(() => showSessions(p))}
                        ><TerminalIcon size={14} /> {nativePane(p) ? 'Sessions' : 'tmux'}</button
                      >
                    {/snippet}
                    <button
                      disabled={!desktop || dictation.active}
                      title="Open this terminal in a window you can drag to another screen"
                      onclick={() => void detached.detach(p).catch(fail)}
                      ><ArrowUpRight size={14} /> Other screen</button
                    >
                    <button
                      title={`Change connected host: ${ui.device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable'}`}
                      aria-label={`Change host for ${p.name}`}
                      onclick={() => choosePaneHost(p)}><Server size={14} /> Host</button
                    >
                    <button
                      title="Upload a local file to a chosen host"
                      aria-label={`Upload file for ${p.name}`}
                      onclick={() => openFiles(p, 'upload')}><Upload size={14} /> Upload</button
                    >

                    {#if ui.terminalProgress[p.id]?.state}<progress
                        class="pane-progress"
                        aria-label="Terminal task progress"
                        max="100"
                        value={ui.terminalProgress[p.id].state === 3
                          ? undefined
                          : ui.terminalProgress[p.id].value}
                      ></progress>{/if}
                    <button
                      title="Dictate to this terminal"
                      aria-label={`Dictate to ${p.name}`}
                      class:active={dictation.target === p.id && dictation.status === 'listening'}
                      onclick={() => void task(() => record('dictation', p.id))}
                      ><Mic size={14} /> Listen</button
                    ><button
                      class="icon"
                      title={ui.maximize === p.id ? 'Restore pane' : 'Maximize pane'}
                      aria-label={`${ui.maximize === p.id ? 'Restore' : 'Maximize'} ${p.name}`}
                      onclick={() => (ui.maximize = ui.maximize === p.id ? '' : p.id)}
                      >{#if ui.maximize === p.id}<Minimize2 size={14} />{:else}<Maximize2
                          size={14}
                        />{/if}<span class="overflow-label"
                        >{ui.maximize === p.id ? 'Restore' : 'Maximize'}</span
                      ></button
                    ><button
                      class="icon"
                      title={p.shell ? 'Close terminal shell' : 'Close pane; keep tmux session'}
                      aria-label={`Close pane ${p.name}`}
                      onclick={() => closePane(p)}
                      ><X size={14} /><span class="overflow-label">Close</span></button
                    >
                  </PaneActions>
                </div>
                {#if tabs}<div class="terminal-tabs" role="tablist" aria-label="Terminal tabs">
                    {#each tabs.panes as tabId}
                      {@const tab = w.panes.find((pane) => pane.id === tabId)}
                      {#if tab}<button
                          role="tab"
                          data-terminal-tab={tab.id}
                          aria-selected={p.id === tab.id}
                          tabindex={p.id === tab.id ? 0 : -1}
                          onclick={() => {
                            selectPane(tab.id, w);
                            ui.maximize = '';
                            void tick().then(() => terminals.get(tab.id)?.focus?.());
                          }}
                          onkeydown={(event) => {
                            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key))
                              return;
                            event.preventDefault();
                            const index = tabs.panes.indexOf(tab.id);
                            const next =
                              event.key === 'Home'
                                ? 0
                                : event.key === 'End'
                                  ? tabs.panes.length - 1
                                  : (index +
                                      (event.key === 'ArrowLeft' ? -1 : 1) +
                                      tabs.panes.length) %
                                    tabs.panes.length;
                            selectPane(tabs.panes[next], w);
                            ui.maximize = '';
                            const nextId = CSS.escape(tabs.panes[next]);
                            void tick().then(() =>
                              document
                                .querySelector<HTMLButtonElement>(
                                  `[data-pane-id="${nextId}"] [data-terminal-tab="${nextId}"]`,
                                )
                                ?.focus(),
                            );
                          }}>{terminalPresentation(tab, ui.paneStatus[tab.id]).name}</button
                        >{/if}
                    {/each}
                    {#if w.hostId !== HOSTLESS}<button
                        aria-label="New terminal tab"
                        onclick={() => addTerminal('right', p, true)}>+</button
                      >{/if}
                  </div>{/if}
                {#if !p.hideStatus || (dictation.target === p.id && dictation.active)}{@const status =
                    ui.paneStatus[p.id] || unknownStatus}
                  <div class="pane-context">
                    <span
                      class="attachment-status"
                      title={status.attachment === 'unknown'
                        ? 'Attachment could not be verified'
                        : `Verified ${status.attachment}`}
                      ><i
                        class:tmux={status.attachment === 'tmux'}
                        class:plain={status.attachment === 'plain'}
                      ></i>{status.attachment === 'tmux'
                        ? 'tmux'
                        : status.attachment === 'native'
                          ? 'PowerShell'
                          : status.attachment === 'plain'
                            ? 'Plain shell'
                            : 'Unknown'}</span
                    >
                    {#if dictation.target === p.id && dictation.active}
                      <div
                        class="voice-status-alert recording-highlight"
                        title={`${dictation.statusText}. Enter finishes without submitting; Escape abandons capture.`}
                        data-highlight={recordingHighlight(ui.device.recordingHighlight)}
                        style:--voice-highlight-color={ui.device.recordingHighlightColor ||
                          '#39bdd7'}
                        role="status"
                        aria-live="polite"
                      >
                        {#if recordingHighlight(ui.device.recordingHighlight) !== 'static'}<HighlightEffect
                            kind={recordingHighlight(ui.device.recordingHighlight)}
                          />{/if}
                        <span> {dictation.statusText} </span>
                      </div>
                    {/if}
                    <span
                      ><FolderGit2 size={12} />{p.repo ||
                        (p.shell
                          ? 'Plain terminal · no managed tmux session'
                          : nativePane(p)
                            ? 'PowerShell session'
                            : 'tmux session')}</span
                    ><span
                      title={hostLabel(
                        ui.device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable',
                        status,
                      )}
                      >{hostLabel(
                        ui.device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable',
                        status,
                      )}</span
                    >
                  </div>{/if}
                {#if p.error}<div class="pane-message">
                    <p>{p.error}</p>
                    <button onclick={() => void task(() => retry(p))}>Retry launch</button>
                  </div>{:else if p.sessionId || p.shell}<TerminalView
                    settings={{
                      ...(ui.device.terminal || terminalDefaults),
                      clipboard:
                        ui.device.clipboardByHost?.[p.hostId] ||
                        ui.device.terminal?.clipboard ||
                        'notify',
                    }}
                    onclipboard={(characters) =>
                      (ui.notice = `${p.name}: a program on the host copied ${characters} characters to your clipboard.`)}
                    onpastesetting={setPasteConfirmation}
                    watching={() =>
                      detachedPanes.has(p.id) ? detached.focus === p.id : document.hasFocus()}
                    floating={detachedPanes.has(p.id)}
                    onterminalfocus={() => {
                      if (!detachedPanes.has(p.id)) return false;
                      void call('terminal_window_focus', { paneId: p.id }).catch(fail);
                      return true;
                    }}
                    transcript={dictation.target === p.id && dictation.status !== 'idle'
                      ? dictation.text
                      : ''}
                    onprogress={(value) => (ui.terminalProgress[p.id] = value)}
                    theme={ui.device.theme || 'blue'}
                    pane={p}
                    host={ui.device.hosts.find((h) => h.id === p.hostId)}
                    active={w.focused === p.id}
                    visible={!detachedPanes.has(p.id) &&
                      (!tabs || activeTab === p.id) &&
                      panes.current?.id === w.id &&
                      (!ui.maximize || ui.maximize === p.id)}
                    connected={connections[p.hostId] === 'Connected'}
                    ondisconnect={() => void checkConnections(p.hostId)}
                    oncloseview={() => closePane(p)}
                    onreconnect={async () => {
                      const host = ui.device.hosts.find((h) => h.id === p.hostId);
                      if (host) await reconnectHost(host);
                    }}
                    onfocus={() => {
                      if (w.focused !== p.id) w.focused = p.id;
                      setLocalFocus(w.id, p.id);
                    }}
                    onshell={() => {
                      p.sessionId = '';
                      p.stamp = undefined;
                      p.shell = true;
                      p.agent = 'shell';
                      p.model = '';
                      p.effort = '';
                      p.name = 'Terminal';
                      saved(w.hostId);
                    }}
                    suppressFocus={!!searchOrigin}
                    onsearch={() => openTerminalSearch(p.id)}
                    onfile={(path) => openFiles(p, 'documents', w.id, path)}
                    onalert={(message) => terminalAlert(p, message)}
                    onmetadata={(value) => (ui.paneStatus[p.id] = value)}
                    onstate={(s) => (ui.terminalState[p.id] = s)}
                  />{:else}<div class="pane-message">
                    <TerminalIcon size={24} />
                    <p>{desktop ? 'Creating session…' : 'Terminal preview'}</p>
                    {#if !desktop}<code>Connect a host to begin.</code>{/if}
                  </div>{/if}
                <div class="pane-footer">
                  <span title={ui.terminalState[p.id]}
                    >{ui.terminalState[p.id] === 'Attached' || ui.terminalState[p.id] === 'Shell'
                      ? ''
                      : ui.terminalState[p.id]}</span
                  >
                  <span class="spacer"></span>
                  <button
                    disabled={!['codex', 'claude', 'gemini', 'opencode'].includes(p.agent)}
                    title="Open the agent's live picker. Use when its prompt is empty and ready."
                    aria-label={`Open ${p.agent === 'codex' ? 'model and effort' : 'model'} picker for ${p.name}`}
                    onclick={() => void task(() => agentMenu(p))}
                  >
                    {p.agent === 'codex' ? 'Model / effort' : 'Model'}
                    <ChevronDown size={12} />
                  </button>
                  {#if p.agent === 'claude'}<button
                      title="Open Claude's live effort picker. Use when its prompt is empty and ready."
                      aria-label={`Open effort picker for ${p.name}`}
                      onclick={() => void task(() => agentMenu(p, true))}
                      >Effort <ChevronDown size={12} /></button
                    >{/if}
                </div>
                {#if panes.draggingPane && panes.dropTarget?.id === p.id}<div
                    class="dock-guide"
                    aria-hidden="true"
                  >
                    <span class="left">Left</span><span class="right">Right</span>
                    <span class="top">Above</span><span class="bottom">Below</span>
                  </div>{/if}
                {#if panes.dropTarget?.id === p.id}<div
                    class={`drop-preview ${panes.dropTarget.edge}`}
                  >
                    <span
                      >{panes.dropTarget.edge === 'center'
                        ? `${panes.dragCopy ? '+ Duplicate' : 'Move'} here`
                        : `${panes.dragCopy ? '+ Duplicate' : 'Move'}: split ${panes.dropTarget.edge}`}</span
                    >
                  </div>{/if}
              </section>{/if}{/each}
          {#each panes.viewers as viewer, index (viewer.id)}
            <ViewerWindow
              tileId={viewer.id}
              dockRect={panes.workspaceBoxes[viewer.workspaceId]?.[viewer.id] || {
                x: 0,
                y: 0,
                width: 100,
                height: 100,
              }}
              hidden={panes.current?.id !== viewer.workspaceId ||
                (!!ui.maximize && ui.maximize !== viewer.id)}
              maximized={ui.maximize === viewer.id}
              onmaximize={() => (ui.maximize = ui.maximize === viewer.id ? '' : viewer.id)}
              ondrag={(e) => {
                const w = panes.workspaces.find((w) => w.id === viewer.workspaceId);
                if (w) startPaneDrag(e, viewer, w, true);
              }}
              onnew={() => openFiles(viewer.pane, 'documents', viewer.workspaceId)}
              title={viewer.title}
              locked={viewer.locked}
              offset={index % 8}
              layer={viewer.order}
              onactivate={() => {
                if (viewer.order !== panes.viewerOrder) viewer.order = ++panes.viewerOrder;
              }}
              onclose={() => {
                closeViewer(viewer.id);
              }}
            >
              {#if panes.dropTarget?.id === viewer.id}<div
                  class={`drop-preview ${panes.dropTarget.edge}`}
                >
                  <span>Move: {panes.dropTarget.edge}</span>
                </div>{/if}
              <FilesDialog
                hosts={ui.device.hosts}
                inventories={ui.inventories}
                connected={connections}
                initialHost={viewer.pane.hostId}
                initialPath={viewer.initialPath}
                directory={viewer.pane.repo}
                mode="documents"
                onpaste={async () => {}}
                onbusy={(value) => (viewer.locked = value)}
                ondocument={(path, hostId) => {
                  viewer.pane.hostId = hostId;
                  viewer.title = path
                    ? `${ui.device.hosts.find((h) => h.id === hostId)?.name || 'Host'}: ${path}`
                    : 'Files and previews';
                }}
              />
            </ViewerWindow>
          {/each}
        </div>
        {#if !panes.current}<div class="empty-state">
            <Brand large />
            <p class="holding-title">Let's speed things up</p>
            <div class="empty-actions">
              <button
                class="primary"
                onclick={() => {
                  if (!host) {
                    newHost = hostDefaults();
                    ui.modal = 'host';
                  } else showLaunch();
                }}
                ><Plus size={16} />{host ? 'Start a workspace' : 'Connect your first host'}</button
              ><button onclick={() => (ui.modal = 'command')}
                ><Command size={16} /> Explore commands</button
              >
            </div>
          </div>{/if}
      </div>
    </main>
  </div>
</div>
{#if panes.hostlessConnect && panes.hostlessNeeds}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Connect workspace hosts"
      tabindex="-1"
      use:focusDialog
    >
      <HostlessConnect
        {addHost}
        {connect}
        bind:hostlessConnect={panes.hostlessConnect}
        hostlessNeeds={panes.hostlessNeeds}
        {task}
      />
    </div>
  </div>
{/if}
{#if panes.draggingPane}<div
    class="pane-drag-ghost"
    style:left={`${panes.dragPosition.x}px`}
    style:top={`${panes.dragPosition.y}px`}
  >
    {panes.dragCopy
      ? '+ Duplicate terminal'
      : panes.viewers.some((v) => v.id === panes.draggingPane)
        ? 'Move viewer'
        : 'Move terminal'} · Esc to cancel
  </div>{/if}

{#if ui.modal}<div
    class="modal-backdrop"
    role="presentation"
    onpointerdown={(e) => {
      backdropPress = e.target === e.currentTarget;
    }}
    onclick={(e) => {
      if (
        backdropPress &&
        e.target === e.currentTarget &&
        !ui.busy &&
        !panes.filesLocked &&
        !broadcast.busy
      )
        ui.modal = ui.modal === 'changelog' ? changelogReturn : '';
    }}
  >
    <div
      class="modal"
      class:wide={[
        'settings',
        'support',
        'repos',
        'pane-repo',
        'new-session',
        'documents',
        'upload',
      ].includes(ui.modal)}
      use:focusDialog={{ restore: restoreTerminalFocus, key: ui.modal }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-title"
      data-modal={ui.modal}
      tabindex="-1"
    >
      <div class="modal-header">
        <h2 id="modal-title">
          {{
            host: 'Connect a machine',
            transcript: 'Review transcription',
            feedback: supportContent.feedback.title,
            support: supportContent.title,
            'pane-repo': 'Choose repository',
            'pane-host': 'Change terminal host',
            upload: 'Upload to host',
            documents: 'Files and previews',
            'delete-host': 'Delete host',
            'close-native': 'Close PowerShell sessions',
            'close-terminal': 'Close running terminals?',
            'repo-collision': 'Working copy already in use',
            'voice-setup': 'Set up offline dictation',
            'pane-colors': 'Terminal colors',
            'host-colors': 'Host colors',
            'tmux-settings': 'tmux settings',
            changelog: 'Changelog',
            launch: launcher.launchAxis ? 'Add terminals' : 'Start a workspace',
            sessions:
              ui.device.hosts.find((h) => h.id === (launcher.sessionHost || ui.device.activeHost))
                ?.kind === 'powershell'
                ? 'PowerShell sessions'
                : 'tmux sessions',
            'new-session': ['Name your session', 'Choose an agent', 'Choose a repository'][
              launcher.sessionStep
            ],
            rename: 'Rename session',
            destroy: 'Destroy session',
            settings: 'Workspace settings',
            command: 'What would you like to do?',
            broadcast: 'Broadcast message',
            repos: 'Repositories',
            agents: 'Agents on this host',
            worktree: 'Create a worktree',
            workspace: 'Workspace options',
            'listen-intro': 'Dictate into a terminal',
            'terminal-host-connection': 'Terminal host connection',
          }[ui.modal] || 'Dialog'}
        </h2>
        <button
          class="icon"
          aria-label="Close dialog"
          disabled={ui.busy || panes.filesLocked || broadcast.busy}
          onclick={() => (ui.modal = ui.modal === 'changelog' ? changelogReturn : '')}
          ><X size={18} /></button
        >
      </div>
      {#if ui.error}<p class="inline-error" role="alert">{ui.error}</p>{/if}
      {#each Object.keys(downloadProgress) as id}<button
          onclick={() =>
            void call('model_cancel', { id })
              .then(() => {
                downloadProgress[id] = 'Cancelling';
              })
              .catch(fail)}
          >Cancel {voiceModels.models.find((m) => m.id === id)?.name || id} download</button
        >{/each}
      {#if (ui.modal === 'upload' || ui.modal === 'documents') && panes.filesTarget}
        <FilesDialog
          hosts={ui.device.hosts}
          inventories={ui.inventories}
          connected={connections}
          initialHost={panes.filesTarget.hostId}
          directory={panes.filesTarget.repo}
          mode={ui.modal}
          onpaste={pasteFilePath}
          onbusy={(value) => (panes.filesLocked = value)}
        />
      {:else if ui.modal === 'pane-host' && panes.switchTarget}
        <PaneHost
          busy={ui.busy}
          {connections}
          device={ui.device}
          bind:switchHost={panes.switchHost}
          {switchPaneHost}
          {task}
          voiceStatus={dictation.status}
        />
      {:else if ui.modal === 'pane-repo' && repoTarget}
        <PaneRepo
          busy={ui.busy}
          {connections}
          device={ui.device}
          {pasteRepoPrompt}
          {promptFor}
          {refreshRepoChoices}
          {repoChoices}
          bind:repoHost
          bind:repoPath
          {repoTarget}
          {repoWarnings}
          {task}
        />
      {:else if ui.modal === 'terminal-host-connection' && profiles.connectionTarget}
        <TerminalHostConnection
          {connectHostTerminals}
          connectionTarget={profiles.connectionTarget}
          {connections}
          {disconnectHostTerminals}
          {task}
        />
      {:else if ui.modal === 'support'}
        <SupportDialog
          version={releaseVersion(clientInfo.version)}
          status={updates.status || updates.label}
          updateState={updates.available ? 'available' : updates.state}
          updateAvailable={updates.available ? releaseVersion(updates.available.version) : ''}
          updateBusy={updates.busy}
          updateSupported={desktop && clientInfo.updateSupported}
          onfeedback={openFeedback}
          glow={ui.device.supportGlow !== false}
          highlight={recordingHighlight(ui.device.recordingHighlight)}
          highlightColor={ui.device.recordingHighlightColor || '#39bdd7'}
          onglowchange={(enabled) => {
            ui.device.supportGlow = enabled;
            persistDevice();
          }}
          onupdate={() => {
            openUpdates();
            if (!updates.available) void checkUpdates(true);
          }}
        />
      {:else if ui.modal === 'feedback' && ui.device.feedbackContact}
        <FeedbackDialog
          appName="DazeDev"
          bind:name={ui.device.feedbackContact.name}
          bind:email={ui.device.feedbackContact.email}
          bind:message={feedbackMessage}
          busy={feedbackBusy}
          sent={feedbackSent}
          error={feedbackError}
          oncontactchange={persistDevice}
          onmessagechange={() => (feedbackSent = false)}
          onsubmit={() => void sendFeedback()}
          onclose={() => (ui.modal = '')}
        />
      {:else if ui.modal === 'repo-collision'}
        <RepoCollision bind:modal={ui.modal} {useSharedWorkingCopy} />
      {:else if ui.modal === 'close-native'}
        <CloseNative
          busy={ui.busy}
          {closeAnyway}
          closingApp={panes.closingApp}
          {finishNativeClose}
          {cancelNativeClose}
          {task}
        />
      {:else if ui.modal === 'close-terminal'}
        <CloseTerminal bind:modal={ui.modal} {confirmPendingClose} />
      {:else if ui.modal === 'listen-intro'}
        <ListenIntro
          {bindingFor}
          bind:device={ui.device}
          bind:modal={ui.modal}
          {openSettings}
          {persistDevice}
          startListening={() => record('dictation', dictation.listenIntroTarget)}
          {task}
          voiceReady={voiceModels.ready}
        />
      {:else if ui.modal === 'voice-setup'}
        <VoiceSetup
          busy={ui.busy}
          {changeVoiceModel}
          bind:device={ui.device}
          {downloadProgress}
          bind:modal={ui.modal}
          models={voiceModels.models}
          {openSettings}
          {persistDevice}
          {setupVoice}
          switchingVoice={voiceModels.switching}
          {task}
          voiceCacheLoading={voiceModels.cacheLoading}
          voiceStatus={dictation.status}
        />
      {:else if ui.modal === 'changelog'}
        <Changelog
          availableUpdate={updates.available}
          {changelogReturn}
          {historyStatus}
          bind:modal={ui.modal}
          bind:releaseFilter
          {releaseHistory}
        />
      {:else if ui.modal === 'delete-host' && profiles.deleteHostTarget}
        <DeleteHost
          busy={ui.busy}
          {deleteHost}
          deleteHostTarget={profiles.deleteHostTarget}
          bind:modal={ui.modal}
          {task}
        />
      {:else if ui.modal === 'host-colors' && colorHost}
        <HostColors {colorHost} bind:device={ui.device} {persistDevice} />
      {:else if ui.modal === 'tmux-settings' && tmuxHost}
        <TmuxSettings
          host={tmuxHost}
          appScrollback={ui.device.terminal?.scrollback || terminalDefaults.scrollback}
        />
      {:else if ui.modal === 'pane-colors' && ui.colorPane}
        <PaneColors
          bind:colorPane={ui.colorPane}
          device={ui.device}
          {updateWorkspace}
          workspaces={panes.workspaces}
        />
      {:else if ui.modal === 'workspace' && workspaceTarget}
        <WorkspaceOptions
          {closeWorkspace}
          device={ui.device}
          bind:modal={ui.modal}
          bind:profiles={ui.profiles}
          bind:renameValue={ui.renameValue}
          {saved}
          {updateWorkspace}
          bind:workspaceTarget
        />
      {:else if ui.modal === 'host'}
        <HostForm
          {aliases}
          busy={ui.busy}
          {connect}
          bind:device={ui.device}
          bind:modal={ui.modal}
          bind:newHost
          {persistDevice}
          {task}
        />
      {:else if ui.modal === 'launch'}
        <Launch
          busy={ui.busy}
          {connections}
          device={ui.device}
          bind:form={ui.form}
          inventories={ui.inventories}
          {launch}
          launchAxis={launcher.launchAxis}
          bind:launchChooseRepo={launcher.launchChooseRepo}
          launchFromCommand={launcher.launchFromCommand}
          {task}
        />
      {:else if ui.modal === 'sessions'}
        <Sessions
          {attach}
          busy={ui.busy}
          bind:destroyTarget={ui.destroyTarget}
          device={ui.device}
          bind:modal={ui.modal}
          {refreshSessions}
          sessionHost={launcher.sessionHost}
          sessionPane={launcher.sessionPane}
          sessions={ui.sessions}
          {startSession}
          {task}
        />
      {:else if ui.modal === 'new-session'}
        <NewSession
          busy={ui.busy}
          {createSession}
          device={ui.device}
          bind:form={ui.form}
          inv={launcher.inv}
          bind:modal={ui.modal}
          bind:nameError={launcher.nameError}
          selectedAgent={launcher.selectedAgent}
          sessionHost={launcher.sessionHost}
          bind:sessionStep={launcher.sessionStep}
          {task}
        />
      {:else if ui.modal === 'rename'}
        <RenameSession busy={ui.busy} {renameSession} bind:renameValue={ui.renameValue} {task} />
      {:else if ui.modal === 'destroy'}
        <DestroySession
          busy={ui.busy}
          {destroySession}
          destroyTarget={ui.destroyTarget}
          device={ui.device}
          bind:modal={ui.modal}
          {task}
        />
      {:else if ui.modal === 'broadcast'}
        <BroadcastDialog
          bind:text={broadcast.text}
          bind:allHosts={broadcast.allHosts}
          bind:allWorkspaces={broadcast.allWorkspaces}
          hostName={broadcast.hostName}
          workspaceName={broadcast.workspaceName}
          phase={dictation.mode === 'broadcast' ? dictation.status : 'idle'}
          busy={broadcast.busy}
          snapshot={broadcast.snapshot}
          result={broadcast.result}
          onrecord={() => void task(() => record('broadcast'))}
          onreview={() => void task(reviewBroadcast)}
          onsend={() => void task(sendBroadcast)}
          onclose={() => {
            if (!broadcast.busy) {
              if (dictation.mode === 'broadcast' && dictation.status !== 'idle')
                void task(cancelVoice);
              ui.modal = '';
            }
          }}
        />
      {:else if ui.modal === 'command'}
        <CommandDialog
          busy={ui.busy}
          bind:commandError={commands.error}
          bind:commandReview={commands.review}
          {commandSubmit}
          bind:commandText={commands.text}
          {implementCommand}
          {record}
          {task}
          voiceMode={dictation.mode}
          voicePreparing={dictation.preparing}
          voiceStatus={dictation.status}
        />
      {:else if ui.modal === 'settings'}
        <div class="settings-compact settings-page">
          <UpdatesSection
            availableUpdate={updates.available}
            {checkUpdates}
            {clientInfo}
            bind:device={ui.device}
            {openUpdates}
            {persistDevice}
            {showChangelog}
            updateBusy={updates.busy}
            updateLabel={updates.label}
            updateStatus={updates.status}
          />

          <PinSettings />
          <DictionarySettings
            compact
            hosts={ui.device.hosts.filter((h) => h.kind === 'ssh' || h.kind === 'tailscale')}
          />
          <VoiceModelsSection
            busy={ui.busy}
            {changeVoiceModel}
            bind:device={ui.device}
            {downloadModel}
            {downloadProgress}
            hardware={voiceModels.hardware}
            hardwareError={voiceModels.hardwareError}
            models={voiceModels.models}
            modelsLoaded={voiceModels.modelsLoaded}
            recommendation={voiceModels.recommendation}
            {removeModel}
            {selectVoiceModel}
            {setupVoice}
            switchingVoice={voiceModels.switching}
            {task}
            voiceCacheLoading={voiceModels.cacheLoading}
            voiceReady={voiceModels.ready}
            voiceStatus={dictation.status}
          />
          <VoiceHotkeysSection
            {bindingFor}
            {captureShortcut}
            capturingShortcut={dictation.capturingShortcut}
            {changeVoiceModel}
            bind:device={ui.device}
            microphoneError={voiceModels.microphoneError}
            microphoneLoading={voiceModels.microphoneLoading}
            microphones={voiceModels.microphones}
            {persistDevice}
            {refreshMicrophones}
            {saveShortcut}
            shortcutStatus={dictation.shortcutStatus}
            switchingVoice={voiceModels.switching}
            {task}
            voiceCacheLoading={voiceModels.cacheLoading}
            voiceStatus={dictation.status}
          />
          <AppearanceSection bind:device={ui.device} {persistDevice} {systemReducedMotion} />
          <TerminalSection
            bind:device={ui.device}
            {fail}
            {persistDevice}
            {setPasteConfirmation}
            {softAlert}
          />
          <ShortcutsSection
            bind:appShortcutStatus
            bind:capturingAppShortcut
            device={ui.device}
            {resetAppShortcuts}
          />
          <ConnectionsSection
            {addHost}
            bind:connections={net.connections}
            bind:device={ui.device}
            {disconnectHost}
            bind:modal={ui.modal}
            bind:newHost
            {persistDevice}
            settingsHost={profiles.settingsHost}
            {task}
          />
          <HostPreferencesSection
            device={ui.device}
            bind:guidance={profiles.guidance}
            profiles={ui.profiles}
            bind:promptTemplate={profiles.promptTemplate}
            {promptValues}
            {reloadHostProfile}
            bind:roots={profiles.roots}
            {saveHostPreferences}
            settingsHost={profiles.settingsHost}
            {task}
          />
          <details class="settings-section settings-disclosure">
            <summary>Licenses and credits</summary>
            <p class="hint">Portable data: {clientInfo.dataPath || 'beside the executable'}</p>
            <Licenses />
          </details>
        </div>
      {:else if ui.modal === 'repos'}
        <Repos
          busy={ui.busy}
          {cloneRepo}
          device={ui.device}
          bind:form={ui.form}
          githubRepos={launcher.githubRepos}
          inventories={ui.inventories}
          {loadGithubRepos}
          {refresh}
          search={ui.search}
          {showLaunch}
          {task}
        />
      {:else if ui.modal === 'agents'}
        <Agents
          busy={ui.busy}
          device={ui.device}
          {host}
          inventories={ui.inventories}
          {refresh}
          {startAgentFromPicker}
          {task}
        />
      {:else if ui.modal === 'worktree'}
        <Worktree
          bind:branch={commands.branch}
          busy={ui.busy}
          {createWorktree}
          bind:destination={commands.destination}
          {sendWorktree}
          {task}
          bind:worktreeQuestion={commands.worktreeQuestion}
        />
      {/if}
    </div>
  </div>{/if}

{#if updates.prompt && updateCanPresent}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="DazeDev updates"
      tabindex="-1"
      use:focusDialog
    >
      <UpdatePrompt
        availableUpdate={updates.available}
        busy={ui.busy}
        {checkUpdates}
        {clientInfo}
        {closeUpdates}
        {installUpdate}
        {showChangelog}
        updateBusy={updates.busy}
        updateLabel={updates.label}
        updateProgress={updates.progress}
        updateState={updates.state}
        updateStatus={updates.status}
        voiceStatus={dictation.status}
      />
    </div>
  </div>
{/if}

{#if ui.paneMenu}
  <div
    class="pane-menu-dismiss"
    role="presentation"
    onpointerdown={() => (ui.paneMenu = null)}
  ></div>
  <div
    class="pane-menu"
    role="dialog"
    aria-label="Terminal actions"
    tabindex="-1"
    use:focusDialog
    style={`left:${ui.paneMenu.x}px;top:${ui.paneMenu.y}px;max-height:calc(100vh - ${ui.paneMenu.y + 8}px)`}
  >
    <TerminalActionMenu
      items={panes.menuItems}
      order={ui.device.paneMenuOrder}
      onorder={(order) => {
        ui.device.paneMenuOrder = order;
        persistDevice();
      }}
      onsplit={(edge) => addTerminal(edge, ui.paneMenu!.pane)}
      transfers={panes.workspaces.map((w) => ({
        id: w.id,
        label: `${w.hostId === HOSTLESS ? 'Hostless' : ui.device.hosts.find((h) => h.id === w.hostId)?.name} / ${w.name}`,
      }))}
      transferDisabled={dictation.active}
      ontransfer={(id, duplicate) => {
        const destination = panes.workspaces.find((w) => w.id === id);
        if (destination) menuPaneDrop(ui.paneMenu!.pane, destination, duplicate);
      }}
    />
  </div>
{/if}{#if ui.dictionarySelection !== null}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Add dictation phrase"
      tabindex="-1"
      use:focusDialog
    >
      <button onclick={() => (ui.dictionarySelection = null)}>Close</button>
      <DictionarySettings
        hosts={ui.device.hosts.filter((h) => h.kind === 'ssh' || h.kind === 'tailscale')}
        selection={ui.dictionarySelection}
        ondone={() => (ui.dictionarySelection = null)}
      />
    </div>
  </div>
{/if}
{#if closeFailure}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="close-failure-title"
      tabindex="-1"
      use:focusDialog
    >
      <CloseFailure {closeAnyway} bind:closeFailure {task} />
    </div>
  </div>
{/if}
{#if browserRequest}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="browser-request-title"
      aria-describedby="browser-request-url"
      tabindex="-1"
      use:focusDialog={{ key: browserRequest.requestId }}
    >
      <BrowserRequest
        {browserRequest}
        {browserRequests}
        bind:browserTrust
        {decideBrowserRequest}
        device={ui.device}
      />
    </div>
  </div>
{/if}
