<script lang="ts">
  import TerminalSearch from './components/TerminalSearch.svelte';
  import type { SearchTarget, SearchHit, SearchNavigation } from './lib/terminal-search';
  import { SvelteMap } from 'svelte/reactivity';
  import type { WindowTerminalState } from './lib/terminal-window';
  import { MixedHostRecovery, type HostRecovery } from './lib/mixed-host-recovery';
  import { refreshHostIdentity } from './lib/host-identity';
  import PinSettings from './components/PinSettings.svelte';
  import TerminalActionMenu from './components/TerminalActionMenu.svelte';
  import type { TerminalMenuItem } from './lib/terminal-menu';
  import BroadcastDialog from './components/BroadcastDialog.svelte';
  import {
    snapshotBroadcast,
    broadcastRevision,
    createBroadcastDelivery,
    type BroadcastSnapshot,
  } from './lib/broadcast';
  import { voiceDestination, type CaptureMode } from './lib/voice-routing';
  import DictionarySettings from './components/DictionarySettings.svelte';
  import { dictionaryState, startDictionarySync } from './lib/dictionary-store';
  import { applyFinalDictionary } from './lib/dictionary';
  let dictionarySelection = $state<string | null>(null);

  import PaneActions from './components/PaneActions.svelte';
  import RepoPicker from './components/RepoPicker.svelte';
  import { needsChangelog } from './lib/repositories';
  import FilesDialog from './components/FilesDialog.svelte';
  import ViewerWindow from './components/ViewerWindow.svelte';
  import Licenses from './components/Licenses.svelte';
  import { hostLabel, terminalPresentation, unknownStatus, type PaneStatus } from './lib/pane-status';
  import { diagnostic, exportDiagnostics } from './lib/diagnostics';
  import { onMount, tick } from 'svelte';
  import { sessionNameError } from './lib/session-name';
  import { recommendVoice, accelerationAdvice, type VoiceHardware } from './lib/hardware';
  import { fade } from 'svelte/transition';
  import HighlightEffect from './components/HighlightEffect.svelte';
  import { recordingHighlights, recordingHighlight } from './lib/highlights';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import {
    Plus,
    Terminal as TerminalIcon,
    Server,
    Mic,
    Settings,
    ChevronDown,
    ChevronRight,
    Columns2,
    Rows2,
    Maximize2,
    Minimize2,
    X,
    RefreshCw,
    Command,
    ArrowUpRight,
    FolderGit2,
    GitBranch,
    SlidersHorizontal,
    Search,
    Download,
    Trash2,
    PanelLeftClose,
    PanelLeftOpen,
    Square,
    Check,
    Send,
    MoveHorizontal,
    MoreHorizontal,
    Unplug,
    Radio,
    LayoutGrid,
    Headphones,
    Palette,
    Upload,
  } from '@lucide/svelte';
  import {
    defaultPrompt,
    promptVariables,
    renderPrompt,
    unknownVariables,
    type PromptValues,
  } from './lib/prompt';
  import { terminals, terminalDefaults, deliverDictation } from './lib/terminals';
  import ColorPicker from './components/ColorPicker.svelte';
  import { tabColors, retainedPaneColors } from './lib/colors';
  import TmuxSettings from './components/TmuxSettings.svelte';
  import Brand from './components/Brand.svelte';
  import SupportDialog from '../donate/SupportDialog.svelte';
  import FeedbackDialog from '../donate/templates/FeedbackDialog.svelte';
  import { content as supportContent } from '../donate/adapter';
  import {
    defaultShortcut,
    defaultCommandShortcut,
    sameShortcut,
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
    type Layout,
    type Device,
    type Profile,
    type Inventory,
    type Session,
    type ModelInfo,
    defaults,
    blankProfile,
    uid,
  } from './lib/types';
  import {
    tile,
    split,
    remove,
    resize,
    swap,
    rebindOwner,
    columns,
    balanced,
    rectangles,
    insertAt,
    dock,
    dropZone,
    ids,
    type Edge,
  } from './lib/layout';
  import {
    createNativeTerminalPane,
    selectPanes,
    removePanes,
    insertPaneStack,
    reorderWorkspaces,
  } from './lib/pane-transfer';
  import { terminalProjection, reconcileViewerLayout } from './lib/viewer-layout';
  import { HOSTLESS, workspaceHosts } from './lib/workspace-groups';
  import { ReviewHotkey } from './lib/review-hotkey';
  import {
    cleanTabGroups,
    terminalTabs,
    tabLayout,
    retainTabPanes,
    tabSelections,
  } from './lib/terminal-tabs';
  import { call, on, rpc, desktop } from './lib/bridge';
  import { parseCommand, commandCatalogue, type Action } from './lib/commands';
  import { reviewCommand, consumeReview, type CommandReview } from './lib/command-review';
  import {
    connectionDeadline,
    HOST_HEALTH_TIMEOUT,
    ConnectionOperations,
  } from './lib/connection-health';
  import { UpdateCheck, pollUpdates, canPresentUpdate } from './lib/update-checks';
  import { version as appVersion } from '../package.json';
  import { Capture } from './lib/audio';
  import { VoiceCaptureKeys } from './lib/voice-capture-keys';
  import { RecordingTail } from './lib/recording-tail';
  import {
    modelDescriptions,
    requiredVoiceModels,
    voiceStackReady,
    listMicrophones,
  } from './lib/voice-setup';
  import { changelog, mergeChangelog, releaseHeading, type ReleaseEntry } from './lib/changelog';
  import { releaseVersion } from './lib/release-version';
  import { focusDialog } from './lib/focus';
  let device = $state<Device>({
      ...defaults(),
      inputMode: 'toggle',
      highlightMotion: 'animate',
      terminal: { ...terminalDefaults },
    }),
    profiles = $state<Record<string, Profile>>({}),
    inventories = $state<Record<string, Inventory>>({}),
    connections = $state<Record<string, string>>({}),
    logs = $state<Record<string, string>>({}),
    saveState = $state<Record<string, string>>({}),
    terminalState = $state<Record<string, string>>({});
  let modal = $state(''),
    error = $state(''),
    notice = $state(''),
    busy = $state(false),
    maximize = $state(''),
    search = $state(''),
    newHost = $state<Host>(hostDefaults()),
    sessions = $state<Session[]>([]),
    models = $state<ModelInfo[]>([]),
    downloadProgress = $state<Record<string, string>>({}),
    microphones = $state<MediaDeviceInfo[]>([]);
  let form = $state({
      hostId: '',
      name: '',
      repo: '',
      identity: '',
      agent: 'shell',
      model: '',
      effort: '',
      count: 1,
      brief: true,
      exactName: false,
    }),
    renameValue = $state(''),
    renameTarget = $state<Pane | null>(null),
    destroyTarget = $state<(Session & { hostId?: string }) | null>(null),
    roots = $state(''),
    guidance = $state(''),
    promptTemplate = $state(defaultPrompt),
    settingsHost = $state(''),
    githubRepos = $state<{ full_name: string; description: string }[]>([]),
    branch = $state(''),
    destination = $state('');
  let dictation = $state(''),
    raw = $state(''),
    voiceStatus = $state('idle'),
    voicePreparing = $state('Preparing…'),
    cleanupPart = $state(0),
    cleanupTotal = $state(0),
    cleanupWords = $state(0),
    voiceMode = $state<CaptureMode>('dictation'),
    voiceTarget = $state(''),
    voiceBackend = $state(''),
    commandText = $state('');
  let commandReview = $state<CommandReview | null>(null);
  let commandError = $state('');
  let capture: Capture | undefined;
  const recordingTail = new RecordingTail();
  let recordingId = '';
  let finishWithoutSubmit = false;
  let shortcutHeld: 'dictation' | 'command' | null = null;
  let releasePending = false;
  let terminalProgress = $state<Record<string, { state: number; value: number }>>({});
  let agentTarget = $state<Pane | null>(null);
  let repoTarget = $state<Pane | null>(null);
  let repoPath = $state('');
  let repoHost = $state('');
  let repoContext = '';
  let repoChoices = $state<Repo[]>([]);
  let repoWarnings = $state<string[]>([]);
  let detachedFocus = $state('');
  function appHasFocus() {
    return document.hasFocus() || !!detachedFocus;
  }
  const detachedPanes = new SvelteMap<string, string>();
  function windowState(pane: Pane, workspace: Workspace): WindowTerminalState {
    return {
      pane: $state.snapshot(pane),
      host: $state.snapshot(device.hosts.find((h) => h.id === pane.hostId)),
      workspaceName: workspace.name,
      workspaceColors: $state.snapshot(workspace.colors),
      theme: device.theme || 'blue',
      inputShortcut: device.inputShortcut || defaultShortcut,
      commandShortcut: device.commandShortcut || defaultCommandShortcut,
      voiceStatus,
      voiceMode,
      voiceTarget,
      dictation,
      settings: {
        ...(device.terminal || terminalDefaults),
        clipboard: device.clipboardByHost?.[pane.hostId] || device.terminal?.clipboard || 'write',
      },
      status: terminalState[pane.id] || '',
      ready: !!terminals.get(pane.id)?.context(),
      metadata: paneStatus[pane.id],
    };
  }
  function publishTerminal(paneId: string, value: unknown) {
    if (detachedPanes.has(paneId))
      void call('terminal_window_publish', { paneId, value }).catch(() => {});
  }
  $effect(() => {
    for (const [id] of detachedPanes) {
      const owner = workspaces.find((w) => w.panes.some((p) => p.id === id));
      const pane = owner?.panes.find((p) => p.id === id);
      if (!owner || !pane) {
        detachedPanes.delete(id);
        void call('terminal_window_close', { paneId: id }).catch(fail);
      } else publishTerminal(id, { kind: 'state', state: windowState(pane, owner) });
    }
  });
  async function detachTerminal(pane: Pane, x?: number, y?: number, dragging = false) {
    if (!desktop) throw new Error('Terminal windows are available in the desktop app.');
    if (dictationActive || modal)
      throw new Error('Finish the current action before moving this terminal.');
    const owner = workspaces.find((w) => w.panes.some((p) => p.id === pane.id));
    if (!owner || !terminals.get(pane.id)) throw new Error('Wait for the terminal to be ready.');
    detachedPanes.set(pane.id, owner.id);
    try {
      await call('terminal_window_open', {
        paneId: pane.id,
        workspaceId: owner.id,
        title: `${owner.name} / ${terminalPresentation(pane, paneStatus[pane.id]).name} - DazeDev`,
        x: x ?? null,
        y: y ?? null,
        dragging,
      });
      paneMenu = null;
    } catch (error) {
      detachedPanes.delete(pane.id);
      throw error;
    }
  }
  async function dockTerminal(id: string) {
    const owner = workspaces.find((w) => w.panes.some((p) => p.id === id));
    detachedPanes.delete(id);
    await call('terminal_window_close', { paneId: id });
    if (owner) {
      selectWorkspace(owner);
      owner.focused = id;
    }
    await tick();
    terminals.get(id)?.focus?.();
  }
  async function windowRequest(event: {
    paneId: string;
    requestId: string;
    action: string;
    args: Record<string, unknown>;
  }) {
    const { paneId, requestId, action, args } = event;
    const owner = workspaces.find((w) => w.panes.some((p) => p.id === paneId));
    const pane = owner?.panes.find((p) => p.id === paneId);
    const terminal = terminals.get(paneId);
    try {
      if (!detachedPanes.has(paneId) || !owner || !pane || !terminal)
        throw new Error('Terminal window is no longer attached.');
      let result: unknown = null;
      if (action === 'snapshot')
        result = { state: windowState(pane, owner), snapshot: await terminal.snapshot?.() };
      else if (action === 'search-open' || action === 'search-results') {
        if (action === 'search-results') searchPanel?.showResults();
        else openTerminalSearch(paneId);
        await getCurrentWindow().show();
        await getCurrentWindow().setFocus();
      } else if (action === 'search-step')
        await searchPanel?.step(Number(args.direction) < 0 ? -1 : 1);
      else if (action === 'search-close') closeTerminalSearch();
      else if (action === 'input') {
        await terminal.input?.(String(args.data || ''));
        result = terminal.context();
      } else if (action === 'binary') await terminal.binary?.(String(args.data || ''));
      else if (action === 'resize') await terminal.resize?.(Number(args.cols), Number(args.rows));
      else if (action === 'paste') {
        await terminal.paste(String(args.text || ''), false, String(args.context || ''));
        result = terminal.context();
      } else if (action === 'reconnect') {
        terminal.resume?.();
        const host = device.hosts.find((h) => h.id === pane.hostId);
        if (host) await reconnectHost(host);
      } else if (action === 'focus') {
        owner.focused = paneId;
        (device.localFocus ||= {})[owner.id] = paneId;
        device.activeWorkspace = owner.id;
        device.activeHost = owner.hostId;
        persistDevice();
      } else if (action === 'dock') {
        await call('terminal_window_publish', { paneId, value: { requestId, result: null } });
        await dockTerminal(paneId);
        return;
      } else if (action === 'key') {
        const options = {
          key: String(args.key || ''),
          code: String(args.code || ''),
          ctrlKey: !!args.ctrlKey,
          altKey: !!args.altKey,
          shiftKey: !!args.shiftKey,
          metaKey: !!args.metaKey,
          repeat: !!args.repeat,
        };
        window.dispatchEvent(
          new KeyboardEvent(args.type === 'keyup' ? 'keyup' : 'keydown', options),
        );
      } else if (action === 'pointer') {
        window.dispatchEvent(
          new PointerEvent(args.type === 'pointerup' ? 'pointerup' : 'pointerdown', {
            button: Number(args.button),
          }),
        );
      } else if (['repo', 'tmux', 'host', 'model', 'close'].includes(action)) {
        if (modal || dictationActive)
          throw new Error('Finish the current action in the workspace window first.');
        selectWorkspace(owner);
        owner.focused = paneId;
        await getCurrentWindow().show();
        await getCurrentWindow().unminimize();
        await getCurrentWindow().setFocus();
        if (action === 'repo') await chooseRepo(pane);
        if (action === 'tmux') await showSessions(pane);
        if (action === 'host') showHostConnection(pane);
        if (action === 'model') await agentMenu(pane);
        if (action === 'close') closePane(pane);
      } else throw new Error('Unsupported terminal window action.');
      await call('terminal_window_publish', { paneId, value: { requestId, result } });
    } catch (error) {
      await call('terminal_window_publish', {
        paneId,
        value: { requestId, error: String(error) },
      }).catch(() => {});
    }
  }
  let terminalReturnFocus = '';
  function restoreTerminalFocus() {
    const id = terminalReturnFocus;
    terminalReturnFocus = '';
    if (!id) return false;
    void tick().then(() => terminals.get(id)?.focus?.());
    return true;
  }
  let repoRequest = 0;
  async function refreshRepoChoices() {
    const request = ++repoRequest;
    const host = repoHost;
    repoChoices = [];
    repoWarnings = [];
    const result = await rpc<{ repos: Repo[]; warnings: string[] }>(host, 'repositories');
    if (request !== repoRequest || host !== repoHost || modal !== 'pane-repo') return;
    repoChoices = result.repos;
    repoWarnings = [...new Set(result.warnings)];
  }
  async function chooseRepo(p: Pane) {
    repoTarget = p;
    repoHost = p.hostId;
    repoPath = p.repo;
    repoContext = terminals.get(p.id)?.context() || '';
    search = '';
    modal = 'pane-repo';
    await refreshRepoChoices();
    if (repoTarget?.id === p.id && modal === 'pane-repo') repoPath = p.repo;
  }
  async function pasteRepoPrompt() {
    const owner = workspaces.find((w) => w.panes.some((p) => p.id === repoTarget?.id));
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
    terminalReturnFocus = target.id;
    modal = '';
    notice = 'Repository prompt pasted for review. Press Enter in the terminal when ready.';
  }
  let alertAudio: AudioContext | undefined;
  let lastTerminalAlert = 0;
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
    if (Date.now() - lastTerminalAlert < 3000) return;
    lastTerminalAlert = Date.now();
    if (device.terminalAlerts?.visual) notice = `${pane.name}: ${message}`;
    if (device.terminalAlerts?.sound) void softAlert().catch(() => {});
  }
  let paneStatus = $state<Record<string, PaneStatus>>({});
  type FileContext = Pick<Pane, 'id' | 'hostId' | 'repo'>;
  let filesTarget = $state<FileContext | null>(null);
  let filesContext = '';
  let filesLocked = $state(false);
  let viewers = $state<
    {
      id: string;
      workspaceId: string;
      pane: FileContext;
      initialPath?: string;
      locked: boolean;
      title: string;
      order: number;
    }[]
  >([]);
  let viewerLayouts = $state<Record<string, Layout>>({});
  let viewerOrder = 0;
  const viewerLocked = $derived(viewers.some((v) => v.locked));
  function openFiles(
    p: FileContext,
    mode: 'upload' | 'documents',
    workspaceId?: string,
    initialPath = '',
  ) {
    if (mode === 'documents') {
      const owner =
        workspaces.find((w) => w.id === workspaceId) ||
        workspaces.find((w) => w.panes.some((x) => x.id === p.id)) ||
        current;
      if (!owner) {
        filesTarget = p;
        filesContext = '';
        filesLocked = false;
        modal = 'documents';
        paneMenu = null;
        return;
      }
      const id = uid();
      const prior = layoutFor(owner);
      viewerLayouts[owner.id] = ids(prior).filter(Boolean).length
        ? insertAt(prior, ids(prior).includes(p.id) ? p.id : ids(prior).at(-1)!, id, 'right')
        : { kind: 'pane' as const, id };
      viewers.push({
        id,
        workspaceId: owner.id,
        pane: { ...p },
        locked: false,
        title: 'Files and previews',
        initialPath,
        order: ++viewerOrder,
      });
      maximize = '';
      paneMenu = null;
      return;
    }
    filesTarget = p;
    filesContext = terminals.get(p.id)?.context() || '';
    filesLocked = false;
    modal = mode;
    paneMenu = null;
  }
  async function pasteFilePath(text: string) {
    const terminal = filesTarget && terminals.get(filesTarget.id);
    if (!terminal || !filesContext || terminal.context() !== filesContext)
      throw new Error('Terminal changed. Copy the path or reopen Upload for the current terminal.');
    await terminal.pasteForReview(text, filesContext);
    filesContext = terminal.context();
  }
  function toggleStatus(p: Pane) {
    p.hideStatus = !p.hideStatus;
    const owner = workspaces.find((w) => w.panes.some((x) => x.id === p.id));
    if (owner) saved(owner.hostId);
    paneMenu = null;
  }
  let switchTarget = $state<Pane | null>(null);
  let switchHost = $state('');
  function choosePaneHost(p: Pane) {
    switchTarget = p;
    switchHost = p.hostId;
    modal = 'pane-host';
    paneMenu = null;
  }
  async function switchPaneHost() {
    const target = switchTarget;
    const owner = workspaces.find((w) => w.panes.some((p) => p.id === target?.id));
    if (!target || !owner) throw new Error('Terminal no longer exists.');
    if (voiceStatus !== 'idle')
      throw new Error('Finish dictation before changing this connection.');
    if (connections[switchHost] !== 'Connected')
      throw new Error('Connect the selected host first.');
    if (switchHost === target.hostId) {
      modal = '';
      return;
    }
    const replacement: Pane = {
      ...target,
      id: uid(),
      hostId: switchHost,
      hostKey: device.hosts.find((h) => h.id === switchHost)?.fingerprint,
      sessionId: '',
      stamp: undefined,
      shell: true,
      name: 'Terminal',
      repo: '',
      agent: 'shell',
      identity: '',
      model: '',
      effort: '',
      operation: uid(),
      error: undefined,
    };
    await call('terminal_close', { id: target.id });
    updateWorkspace({
      ...owner,
      panes: owner.panes.map((p) => (p.id === target.id ? replacement : p)),
      layout: swap(layoutFor(owner), target.id, replacement.id),
      focused: replacement.id,
    });
    if (maximize === target.id) maximize = replacement.id;
    modal = '';
  }
  let revisionTimers: Record<string, ReturnType<typeof setTimeout>> = {};
  let saveChains: Record<string, Promise<void>> = {};
  let changeSeq: Record<string, number> = {};
  let deviceTimer: ReturnType<typeof setTimeout>;
  let colorPane = $state<Pane | null>(null);
  let colorHost = $state<Host | null>(null);
  let tmuxHost = $state<Host | null>(null);
  let deleteHostTarget = $state<Host | null>(null);
  let workspaceTarget = $state<Workspace | null>(null);
  let aliases = $state<string[]>([]);
  let clientInfo = $state({
    version: appVersion,
    dataPath: '',
    migration: '',
    updateSupported: true,
  });
  let availableUpdate = $state<{ version: string; notes: string; size: number } | null>(null);
  let updateStatus = $state(''),
    updateBusy = $state(false),
    updateProgress = $state(0),
    updatePrompt = $state(false);
  const updateCheck = new UpdateCheck<{ version: string; notes: string; size: number } | null>();
  let updateState = $state<
    'unchecked' | 'checking' | 'current' | 'available' | 'error' | 'installing'
  >('unchecked');
  let updateReturn = '';
  let automaticUpdatePending = $state(false);
  const updateLabel = $derived(
    !desktop || !clientInfo.updateSupported
      ? 'Manual updates'
      : updateState === 'checking'
        ? 'Checking…'
        : updateState === 'installing'
          ? 'Installing…'
          : updateState === 'error'
            ? 'Check failed'
            : availableUpdate
              ? 'Update available'
              : updateState === 'current'
                ? 'Up to date'
                : 'Not checked',
  );
  let modelsLoaded = $state(false),
    microphoneError = $state(''),
    microphoneLoading = $state(false);
  let changelogReturn = $state('settings');
  let releaseHistory = $state<ReleaseEntry[]>(changelog);
  let releaseFilter = $state<'all' | 'desktop' | 'mobile'>('all');
  let historyStatus = $state('');
  let pendingChangelog = $state('');
  $effect(() => {
    if (pendingChangelog && !modal && !busy) {
      device.lastChangelogVersion = pendingChangelog;
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
    device.feedbackContact ||= { name: '', email: '' };
    feedbackSent = false;
    error = '';
    modal = 'feedback';
  }
  async function sendFeedback() {
    if (feedbackBusy) return;
    feedbackError = '';
    feedbackSent = false;
    feedbackBusy = true;
    try {
      clearTimeout(deviceTimer);
      await call('device_save', { value: { ...$state.snapshot(device), recovery } });
      await call('feedback_send', {
        feedback: { ...$state.snapshot(device.feedbackContact), message: feedbackMessage },
      });
      feedbackMessage = '';
      feedbackSent = true;
      if (modal !== 'feedback') notice = supportContent.feedback.success;
    } catch (e) {
      feedbackError = String(e);
    } finally {
      feedbackBusy = false;
    }
  }
  const voiceReady = $derived(modelsLoaded && voiceStackReady(device.voice, models));
  $effect(() => {
    document.documentElement.dataset.theme = device.theme || 'blue';
    document.documentElement.dataset.highlightMotion = device.highlightMotion || 'animate';
  });
  function addHost() {
    newHost = hostDefaults();
    error = '';
    modal = 'host';
  }
  function showChangelog(origin = 'settings') {
    changelogReturn = origin;
    releaseHistory = mergeChangelog(device.releaseHistory);
    modal = 'changelog';
    if (desktop) {
      historyStatus = 'Checking public release history…';
      void call<unknown>('release_history')
        .then((value) => {
          releaseHistory = mergeChangelog(value, releaseHistory);
          device.releaseHistory = releaseHistory;
          persistDevice();
          historyStatus = '';
        })
        .catch(() => {
          historyStatus = 'Showing saved release history. Public history is currently unavailable.';
        });
    }
  }
  async function respond(hostId: string, value: string) {
    const attempt = hostAttempts.get(hostId);
    logs[hostId] = '';
    try {
      await call('host_auth', { hostId, data: value });
    } catch (e) {
      if (hostAttempts.get(hostId) === attempt) throw e;
    }
  }
  async function refreshMicrophones(requestPermission = true) {
    microphoneError = '';
    microphoneLoading = true;
    try {
      if (!navigator.mediaDevices)
        throw new Error('Microphone access is unavailable in this window.');
      microphones = await listMicrophones(navigator.mediaDevices, requestPermission);
    } catch (e) {
      microphoneError = `Microphone access: ${String(e)}. Allow microphone access in Windows privacy settings, then refresh.`;
    } finally {
      microphoneLoading = false;
    }
  }
  async function setupVoice() {
    for (const id of requiredVoiceModels(device.voice)) {
      if (models.find((m) => m.id === id)?.installed) continue;
      downloadProgress[id] = 'Starting';
      try {
        await call('model_download', { id });
      } finally {
        delete downloadProgress[id];
        await loadModels();
      }
    }
    device.voiceSetupDismissed = true;
    persistDevice();
    notice = 'Voice stack ready to go.';
  }
  let sessionPane = $state<Pane | null>(null);
  let sessionHost = $state('');
  let sessionStep = $state(0);
  let nameError = $state('');
  let hardware = $state<VoiceHardware | null>(null);
  let hardwareError = $state('');
  let switchingVoice = $state(false);
  const recommendation = $derived(hardware ? recommendVoice(hardware) : null);
  let voiceCacheLoading = $state(false);
  let voiceCacheTask: Promise<void> | undefined;
  let voiceCacheSignature = '';
  async function configureVoiceCache() {
    if (!desktop || voiceStatus !== 'idle') return;
    if (voiceCacheTask) return voiceCacheTask;
    const retain = device.voice.keepLoaded ?? null;
    const installed = (id: string) => models.some((m) => m.id === id && m.installed);
    if (retain && (!installed(device.voice.model) || !installed('silero'))) return;
    const options = {
      model: device.voice.model,
      backend: device.voice.backend,
      cleanup: device.voice.cleanup && installed('s1-mini'),
      retain,
    };
    const signature = JSON.stringify(options);
    if (signature === voiceCacheSignature) return;
    voiceCacheLoading = true;
    try {
      voiceCacheTask = call<void>('voice_cache', options);
      await voiceCacheTask;
      voiceCacheSignature = signature;
    } finally {
      voiceCacheTask = undefined;
      voiceCacheLoading = false;
    }
  }
  async function changeVoiceModel() {
    switchingVoice = true;
    try {
      await call('voice_release');
      voiceCacheSignature = '';
      voiceBackend = '';
      persistDevice();
      await configureVoiceCache();
    } catch (e) {
      fail(e);
    } finally {
      switchingVoice = false;
    }
  }
  async function selectVoiceModel(id: string) {
    if (switchingVoice || voiceCacheLoading || voiceStatus !== 'idle') return;
    switchingVoice = true;
    try {
      if (!models.find((m) => m.id === id)?.installed) {
        downloadProgress[id] = 'Starting';
        await call('model_download', { id });
        await loadModels();
      }
      await call('voice_release');
      voiceCacheSignature = '';
      device.voice.model = id;
      voiceBackend = '';
      persistDevice();
      await configureVoiceCache();
    } finally {
      delete downloadProgress[id];
      switchingVoice = false;
    }
  }
  let sessionOperation = $state('');
  let stageWidth = $state(1200);
  let draggingPane = $state('');
  let selectedPanes = $state<string[]>([]);
  let draggingPanes = $state<string[]>([]);
  let selectionWorkspace = '';
  function selectPane(paneId: string, workspace: Workspace, additive = false) {
    const group = terminalTabs(workspace, paneId);
    if (group) (device.activeTerminalTabs ||= {})[group.id] = paneId;
    if (selectionWorkspace !== workspace.id) selectedPanes = [];
    selectionWorkspace = workspace.id;
    selectedPanes = selectPanes(selectedPanes, paneId, additive);
    workspace.focused = paneId;
    (device.localFocus ||= {})[workspace.id] = paneId;
    persistDevice();
  }
  const updateCanPresent = $derived(canPresentUpdate(modal, voiceStatus, !!draggingPane));
  $effect(() => {
    if (automaticUpdatePending && updateCanPresent && updateState === 'available') {
      automaticUpdatePending = false;
      updateReturn = '';
      updatePrompt = true;
    }
  });
  let dropTarget = $state<{ id: string; edge: Edge | 'center' } | null>(null);
  let paneMenu = $state<{ pane: Pane; x: number; y: number; selection?: string } | null>(null);
  type VoiceMode = 'dictation' | 'command';
  let capturingShortcut = $state<VoiceMode | null>(null);
  const reviewHotkey = new ReviewHotkey();
  let listenIntroTarget = '';
  let shortcutStatus = $state('');
  let backdropPress = false;
  function bindingFor(mode: VoiceMode) {
    return mode === 'dictation'
      ? device.inputShortcut || defaultShortcut
      : device.commandShortcut || defaultCommandShortcut;
  }
  async function configureShortcut() {
    // Disable the legacy OS-wide sampler. DOM events belong only to this window.
    if (desktop) await call('input_configure', { binding: null });
    shortcutStatus = 'Both hotkeys work only while DazeDev is focused.';
  }
  async function saveShortcut(
    binding: Shortcut,
    mode: VoiceMode = capturingShortcut || 'dictation',
  ) {
    if (sameShortcut(binding, bindingFor(mode === 'dictation' ? 'command' : 'dictation')))
      throw new Error('Choose different hotkeys for terminal dictation and IDE commands.');
    if (mode === 'dictation') device.inputShortcut = binding;
    else device.commandShortcut = binding;
    capturingShortcut = null;
    persistDevice();
    await configureShortcut();
  }
  function captureShortcut(mode: VoiceMode) {
    capturingShortcut = mode;
    shortcutStatus = 'Press a key combination, or choose a mouse/device button.';
  }
  async function inputEdge(pressed: boolean, mode: VoiceMode) {
    if (capturingShortcut || (pressed && !appHasFocus())) return;
    const destination = voiceDestination(mode, modal);
    if (voiceStatus !== 'idle' && voiceMode !== destination) return;
    if (broadcastBusy || (broadcastResult && modal === 'broadcast')) return;
    if (device.inputMode !== 'hold') {
      if (
        pressed &&
        mode === 'dictation' &&
        destination === 'dictation' &&
        voiceStatus === 'idle' &&
        recentDictation?.submitContext &&
        !modal
      ) {
        const reviewed = recentDictation;
        const target = activePane?.id;
        reviewHotkey.tap(
          () => {
            if (appHasFocus() && !modal && activePane?.id === target)
              void task(() => record(destination));
          },
          () => {
            if (!appHasFocus() || modal || activePane?.id !== reviewed.target) {
              notice = 'Select the terminal containing the reviewed dictation before sending.';
              return;
            }
            const expected = reviewed.submitContext!;
            reviewed.submitContext = undefined;
            void task(async () => {
              const terminal = terminals.get(reviewed.target);
              if (!terminal?.submitReviewed)
                throw new Error(
                  'Terminal is unavailable. The dictation remains available to copy.',
                );
              await terminal.submitReviewed(expected);
              terminal.focus?.();
            });
          },
        );
        return;
      }
      if (pressed) await record(destination);
      return;
    }
    if (pressed) {
      if (shortcutHeld || voiceStatus !== 'idle') return;
      shortcutHeld = mode;
      releasePending = false;
      await record(destination);
      if (releasePending && String(voiceStatus) === 'listening') await record(destination);
    } else if (shortcutHeld === mode) {
      shortcutHeld = null;
      releasePending = true;
      if (voiceStatus === 'listening') await record(destination);
    }
  }
  async function agentPicker(p: Pane | null = null) {
    agentTarget = p;
    if (p) device.activeHost = p.hostId;
    await refresh(p?.hostId || device.activeHost);
    modal = 'agents';
  }
  async function agentMenu(p: Pane, effort = false) {
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
      throw new Error(
        'The agent in this terminal changed. Refresh its controls before choosing a model.',
      );
    }
    await terminal.paste(
      effort && p.agent === 'claude' ? '/effort' : p.agent === 'opencode' ? '/models' : '/model',
      true,
      expectedContext,
    );
    terminals.get(p.id)?.focus?.();
  }
  async function startAgentFromPicker(agent: string) {
    sessionPane = agentTarget;
    sessionHost = agentTarget?.hostId || device.activeHost;
    await startSession();
    form.agent = agent;
    form.name = `${agent}-${Date.now().toString(36)}`;
    await createSession();
  }
  function addTerminal(edge: Edge, target = activePane, asTab = false) {
    if (!current) return;
    let w = current;
    if (w.hostId === HOSTLESS) {
      notice =
        'Open a terminal in a host workspace, then hold Ctrl and drag its view into this group.';
      return;
    }
    target ||= {
      id: ids(layoutFor(w)).find(Boolean) || '',
      hostId: w.hostId,
      hostKey: device.hosts.find((h) => h.id === w.hostId)?.fingerprint,
    } as Pane;
    let pane: Pane = {
      id: uid(),
      hostId: target.hostId,
      hostKey: target.hostKey,
      sessionId: '',
      shell: true,
      name: `Terminal ${w.panes.length + 1}`,
      repo: '',
      identity: '',
      agent: 'shell',
      model: '',
      effort: '',
      operation: uid(),
    };
    const insert = () => {
      const owner = profiles[w.hostId]?.workspaces.find((item) => item.id === w.id);
      if (!owner) return false;
      w = owner;
      maximize = '';
      paneMenu = null;
      updateWorkspace({
        ...w,
        panes: [...w.panes, pane],
        layout: ids(layoutFor(w)).filter(Boolean).length
          ? insertAt(
              layoutFor(w),
              ids(layoutFor(w)).includes(target.id) ? target.id : ids(layoutFor(w))[0],
              pane.id,
              edge,
            )
          : { kind: 'pane', id: pane.id },
        focused: pane.id,
        tabGroups: asTab
          ? [
              ...(w.tabGroups || []).filter((group) => !group.panes.includes(target!.id)),
              {
                id: terminalTabs(w, target.id)?.id || uid(),
                panes: [...(terminalTabs(w, target.id)?.panes || [target.id]), pane.id],
              },
            ]
          : w.tabGroups,
      });
      const updated = profiles[w.hostId].workspaces.find((item) => item.id === w.id)!;
      selectPane(pane.id, updated);
      return true;
    };
    if (nativePane(pane)) {
      void task(async () => {
        pane = await createNativeTerminalPane(pane, (args) => rpc<Session>(pane.hostId, 'create', args));
        if (!insert()) await rpc(pane.hostId, 'destroy', { id: pane.sessionId, stamp: pane.stamp });
      });
    } else insert();
  }
  let dragCopy = $state(false);
  let dragHover = $state('');
  let dragPosition = $state({ x: 0, y: 0 });
  let cancelPaneDrag: (() => void) | null = null;
  async function preparePaneDrop(
    source: Workspace,
    destination: Workspace,
    original: Pane,
    copy: boolean,
  ) {
    let pane = original;
    if (destination.hostId === HOSTLESS && !copy)
      throw new Error(
        'Hold Ctrl while dragging to add a view to this hostless workspace. The original stays in place.',
      );
    if (source.id !== destination.id) {
      const header = document.querySelector<HTMLElement>(
        `[data-pane-id="${CSS.escape(original.id)}"] .pane-header`,
      );
      const style = header ? getComputedStyle(header) : null;
      const toHex = (value: string | undefined, fallback: string) => {
        const rgb = value?.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        return rgb
          ? '#' +
              rgb
                .slice(1, 4)
                .map((v) => Number(v).toString(16).padStart(2, '0'))
                .join('')
          : fallback;
      };
      pane = {
        ...original,
        colors: retainedPaneColors(
          original.colors,
          source.colors,
          device.hosts.find((h) => h.id === original.hostId)?.colors,
          {
            background: toHex(style?.backgroundColor, '#242822'),
            foreground: toHex(style?.color, '#edede8'),
          },
        ),
      };
    }
    if (copy) {
      const terminal = terminals.get(original.id);
      const context = terminal?.context();
      const status = desktop
        ? await rpc<import('./lib/pane-status').PaneStatus>(original.hostId, 'terminal_status', {
            pid: terminal?.processId() || 0,
          })
        : {
            attachment: original.shell ? 'plain' : 'tmux',
            sessionId: original.sessionId,
            stamp: original.stamp,
            nestedSsh: false,
            directory: undefined,
          };
      if (desktop && (!context || terminal?.context() !== context))
        throw new Error('The source terminal changed. Try duplicating it again.');
      if (status.attachment === 'unknown' || status.nestedSsh)
        throw new Error(
          'Cannot identify this terminal’s session safely. Return to its managed host shell and try again.',
        );
      const attached = status.attachment === 'tmux' || status.attachment === 'native';
      if (destination.hostId === HOSTLESS && !attached)
        throw new Error(
          'Hostless workspaces need a persistent session. Open or create a managed terminal on its host first.',
        );
      if (destination.hostId === HOSTLESS && (!status.stamp || !original.hostKey))
        throw new Error(
          'Reconnect the source host before cloning this view so its host and session identities can be verified.',
        );
      if (attached && !status.sessionId)
        throw new Error(
          'The host helper needs reconnecting before this session can be duplicated.',
        );
      pane = {
        ...pane,
        id: uid(),
        operation: uid(),
        error: undefined,
        sessionId: attached ? status.sessionId! : '',
        stamp: attached ? status.stamp : undefined,
        shell: !attached,
        directory: attached ? undefined : status.directory,
        agent: attached ? original.agent : 'shell',
        model: attached ? original.model : '',
        effort: attached ? original.effort : '',
        name: attached ? original.name : 'Terminal',
      };
    }
    return pane;
  }
  async function commitPaneDrop(
    sourceId: string,
    destinationId: string,
    paneIds: string | string[],
    target: { id: string; edge: Edge | 'center' },
    copy: boolean,
    sidebar = false,
  ) {
    const source = workspaces.find((w) => w.id === sourceId);
    const destination = workspaces.find((w) => w.id === destinationId);
    const wanted = new Set(typeof paneIds === 'string' ? [paneIds] : paneIds);
    const originals = source?.panes.filter((p) => wanted.has(p.id));
    if (!source || !destination || !originals?.length || originals.length !== wanted.size) return;
    if (sidebar && !copy && sourceId === destinationId) return;
    const prepared = await Promise.all(
      originals.map((pane) => preparePaneDrop(source, destination, pane, copy)),
    );
    const from = workspaces.find((w) => w.id === sourceId);
    const to = workspaces.find((w) => w.id === destinationId);
    if (
      !from ||
      !to ||
      originals.some((p) => from.panes.find((x) => x.id === p.id) !== p) ||
      (target.id && !ids(layoutFor(to)).includes(target.id))
    )
      return;
    if (!copy && from.id === to.id && wanted.has(target.id)) return;
    const moving = originals.map((p) => p.id);
    const remaining =
      !copy && from.id === to.id ? removePanes(layoutFor(to), moving) : layoutFor(to);
    const incoming = prepared.map((p) => p.id);
    let layout: Layout;
    if (sidebar || !ids(remaining).some(Boolean)) layout = insertPaneStack(remaining, incoming);
    else if (target.edge === 'center') {
      const order = ids(remaining);
      order.splice(Math.max(0, order.indexOf(target.id)), 0, ...incoming);
      layout = balanced(order, stageWidth);
    } else layout = insertPaneStack(remaining, incoming, target.id, target.edge);
    if (!copy && from.id !== to.id) {
      const panes = from.panes.filter((p) => !wanted.has(p.id));
      updateWorkspace({
        ...from,
        panes,
        layout: removePanes(layoutFor(from), moving),
        focused: wanted.has(from.focused) ? panes[0]?.id || '' : from.focused,
      });
    }
    updateWorkspace({
      ...to,
      panes: [...to.panes.filter((p) => copy || !wanted.has(p.id)), ...prepared],
      layout,
      focused: incoming[0],
    });
    selectedPanes = [];
    if (sidebar) {
      notice = `${prepared.length === 1 ? prepared[0].name : prepared.length + ' terminals'} ${copy ? 'duplicated in' : 'moved to'} ${to.name}.`;
      if (maximize && wanted.has(maximize) && !copy) maximize = '';
    } else selectWorkspace(to);
  }
  function commitViewerDrop(
    id: string,
    destinationId: string,
    target: { id: string; edge: Edge | 'center' },
    sidebar = false,
  ) {
    const viewer = viewers.find((v) => v.id === id);
    const from = workspaces.find((w) => w.id === viewer?.workspaceId);
    const to = workspaces.find((w) => w.id === destinationId);
    if (
      !viewer ||
      !from ||
      !to ||
      target.id === id ||
      (sidebar && from.id === to.id) ||
      (target.id && !ids(layoutFor(to)).includes(target.id))
    )
      return;
    const sourceLayout = remove(layoutFor(from), id) || { kind: 'pane' as const, id: '' };
    const remaining = from.id === to.id ? sourceLayout : layoutFor(to);
    let layout: Layout;
    if (sidebar) layout = insertPaneStack(remaining, [id]);
    else if (!ids(remaining).filter(Boolean).length) layout = { kind: 'pane' as const, id };
    else if (target.edge === 'center') {
      const order = ids(remaining);
      order.splice(Math.max(0, order.indexOf(target.id)), 0, id);
      layout = balanced(order, stageWidth);
    } else layout = insertAt(remaining, target.id, id, target.edge);
    viewer.workspaceId = to.id;
    if (from.id !== to.id) updateWorkspace({ ...from, layout: sourceLayout });
    updateWorkspace({ ...to, layout });
    maximize = '';
    if (!sidebar) selectWorkspace(to);
  }
  function closeViewer(id: string) {
    const viewer = viewers.find((v) => v.id === id);
    if (!viewer || viewer.locked) return;
    const owner = workspaces.find((w) => w.id === viewer.workspaceId);
    const layout = owner && remove(layoutFor(owner), id);
    viewers = viewers.filter((v) => v.id !== id);
    if (owner) updateWorkspace({ ...owner, layout: layout || { kind: 'pane' as const, id: '' } });
    if (maximize === id) maximize = '';
  }
  function menuPaneDrop(pane: Pane, destination: Workspace, copy: boolean) {
    const source = workspaces.find((w) => w.panes.some((p) => p.id === pane.id));
    const target =
      destination.panes.find((p) => p.id === destination.focused && (copy || p.id !== pane.id)) ||
      destination.panes.find((p) => copy || p.id !== pane.id);
    paneMenu = null;
    if (!source || (!copy && source.id === destination.id && !target)) return;
    void task(() =>
      commitPaneDrop(
        source.id,
        destination.id,
        pane.id,
        { id: target?.id || '', edge: 'right' },
        copy,
      ),
    );
  }
  function startPaneDrag(
    e: PointerEvent,
    p: Pick<Pane, 'id'>,
    workspace: Workspace,
    viewer = false,
  ) {
    if (
      e.button !== 0 ||
      (e.target as HTMLElement).closest('button, .pane-actions') ||
      dictationActive ||
      modal
    )
      return;
    cancelPaneDrag?.();
    if (!viewer) {
      if (e.shiftKey) selectPane(p.id, workspace, true);
      else if (selectionWorkspace !== workspace.id || !selectedPanes.includes(p.id))
        selectPane(p.id, workspace);
    }
    const group = viewer
      ? [p.id]
      : workspace.panes.filter((pane) => selectedPanes.includes(pane.id)).map((pane) => pane.id);
    const header = e.currentTarget as HTMLElement;
    const origin = device.activeWorkspace,
      originHost = device.activeHost,
      originMax = maximize;
    const startX = e.clientX,
      startY = e.clientY;
    let moved = false;
    let destination = '',
      last = e;
    let sidebar = false;
    header.setPointerCapture(e.pointerId);
    const locate = (event: PointerEvent) => {
      dragCopy = !viewer && event.ctrlKey;
      dragPosition = { x: event.clientX + 14, y: event.clientY + 14 };
      const hit = document.elementFromPoint(event.clientX, event.clientY);
      const tab = hit?.closest<HTMLElement>('[data-workspace-tab]')?.dataset.workspaceTab || '';
      dragHover = tab;
      sidebar = false;
      if (tab) {
        dropTarget = null;
        destination = '';
        if (workspaces.some((w) => w.id === tab) && (dragCopy || tab !== workspace.id)) {
          destination = tab;
          dropTarget = { id: '', edge: 'right' };
          sidebar = true;
        }
        return;
      }
      const element = hit?.closest<HTMLElement>('[data-pane-id]');
      const w =
        element &&
        workspaces.find(
          (w) =>
            w.id === device.activeWorkspace &&
            ids(layoutFor(w)).includes(element.dataset.paneId || ''),
        );
      dropTarget = null;
      destination = '';
      // Headers and navigation are never docking targets.
      if (
        !w ||
        !element ||
        hit?.closest('.pane-header') ||
        (!dragCopy && group.includes(element.dataset.paneId || ''))
      ) {
        const empty = hit?.closest<HTMLElement>('[data-empty-workspace]');
        if (empty) {
          destination = empty.dataset.emptyWorkspace!;
          dropTarget = { id: '', edge: 'center' };
        }
        return;
      }
      const r = element.getBoundingClientRect();
      destination = w.id;
      dropTarget = {
        id: element.dataset.paneId!,
        edge: dropZone((event.clientX - r.left) / r.width, (event.clientY - r.top) / r.height),
      };
    };
    const move = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      last = event;
      if (!moved && Math.hypot(event.clientX - startX, event.clientY - startY) < 6) return;
      if (!group.length) return;
      moved = true;
      draggingPane = p.id;
      draggingPanes = group;
      paneMenu = null;
      if (
        !viewer &&
        group.length === 1 &&
        !event.ctrlKey &&
        desktop &&
        (event.clientX < 0 ||
          event.clientX > innerWidth ||
          event.clientY < 0 ||
          event.clientY > innerHeight)
      ) {
        cleanup();
        restore();
        const pane = workspace.panes.find((pane) => pane.id === group[0]);
        if (pane)
          void detachTerminal(pane, event.screenX - 120, event.screenY - 24, true).catch(fail);
        return;
      }
      locate(event);
    };
    const cleanup = () => {
      draggingPane = '';
      draggingPanes = [];
      dropTarget = null;
      dragHover = '';
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', abort);
      window.removeEventListener('keydown', key, true);
      window.removeEventListener('keyup', key, true);
      window.removeEventListener('blur', abort);
      header.removeEventListener('lostpointercapture', abort);
      if (header.hasPointerCapture(e.pointerId)) header.releasePointerCapture(e.pointerId);
      cancelPaneDrag = null;
    };
    const restore = () => {
      device.activeWorkspace = origin;
      device.activeHost = originHost;
      maximize = originMax;
    };
    const abort = () => {
      cleanup();
      restore();
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        abort();
      } else if (event.key === 'Control' && moved)
        locate(
          new PointerEvent('pointermove', {
            clientX: last.clientX,
            clientY: last.clientY,
            ctrlKey: event.type === 'keydown',
          }),
        );
    };
    const finish = (event: PointerEvent) => {
      if (event.pointerId !== e.pointerId) return;
      if (moved) locate(event);
      const target = dropTarget,
        to = destination,
        copy = dragCopy;
      cleanup();
      if (!moved || !target || !to) {
        if (!moved && !viewer && !e.shiftKey) selectPane(p.id, workspace);
        restore();
        return;
      }
      const direct = sidebar;
      void task(async () => {
        try {
          if (viewer) commitViewerDrop(p.id, to, target, direct);
          else await commitPaneDrop(workspace.id, to, group, target, copy, direct);
        } catch (error) {
          restore();
          throw error;
        }
      });
    };
    cancelPaneDrag = abort;
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', abort);
    window.addEventListener('keydown', key, true);
    window.addEventListener('keyup', key, true);
    window.addEventListener('blur', abort);
    header.addEventListener('lostpointercapture', abort);
  }
  const terminalMenuItems = $derived.by((): TerminalMenuItem[] => {
    if (!paneMenu) return [];
    const p = paneMenu.pane;
    const close = (run: () => void) => () => {
      run();
      paneMenu = null;
    };
    const owner = workspaces.find((w) => w.panes.some((pane) => pane.id === p.id));
    const items: TerminalMenuItem[] = [{ id: 'split', label: 'Split', kind: 'split' }];
    if (owner?.hostId !== HOSTLESS)
      items.push({
        id: 'new-tab',
        label: 'New terminal tab',
        run: close(() => addTerminal('right', p, true)),
      });
    if (owner && terminalTabs(owner, p.id))
      items.push({
        id: 'ungroup-tab',
        label: 'Move tab into its own pane',
        run: close(() => {
          updateWorkspace({
            ...owner,
            tabGroups: (owner.tabGroups || []).map((group) => ({
              ...group,
              panes: group.panes.filter((id) => id !== p.id),
            })),
          });
        }),
      });
    if (owner && selectedPanes.length > 1)
      items.push({
        id: 'group-tabs',
        label: 'Group selected terminals as tabs',
        run: close(() => {
          const members = owner.panes
            .filter((pane) => selectedPanes.includes(pane.id))
            .map((pane) => pane.id);
          if (members.length < 2) return;
          const group = { id: uid(), panes: members };
          updateWorkspace({
            ...owner,
            tabGroups: [
              ...(owner.tabGroups || []).map((g) => ({
                ...g,
                panes: g.panes.filter((id) => !members.includes(id)),
              })),
              group,
            ],
          });
          (device.activeTerminalTabs ||= {})[group.id] = p.id;
          persistDevice();
        }),
      });
    if (recentDictation?.target === p.id)
      items.push(
        {
          id: 'copy-last',
          label: 'Copy last dictation',
          run: close(() => {
            void navigator.clipboard.writeText(recentDictation!.text).catch(fail);
          }),
        },
        {
          id: 'copy-original',
          label: 'Copy original dictation',
          run: close(() => {
            void navigator.clipboard.writeText(recentDictation!.raw).catch(fail);
          }),
        },
      );
    if (dictation && voiceStatus === 'idle')
      items.push(
        {
          id: 'review-undelivered',
          label: 'Review undelivered dictation',
          run: close(() => {
            modal = 'transcript';
          }),
        },
        {
          id: 'copy-undelivered',
          label: 'Copy undelivered dictation',
          run: close(() => {
            void navigator.clipboard.writeText(dictation).catch(fail);
          }),
        },
        {
          id: 'copy-original-undelivered',
          label: 'Copy original undelivered dictation',
          run: close(() => {
            void navigator.clipboard.writeText(raw).catch(fail);
          }),
        },
        {
          id: 'discard-undelivered',
          label: 'Discard undelivered dictation',
          run: close(() => {
            dictation = '';
            raw = '';
          }),
        },
      );
    items.push({ id: 'transfer', label: 'Move or duplicate to workspace', kind: 'transfer' });
    const selection = paneMenu.selection || '';
    items.push({
      id: 'dictionary',
      label: selection
        ? 'Add selection to dictation dictionary'
        : 'Add dictation dictionary phrase',
      run: close(() => {
        dictionarySelection = selection;
      }),
    });
    items.push(
      { id: 'host', label: 'Change host', run: () => choosePaneHost(p) },
      { id: 'upload', label: 'Upload file', run: () => openFiles(p, 'upload') },
      { id: 'files', label: 'Files and previews', run: () => openFiles(p, 'documents') },
      {
        id: 'status',
        label: p.hideStatus ? 'Show status line' : 'Hide status line',
        run: () => toggleStatus(p),
      },
      {
        id: 'copy',
        label: 'Copy',
        run: close(() => {
          void terminals.get(p.id)?.copy();
        }),
      },
      {
        id: 'paste',
        label: 'Paste',
        run: close(() => {
          void task(async () => {
            const terminal = terminals.get(p.id);
            if (terminal?.pasteClipboard) await terminal.pasteClipboard();
            else await terminal?.paste(await navigator.clipboard.readText());
          });
        }),
      },
      {
        id: 'select-all',
        label: 'Select all',
        run: close(() => {
          terminals.get(p.id)?.selectAll();
        }),
      },
      {
        id: 'find',
        label: 'Find',
        run: close(() => {
          terminals.get(p.id)?.search();
        }),
      },
      {
        id: 'export',
        label: 'Export terminal output',
        run: close(() => {
          terminals.get(p.id)?.exportOutput();
        }),
      },
      {
        id: 'clear',
        label: 'Clear scrollback',
        run: close(() => {
          terminals.get(p.id)?.clear();
        }),
      },
      {
        id: 'colors',
        label: 'Terminal colors',
        run: close(() => {
          colorPane = p;
          modal = 'pane-colors';
        }),
      },
      {
        id: 'maximize',
        label: 'Maximise / restore',
        run: close(() => {
          maximize = maximize === p.id ? '' : p.id;
        }),
      },
    );
    if (p.sessionId)
      items.push({
        id: 'rename',
        label: nativePane(p) ? 'Rename PowerShell session' : 'Rename tmux session',
        run: close(() => {
          renameTarget = p;
          renameValue = p.name;
          modal = 'rename';
        }),
      });
    items.push({ id: 'close', label: 'Close', run: close(() => closePane(p)) });
    return items;
  });
  function contextPane(
    e: MouseEvent,
    p: Pane,
    selection = terminals.get(p.id)?.selection?.() || '',
  ) {
    e.preventDefault();
    paneMenu = {
      pane: p,
      selection: selection.trim().slice(0, 160),
      x: Math.max(8, Math.min(e.clientX, innerWidth - 316)),
      y: Math.max(8, Math.min(e.clientY, innerHeight - 420)),
    };
  }
  function captureTerminalSelection(node: HTMLElement, pane: Pane) {
    const capture = (event: MouseEvent) => {
      const selection = terminals.get(pane.id)?.selection?.() || '';
      if (!selection.trim()) return;
      event.stopPropagation();
      contextPane(event, pane, selection);
    };
    node.addEventListener('contextmenu', capture, true);
    return {
      update(value: Pane) {
        pane = value;
      },
      destroy() {
        node.removeEventListener('contextmenu', capture, true);
      },
    };
  }
  $effect(() => {
    if (capturingShortcut && modal !== 'settings') {
      capturingShortcut = null;
      void task(() => configureShortcut());
    }
  });
  let launchAxis = $state<'row' | 'column' | null>(null);
  $effect(() => {
    if (
      voiceMode === 'command' &&
      modal !== 'command' &&
      (voiceStatus === 'loading' ||
        voiceStatus === 'listening' ||
        voiceStatus === 'finishing-recording')
    )
      void cancelVoice().catch(fail);
  });
  let launchFromCommand = $state(false);
  let launchChooseRepo = $state(false);
  let launchWorkspaceId = '';
  let recovery: Record<string, Profile> = {};
  let mounted = false;
  let profileBlocked: Record<string, boolean> = {};
  const workspaces = $derived(Object.values(profiles).flatMap((p) => p.workspaces));
  const workspaceLayouts = $derived(
    Object.fromEntries(
      workspaces.map((w) => [
        w.id,
        tabLayout(
          reconcileViewerLayout(
            terminalProjection(viewerLayouts[w.id] || w.layout, [
              ...w.panes.filter((p) => !detachedPanes.has(p.id)).map((p) => p.id),
              ...viewers.filter((v) => v.workspaceId === w.id).map((v) => v.id),
            ]),
            terminalProjection(
              w.layout,
              w.panes.filter((p) => !detachedPanes.has(p.id)).map((p) => p.id),
            ) || { kind: 'pane' as const, id: '' },
            viewers.filter((v) => v.workspaceId === w.id).map((v) => v.id),
          ) || { kind: 'pane' as const, id: '' },
          cleanTabGroups(
            w.tabGroups,
            w.panes.map((p) => p.id),
          ),
          tabSelections(w, device.activeTerminalTabs),
        ),
      ]),
    ),
  );
  function layoutFor(w: Workspace): Layout {
    return workspaceLayouts[w.id] || w.layout;
  }
  const workspaceBoxes = $derived(
    Object.fromEntries(workspaces.map((w) => [w.id, rectangles(layoutFor(w))])),
  );
  const current = $derived(workspaces.find((w) => w.id === device.activeWorkspace));
  let searchOrigin = $state<SearchTarget | null>(null);
  let searchPanel = $state<TerminalSearch>();
  const searchTargets = $derived(
    workspaces.flatMap((w) =>
      w.panes.map((p) => ({
        paneId: p.id,
        hostId: p.hostId,
        host: device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable host',
        workspaceId: w.id,
        workspace: w.name,
        terminal: p.name,
        session: [
          p.agent,
          paneStatus[p.id]?.sessionId ||
            inventories[p.hostId]?.sessions.find((session) => session.id === p.sessionId)?.name ||
            (p.shell ? 'Plain shell' : p.sessionId),
        ]
          .filter(Boolean)
          .join(' · '),
      })),
    ),
  );
  function openTerminalSearch(paneId = current?.focused || '') {
    if (modal || dictationActive) {
      notice = 'Finish the current action before searching.';
      return;
    }
    const origin =
      searchTargets.find((target) => target.paneId === paneId) ||
      searchTargets.find((target) => !!terminals.get(target.paneId)?.context());
    if (!origin) {
      notice = 'Select a live terminal to search.';
      return;
    }
    paneMenu = null;
    searchOrigin = null;
    void tick().then(() => {
      searchOrigin = origin;
    });
  }
  function closeTerminalSearch() {
    const id = current?.focused;
    searchOrigin = null;
    if (id) void tick().then(() => terminals.get(id)?.focus?.());
  }
  async function jumpSearch(hit: SearchHit) {
    if (dictationActive) throw Error('Stop dictation before switching terminals.');
    const owner = workspaces.find((w) => w.panes.some((p) => p.id === hit.paneId));
    const controls = terminals.get(hit.paneId);
    if (!owner || !controls?.revealMatch?.(hit.id)) return false;
    selectWorkspace(owner);
    owner.focused = hit.paneId;
    (device.localFocus ||= {})[owner.id] = hit.paneId;
    persistDevice();
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
      publishTerminal(hit.paneId, {
        kind: 'search-selection',
        selection: controls.searchSelection?.(),
      });
    return true;
  }
  function publishSearchNavigation(value: SearchNavigation | null, hit?: SearchHit) {
    for (const [id] of detachedPanes)
      publishTerminal(id, {
        kind: 'search-navigation',
        navigation: id === hit?.paneId ? value : null,
      });
    if (value && hit && detachedPanes.has(hit.paneId))
      requestAnimationFrame(() => {
        void call('terminal_window_focus', { paneId: hit.paneId }).catch(fail);
      });
  }

  const host = $derived(device.hosts.find((h) => h.id === device.activeHost));
  const inv = $derived(inventories[form.hostId || device.activeHost]);
  const activePane = $derived(current?.panes.find((p) => p.id === current.focused));
  const dictationActive = $derived(voiceMode === 'dictation' && voiceStatus !== 'idle');
  function revealDictationTarget(target: string) {
    const w = workspaces.find((w) => w.panes.some((p) => p.id === target));
    if (!w) throw new Error('The dictation terminal was closed. Your text is preserved.');
    device.activeWorkspace = w.id;
    device.activeHost = w.hostId;
    w.focused = target;
    if (maximize && maximize !== target) maximize = '';
    if (device.collapsedHosts?.[w.hostId])
      device.collapsedHosts = { ...device.collapsedHosts, [w.hostId]: false };
    return w;
  }
  $effect(() => {
    if (dictationActive && workspaces.some((w) => w.panes.some((p) => p.id === voiceTarget))) {
      revealDictationTarget(voiceTarget);
      // Dialogs must not cover the receiving terminal during capture or final paste.
      if (modal) modal = '';
    }
  });
  async function focusDictationTarget(target: string) {
    revealDictationTarget(target);
    modal = '';
    paneMenu = null;
    if (detachedPanes.has(target)) {
      await call('terminal_window_focus', { paneId: target });
      return;
    }
    if (desktop) {
      const window = getCurrentWindow();
      await window.show();
      await window.unminimize();
      await window.setFocus();
    }
    await tick();
    const pane = document.querySelector<HTMLElement>(`[data-pane-id="${CSS.escape(target)}"]`);
    if (!pane || !pane.getClientRects().length || pane.getBoundingClientRect().width <= 0)
      throw new Error('The dictation terminal is not visible. Your text is preserved.');
    pane.querySelector<HTMLTextAreaElement>('.xterm-helper-textarea')?.focus();
  }
  const selectedAgent = $derived(inv?.agents.find((a) => a.id === form.agent));
  function hostDefaults(): Host {
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
  function fail(e: unknown) {
    error = String(e);
    diagnostic('operation.failed');
  }
  let pendingTasks = 0;
  async function task(fn: () => Promise<void>) {
    error = '';
    pendingTasks++;
    busy = true;
    try {
      await fn();
    } catch (e) {
      fail(e);
    } finally {
      busy = --pendingTasks > 0;
    }
  }
  function persistDevice() {
    if (!mounted) return;
    clearTimeout(deviceTimer);
    deviceTimer = setTimeout(() => {
      void call('device_save', { value: { ...$state.snapshot(device), recovery } }).catch(fail);
    }, 180);
  }
  function saved(hostId: string) {
    if (hostId === HOSTLESS) {
      device.localWorkspaces = $state.snapshot(profiles[HOSTLESS]);
      persistDevice();
      return;
    }
    recovery[hostId] = $state.snapshot(profiles[hostId]);
    changeSeq[hostId] = (changeSeq[hostId] || 0) + 1;
    if (saveState[hostId] !== 'Conflict') saveState[hostId] = 'Pending';
    persistDevice();
    clearTimeout(revisionTimers[hostId]);
    revisionTimers[hostId] = setTimeout(() => flush(hostId), 250);
  }
  function flush(hostId: string) {
    saveChains[hostId] = (saveChains[hostId] || Promise.resolve()).then(async () => {
      if (connections[hostId] !== 'Connected' || profileBlocked[hostId]) {
        saveState[hostId] = 'Saved on this device';
        return;
      }
      if (saveState[hostId] === 'Conflict') return;
      const snapshot = $state.snapshot(profiles[hostId]);
      const seq = changeSeq[hostId];
      try {
        const result = await rpc<Profile>(hostId, 'profile_save', {
          profile: snapshot,
          revision: snapshot.revision,
        });
        profiles[hostId].revision = result.revision;
        if (changeSeq[hostId] === seq) {
          delete recovery[hostId];
          saveState[hostId] = 'Saved';
        } else {
          recovery[hostId] = $state.snapshot(profiles[hostId]);
          saveState[hostId] = 'Pending';
        }
        persistDevice();
      } catch (e) {
        saveState[hostId] = String(e).includes('CONFLICT') ? 'Conflict' : 'Save failed';
        error = String(e);
      }
    });
  }
  function updateWorkspace(w: Workspace) {
    const p = profiles[w.hostId];
    w = {
      ...w,
      tabGroups: cleanTabGroups(
        w.tabGroups,
        w.panes.map((p) => p.id),
      ),
    };
    const previous = p.workspaces.find((x) => x.id === w.id);
    if (w.layout !== previous?.layout) viewerLayouts[w.id] = w.layout;
    let savedLayout = retainTabPanes(w.layout, w.tabGroups);
    for (const pane of w.panes)
      if (detachedPanes.has(pane.id) && !ids(savedLayout).includes(pane.id))
        savedLayout = ids(savedLayout).filter(Boolean).length
          ? insertAt(savedLayout, ids(savedLayout).filter(Boolean)[0], pane.id, 'right')
          : { kind: 'pane', id: pane.id };
    w = {
      ...w,
      layout: terminalProjection(
        savedLayout,
        w.panes.map((p) => p.id),
      ) || { kind: 'pane' as const, id: '' },
    };
    p.workspaces = p.workspaces.map((x) => (x.id === w.id ? w : x));
    saved(w.hostId);
  }
  function resizeSidebar(width: number) {
    device.sidebarWidth = Math.round(Math.max(180, Math.min(520, window.innerWidth - 360, width)));
  }
  function selectWorkspace(w: Workspace) {
    if (dictationActive && !w.panes.some((p) => p.id === voiceTarget)) {
      notice = 'Stop dictation before switching workspaces.';
      return;
    }
    selectedPanes = [];
    selectionWorkspace = w.id;
    device.collapsedHosts = { ...device.collapsedHosts, [w.hostId]: false };
    device.activeWorkspace = w.id;
    device.activeHost = w.hostId;
    maximize = '';
    persistDevice();
    if (desktop) void call('terminal_workspace_activate', { workspaceId: w.id }).catch(fail);
    if (w.hostId === HOSTLESS) {
      const needs = workspaceHosts(w, device.hosts, connections);
      if (needs.panes.some((pane, index) => pane.hostId !== w.panes[index].hostId))
        updateWorkspace({ ...w, panes: needs.panes });
      if (needs.required.length || needs.missing.length) hostlessConnect = w;
    }
  }
  let hostlessConnect = $state<Workspace | null>(null);
  const hostlessNeeds = $derived(
    hostlessConnect ? workspaceHosts(hostlessConnect, device.hosts, connections) : null,
  );
  function newHostlessWorkspace() {
    const profile = (profiles[HOSTLESS] ||= blankProfile());
    const workspace: Workspace = {
      id: uid(),
      name: 'Hostless workspace',
      hostId: HOSTLESS,
      panes: [],
      layout: { kind: 'pane', id: '' },
      focused: '',
    };
    profile.workspaces.push(workspace);
    saved(HOSTLESS);
    selectWorkspace(workspace);
    workspaceTarget = workspace;
    renameValue = workspace.name;
    modal = 'workspace';
  }
  const mixedRecovery = new MixedHostRecovery();
  let recoveryPrompts = $state<HostRecovery[]>([]);
  let connectionTarget = $state<Host | null>(null);
  function offerMixedRecovery(owner: string) {
    recoveryPrompts = [
      ...recoveryPrompts,
      ...mixedRecovery.plan(
        owner,
        profiles[owner]?.workspaces || [],
        device.hosts,
        new Set(
          device.hosts
            .filter((h) => ['Connected', 'Connecting'].includes(connections[h.id]))
            .map((h) => h.id),
        ),
      ),
    ];
  }
  async function answerRecovery(prompt: HostRecovery, accept: boolean) {
    mixedRecovery.answer(prompt.hostId);
    recoveryPrompts = recoveryPrompts.filter((p) => p.hostId !== prompt.hostId);
    if (!accept) return;
    const host = device.hosts.find((h) => h.id === prompt.hostId);
    if (host && !['Connected', 'Connecting'].includes(connections[host.id])) {
      for (const w of workspaces)
        for (const p of w.panes) if (p.hostId === host.id) terminals.get(p.id)?.resume?.();
      await connect(host);
    }
  }
  function showHostConnection(p: Pane) {
    connectionTarget = device.hosts.find((h) => h.id === p.hostId) || null;
    if (connectionTarget) modal = 'terminal-host-connection';
  }
  async function disconnectHostTerminals(hostId: string) {
    mixedRecovery.answer(hostId);
    recoveryPrompts = recoveryPrompts.filter((p) => p.hostId !== hostId);
    await disconnectHost(hostId);
    await Promise.all(
      workspaces
        .flatMap((w) => w.panes)
        .filter((p) => p.hostId === hostId)
        .map((p) => terminals.get(p.id)?.disconnect?.()),
    );
    modal = '';
  }
  async function connectAllHosts() {
    await Promise.allSettled(
      device.hosts
        .filter(
          (host) => connections[host.id] !== 'Connected' && connections[host.id] !== 'Connecting',
        )
        .map(async (host) => {
          mixedRecovery.answer(host.id);
          recoveryPrompts = recoveryPrompts.filter((p) => p.hostId !== host.id);
          for (const w of workspaces)
            for (const p of w.panes) if (p.hostId === host.id) terminals.get(p.id)?.resume?.();
          await connect(host);
        }),
    );
  }
  async function connectHostTerminals(host: Host) {
    for (const w of workspaces)
      for (const p of w.panes) if (p.hostId === host.id) terminals.get(p.id)?.resume?.();
    modal = '';
    if (connections[host.id] !== 'Connected') await connect(host);
  }
  const reconnectHosts = new Set<string>();
  const hostOperations = new ConnectionOperations();
  const checkingHosts = new Map<string, symbol>();
  const nextConnect = new Map<string, number>();
  async function disconnectHost(hostId: string) {
    reconnectHosts.delete(hostId);
    hostAttempts.delete(hostId);
    checkingHosts.delete(hostId);
    connections[hostId] = 'Disconnected';
    delete logs[hostId];
    await hostOperations.run(hostId, () => call('host_disconnect', { hostId }));
  }
  async function checkConnections(onlyHost?: string) {
    if (!desktop) return;
    await Promise.all(
      device.hosts.map(async (h) => {
        if (onlyHost && h.id !== onlyHost) return;
        if (!reconnectHosts.has(h.id) || checkingHosts.has(h.id)) return;
        if (connections[h.id] === 'Connecting') {
          if (Date.now() < (nextConnect.get(h.id) || 0) + 105000) return;
          // Invalidate the stalled attempt before replacing its management channel.
          hostAttempts.delete(h.id);
          connections[h.id] = 'Disconnected';
        }
        if (!onlyHost && Date.now() < (nextConnect.get(h.id) || 0)) return;
        const check = Symbol();
        const attempt = hostAttempts.get(h.id);
        const current = () => reconnectHosts.has(h.id) && hostAttempts.get(h.id) === attempt;
        checkingHosts.set(h.id, check);
        try {
          if (connections[h.id] === 'Connected') {
            try {
              await connectionDeadline(
                call('host_health', { hostId: h.id }),
                HOST_HEALTH_TIMEOUT,
                'Host did not respond to its health check.',
              );
              return;
            } catch (e) {
              if (!current()) return;
              connections[h.id] = 'Disconnected';
              logs[h.id] = String(e);
            }
          }
          if (current()) await connect(h);
        } catch (e) {
          if (checkingHosts.get(h.id) === check) logs[h.id] = String(e);
        } finally {
          if (checkingHosts.get(h.id) === check) checkingHosts.delete(h.id);
        }
      }),
    );
  }
  async function reconnectHost(h: Host) {
    // Explicit retry also replaces an attempt stuck at Connecting.
    // Replace management only. Healthy terminal PTYs keep running independently.
    hostAttempts.delete(h.id);
    checkingHosts.delete(h.id);
    connections[h.id] = 'Disconnected';
    await connect(h);
  }
  const hostAttempts = new SvelteMap<string, string>();
  async function connect(h: Host) {
    if (connections[h.id] === 'Connecting') return;
    reconnectHosts.add(h.id);
    nextConnect.set(h.id, Date.now() + 15000);
    connections[h.id] = 'Connecting';
    diagnostic('host.connect', h.id);
    logs[h.id] = '';
    const attempt = uid();
    hostAttempts.set(h.id, attempt);
    try {
      await hostOperations.run(h.id, () => {
        if (hostAttempts.get(h.id) !== attempt || !reconnectHosts.has(h.id))
          return Promise.resolve();
        return call('host_connect', { host: h, attempt });
      });
    } catch (e) {
      if (hostAttempts.get(h.id) === attempt) {
        connections[h.id] = 'Disconnected';
        logs[h.id] = String(e);
      }
    }
  }
  async function refresh(id = device.activeHost) {
    const attempt = hostAttempts.get(id);
    const result = await rpc<Inventory>(id, 'inventory');
    if (attempt !== hostAttempts.get(id) || !reconnectHosts.has(id)) return;
    inventories[id] = result;
    // Repository scan notes stay in the picker, not the global title bar.
    if (profiles[id])
      profiles[id].discovery = {
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
    const joinedHost = device.hosts.find((h) => h.id === id);
    if (joinedHost) {
      const changed = refreshHostIdentity(joinedHost, result, Object.values(profiles));
      for (const [hostId, profile] of Object.entries(profiles))
        if (changed.has(profile)) saved(hostId);
    }
    persistDevice();
    if (id === device.activeHost) sessions = result.sessions;
  }
  function replaceProfile(hostId: string, profile: Profile) {
    const previous = new Set(profiles[hostId]?.workspaces.map((w) => w.id) || []);
    const retained = new Set(profile.workspaces.map((w) => w.id));
    const displaced = viewers.filter(
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
      if (previous.has(device.activeWorkspace) && !retained.has(device.activeWorkspace))
        device.activeWorkspace = destination.id;
      notice =
        'The host workspace changed. Your open viewers and edits were kept in ' +
        destination.name +
        '.';
    }
    profiles[hostId] = profile;
  }
  async function joined(id: string, attempt = hostAttempts.get(id)) {
    const currentAttempt = () => hostAttempts.get(id) === attempt && reconnectHosts.has(id);
    if (!currentAttempt()) return;
    try {
      let profile: Profile;
      try {
        profile = rebindOwner(await rpc<Profile>(id, 'profile_load'), id);
        profileBlocked[id] = false;
      } catch (e) {
        profileBlocked[id] = true;
        profile = profiles[id] || blankProfile();
        error = `Host configuration unavailable: ${e}. Changes will be saved on this device.`;
        saveState[id] = 'Saved on this device';
      }
      if (!currentAttempt()) return;
      if (recovery[id]) {
        if (recovery[id].revision === profile.revision) {
          replaceProfile(id, recovery[id]);
          saved(id);
        } else {
          replaceProfile(id, recovery[id]);
          saveState[id] = 'Conflict';
          error =
            'This host has newer workspace changes. Your local recovery copy is preserved. Reload the host profile in settings to use the remote version.';
        }
      } else replaceProfile(id, profile);
      connections[id] = 'Connected';
      for (const w of profiles[id].workspaces) {
        const focused = device.localFocus?.[w.id];
        if (focused && w.panes.some((p) => p.id === focused)) w.focused = focused;
      }
      await refresh(id);
      if (!currentAttempt()) return;
      if (!device.activeWorkspace || !workspaces.some((w) => w.id === device.activeWorkspace))
        device.activeWorkspace = profile.workspaces[0]?.id || '';
      persistDevice();
      offerMixedRecovery(id);
    } catch (e) {
      fail(e);
    }
  }
  function showLaunch(axis: 'row' | 'column' | null = null) {
    launchFromCommand = false;
    launchChooseRepo = false;
    launchWorkspaceId = axis ? current?.id || '' : '';
    launchAxis = axis;
    const targetHost = axis ? activePane?.hostId || device.activeHost : device.activeHost;
    form = {
      hostId: targetHost,
      name: axis ? '' : 'New workspace',
      repo: '',
      identity: '',
      agent: 'shell',
      model: '',
      effort: '',
      count: 1,
      brief: true,
      exactName: false,
    };
    modal = 'launch';
  }
  function promptValues(p?: Pane): PromptValues {
    const hostId = p?.hostId || settingsHost || device.activeHost;
    const h = device.hosts.find((h) => h.id === hostId);
    return {
      host: h?.name || 'this host',
      address: h?.address || 'local',
      user: h?.user || inventories[hostId]?.user || 'current user',
      repo: p?.repo || '/path/to/repository',
      identity: p?.identity || 'the existing host identity',
      agent: p?.agent || 'codex',
      model: p?.model || 'agent default',
      effort: p?.effort || 'agent default',
      guidance: p ? profiles[hostId]?.preferences.guidance || '' : guidance,
    };
  }
  function promptFor(p: Pane) {
    return renderPrompt(
      profiles[p.hostId]?.preferences.promptTemplate ?? defaultPrompt,
      promptValues(p),
    );
  }
  async function launch() {
    if (!Number.isSafeInteger(form.count) || form.count < 1)
      throw new Error('Choose a positive whole number of terminals.');
    if (!form.name.trim() && !launchAxis) throw new Error('Give this workspace a name.');
    if (connections[form.hostId] !== 'Connected')
      throw new Error('Connect to the selected host first.');
    const destination = launchAxis ? workspaces.find((w) => w.id === launchWorkspaceId) : undefined;
    if (launchAxis && !destination)
      throw new Error('The destination workspace was closed. Reopen the command.');
    const options = $state.snapshot(form);
    if (launchChooseRepo && !options.repo) throw new Error('Choose a repository before starting.');
    const managed =
      device.hosts.find((h) => h.id === options.hostId)?.kind === 'powershell' ||
      (launchFromCommand && (options.agent !== 'shell' || options.repo || options.exactName));
    if (
      managed &&
      options.agent !== 'shell' &&
      !inventories[options.hostId]?.agents.find((a) => a.id === options.agent)?.path
    )
      throw new Error(`Install ${options.agent} on this host first using Install agents.`);
    if (managed && options.exactName && !/^[\w-]+$/.test(options.name))
      throw new Error('Use letters, numbers, underscores and hyphens for session names.');
    let workspace: Workspace;
    const panes = Array.from({ length: form.count }, (_, i): Pane => ({
      id: uid(),
      hostId: form.hostId,
      hostKey: device.hosts.find((h) => h.id === form.hostId)?.fingerprint,
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
    if (launchAxis && destination) {
      workspace = {
        ...$state.snapshot(destination),
        panes: [...destination.panes, ...panes],
        layout: layoutFor(destination),
      };
      for (const p of panes)
        workspace.layout = ids(workspace.layout).filter(Boolean).length
          ? split(
              workspace.layout,
              ids(workspace.layout).includes(destination.focused)
                ? destination.focused
                : ids(workspace.layout)[0],
              p.id,
              launchAxis,
            )
          : { kind: 'pane', id: p.id };
      workspace.focused = panes[0].id;
      updateWorkspace(workspace);
    } else {
      workspace = {
        id: uid(),
        hostId: form.hostId,
        name: form.name.trim() || 'Workspace',
        panes,
        layout: balanced(
          panes.map((p) => p.id),
          stageWidth,
        ),
        focused: panes[0].id,
      };
      profiles[form.hostId].workspaces.push(workspace);
      saved(form.hostId);
    }
    selectWorkspace(workspace);
    modal = '';
    if (managed) {
      let started = 0;
      const failures: string[] = [];
      for (const pane of panes) {
        const target = workspaces
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
              options.repo && options.agent !== 'shell' && options.brief ? promptFor(target) : '',
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
        saved(workspace.hostId);
      }
      if (failures.length)
        throw new Error(
          `${started} of ${panes.length} sessions started. Failed terminals remain visible for retry. ${failures[0]}`,
        );
    }
  }
  async function retry(p: Pane) {
    const s = await rpc<Session>(p.hostId, 'create', {
      ...p,
      path: p.repo,
      brief: p.agent !== 'shell' ? promptFor(p) : '',
    });
    const owner = workspaces.find((w) => w.panes.some((pane) => pane.id === p.id));
    if (owner) {
      owner.panes = owner.panes.map((x) =>
        x.id === p.id
          ? { ...x, sessionId: s.id, stamp: s.stamp, name: s.name, error: undefined }
          : x,
      );
      saved(owner.hostId);
    }
  }
  let pendingClose: (() => void) | null = null;
  let nativeCloseTargets = $state<{ hostId: string; ids: string[] }[]>([]);
  let nativeCloseDone: (() => void | Promise<void>) | null = null;
  function nativePane(p: Pane) {
    return device.hosts.find((h) => h.id === p.hostId)?.kind === 'powershell';
  }
  function offerNativeClose(panes: Pane[], done: () => void | Promise<void>) {
    const groups = new Map<string, Set<string>>();
    for (const p of panes)
      if (nativePane(p) && p.sessionId) {
        if (!groups.has(p.hostId)) groups.set(p.hostId, new Set());
        groups.get(p.hostId)!.add(p.sessionId);
      }
    if (!groups.size) return false;
    nativeCloseTargets = [...groups].map(([hostId, ids]) => ({ hostId, ids: [...ids] }));
    nativeCloseDone = done;
    modal = 'close-native';
    return true;
  }
  async function finishNativeClose(action: 'end' | 'background' | 'external') {
    for (const target of nativeCloseTargets) {
      const host = device.hosts.find((h) => h.id === target.hostId);
      if (!host) throw new Error('The local host was removed. Cancel and reconnect it first.');
      if (connections[host.id] !== 'Connected') await connect(host);
      await rpc(host.id, 'native_close', { ids: target.ids, action });
    }
    const done = nativeCloseDone;
    nativeCloseDone = null;
    nativeCloseTargets = [];
    modal = '';
    await done?.();
  }
  async function destroyApp() {
    clearTimeout(deviceTimer);
    await call('device_save', { value: { ...$state.snapshot(device), recovery } });
    await call('app_close');
  }
  function closePane(p: Pane, confirmed = false) {
    if (dictationActive && p.id === voiceTarget) {
      notice = 'Stop dictation before closing its terminal.';
      return;
    }
    const owner = workspaces.find((w) => w.panes.some((pane) => pane.id === p.id));
    if (!owner) return;
    if (
      owner.hostId !== HOSTLESS &&
      !confirmed &&
      nativePane(p) &&
      offerNativeClose([p], () => closePane(p, true))
    )
      return;
    if (!p.sessionId && !confirmed) {
      pendingClose = () => closePane(p, true);
      modal = 'close-terminal';
      return;
    }
    const layout = remove(retainTabPanes(layoutFor(owner), owner.tabGroups), p.id);
    if (!layout) {
      closeWorkspace(owner, confirmed);
      return;
    }
    updateWorkspace({
      ...owner,
      layout,
      panes: owner.panes.filter((x) => x.id !== p.id),
      focused: owner.panes.find((x) => x.id !== p.id)?.id || '',
    });
    maximize = '';
  }
  function closeWorkspace(w: Workspace, confirmed = false) {
    if (viewers.some((v) => v.workspaceId === w.id && v.locked)) {
      notice = 'Save or discard viewer edits before closing this workspace.';
      return;
    }
    if (dictationActive && w.panes.some((p) => p.id === voiceTarget)) {
      notice = 'Stop dictation before closing its workspace.';
      return;
    }
    if (
      w.hostId !== HOSTLESS &&
      !confirmed &&
      offerNativeClose(w.panes, () => closeWorkspace(w, true))
    )
      return;
    if (!confirmed && w.panes.some((p) => !p.sessionId)) {
      pendingClose = () => closeWorkspace(w, true);
      modal = 'close-terminal';
      return;
    }
    viewers = viewers.filter((v) => v.workspaceId !== w.id);
    delete viewerLayouts[w.id];
    profiles[w.hostId].workspaces = profiles[w.hostId].workspaces.filter((x) => x.id !== w.id);
    saved(w.hostId);
    if (device.activeWorkspace === w.id) {
      const next = profiles[w.hostId].workspaces[0] || workspaces[0];
      device.activeWorkspace = next?.id || '';
      if (next) device.activeHost = next.hostId;
      maximize = '';
    }
    persistDevice();
    notice = w.panes.some(nativePane)
      ? 'Workspace closed.'
      : 'Workspace deleted. Its tmux sessions are still running.';
  }
  async function deleteHost(h: Host, confirmed = false) {
    if (!confirmed && h.kind === 'powershell') {
      if (connections[h.id] !== 'Connected') await connect(h);
      const sessions = await rpc<Session[]>(h.id, 'sessions');
      if (
        offerNativeClose(
          sessions.map((s) => ({ hostId: h.id, sessionId: s.id }) as Pane),
          () => deleteHost(h, true),
        )
      )
        return;
    }
    const owned = new Set(profiles[h.id]?.workspaces.map((w) => w.id) || []);
    if (viewers.some((v) => v.locked && (owned.has(v.workspaceId) || v.pane.hostId === h.id)))
      throw new Error('Save or discard viewer edits before removing this host.');
    viewers = viewers.filter((v) => !owned.has(v.workspaceId) && v.pane.hostId !== h.id);
    clearTimeout(revisionTimers[h.id]);
    if (
      workspaces.some((w) => w.panes.some((p) => p.id === voiceTarget && p.hostId === h.id)) &&
      voiceStatus !== 'idle'
    )
      await cancelVoice();
    for (const p of workspaces.flatMap((w) => w.panes).filter((p) => p.hostId === h.id))
      await call('terminal_close', { id: p.id });
    await disconnectHost(h.id);
    await saveChains[h.id];
    const removedActive = profiles[h.id]?.workspaces.some((w) => w.id === device.activeWorkspace);
    delete profiles[h.id];
    delete recovery[h.id];
    delete inventories[h.id];
    delete connections[h.id];
    delete logs[h.id];
    delete saveState[h.id];
    delete profileBlocked[h.id];
    delete changeSeq[h.id];
    delete saveChains[h.id];
    device.hosts = device.hosts.filter((x) => x.id !== h.id);
    if (device.collapsedHosts) delete device.collapsedHosts[h.id];
    if (removedActive) device.activeWorkspace = workspaces[0]?.id || '';
    if (device.activeHost === h.id)
      device.activeHost =
        workspaces.find((w) => w.id === device.activeWorkspace)?.hostId ||
        device.hosts[0]?.id ||
        '';
    maximize = '';
    persistDevice();
    modal = '';
    deleteHostTarget = null;
    notice = 'Host removed from this device. Remote workspaces and tmux sessions are unchanged.';
  }
  async function refreshSessions(hostId: string) {
    const result = await rpc<Session[]>(hostId, 'sessions');
    if (inventories[hostId]) inventories[hostId].sessions = result;
    sessions = result;
  }
  async function showSessions(p: Pane | null = null) {
    sessionPane = p;
    sessionHost = p?.hostId || device.activeHost;
    const h = device.hosts.find((h) => h.id === sessionHost);
    if (!h) throw new Error('Choose a host first.');
    if (connections[sessionHost] !== 'Connected') {
      void connect(h);
      return;
    }
    try {
      await refreshSessions(sessionHost);
    } catch (e) {
      connections[sessionHost] = 'Disconnected';
      void connect(h);
      throw new Error(`Connection lost. Reconnecting; reopen Tmux when connected. ${e}`);
    }
    modal = 'sessions';
  }
  async function startSession() {
    await refresh(sessionHost);
    sessionStep = 0;
    nameError = '';
    sessionOperation = uid();
    form = {
      hostId: sessionHost,
      name: '',
      repo: '',
      identity: '',
      agent: 'shell',
      model: '',
      effort: '',
      count: 1,
      brief: true,
      exactName: true,
    };
    modal = 'new-session';
  }
  let acknowledgedRepo = '';
  async function createSession() {
    if (
      form.repo &&
      form.agent !== 'shell' &&
      acknowledgedRepo !== form.repo &&
      workspaces.some(
        (w) =>
          w.hostId === sessionHost &&
          w.panes.some((p) => p.repo === form.repo && p.agent !== 'shell'),
      )
    ) {
      modal = 'repo-collision';
      return;
    }
    acknowledgedRepo = '';
    const p = { ...form, id: '', sessionId: '', operation: sessionOperation } as Pane;
    const session = await rpc<Session>(sessionHost, 'create', {
      ...p,
      path: form.repo,
      install: form.agent !== 'shell' && !selectedAgent?.path,
      brief: form.repo && form.agent !== 'shell' && form.brief ? promptFor(p) : '',
    });
    if (inventories[sessionHost])
      inventories[sessionHost].sessions = [
        ...inventories[sessionHost].sessions.filter((s) => s.id !== session.id),
        session,
      ];
    await attach(session, {
      repo: form.repo,
      identity: form.identity,
      agent: form.agent,
      model: form.model,
      effort: form.effort,
    });
  }
  async function attach(s: Session, options: Partial<Pane> = {}) {
    const target = sessionPane;
    const w = target ? workspaces.find((w) => w.panes.some((p) => p.id === target.id)) : undefined;
    if (target && !w)
      throw new Error('The selected terminal has been closed. Reopen the session picker.');
    const p: Pane = {
      id: uid(),
      hostId: sessionHost,
      hostKey: device.hosts.find((h) => h.id === sessionHost)?.fingerprint,
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
      updateWorkspace({
        ...w,
        panes: w.panes.map((x) => (x.id === target.id ? p : x)),
        layout: swap(layoutFor(w), target.id, p.id),
        focused: p.id,
      });
      if (maximize === target.id) maximize = p.id;
    } else {
      const workspace: Workspace = {
        id: uid(),
        name: s.name,
        hostId: sessionHost,
        panes: [p],
        layout: tile([p.id]),
        focused: p.id,
      };
      profiles[sessionHost].workspaces.push(workspace);
      selectWorkspace(workspace);
      saved(sessionHost);
    }
    modal = '';
    sessionPane = null;
  }
  async function renameSession() {
    if (!renameTarget) return;
    await rpc(renameTarget.hostId, 'rename', {
      id: renameTarget.sessionId,
      stamp: renameTarget.stamp,
      name: renameValue,
    });
    for (const w of workspaces) {
      let changed = false;
      for (const p of w.panes)
        if (p.hostId === renameTarget.hostId && p.sessionId === renameTarget.sessionId) {
          p.name = renameValue;
          changed = true;
        }
      if (changed) saved(w.hostId);
    }
    modal = '';
    await refresh(renameTarget.hostId);
  }
  async function destroySession() {
    if (!destroyTarget) return;
    const targetHost = destroyTarget.hostId || device.activeHost;
    await rpc(targetHost, 'destroy', { id: destroyTarget.id, stamp: destroyTarget.stamp });
    sessionHost = targetHost;
    await refreshSessions(targetHost);
    modal = 'sessions';
    notice = 'Session deleted. Its running processes have stopped.';
  }
  let broadcastText = $state(''),
    broadcastAllHosts = $state(true),
    broadcastAllWorkspaces = $state(true);
  let broadcastHost = $state(''),
    broadcastWorkspace = $state(''),
    broadcastHostName = $state(''),
    broadcastWorkspaceName = $state('');
  let broadcastSnapshot = $state<BroadcastSnapshot | null>(null),
    broadcastBusy = $state(false),
    broadcastResult = $state('');
  $effect(() => {
    broadcastText;
    broadcastAllHosts;
    broadcastAllWorkspaces;
    broadcastSnapshot = null;
  });
  $effect(() => {
    if (voiceMode === 'broadcast' && voiceStatus !== 'idle' && modal !== 'broadcast')
      void task(cancelVoice);
  });
  function focusBroadcastInput() {
    if (modal === 'broadcast' && appHasFocus())
      document
        .querySelector<HTMLTextAreaElement>('[role="dialog"][aria-label="broadcast"] textarea')
        ?.focus();
  }
  function openBroadcast() {
    if (voiceStatus !== 'idle') return;
    broadcastText = '';
    broadcastAllHosts = true;
    broadcastAllWorkspaces = true;
    broadcastHost = device.activeHost;
    broadcastWorkspace = current?.id || '';
    broadcastHostName = device.hosts.find((h) => h.id === broadcastHost)?.name || 'current host';
    broadcastWorkspaceName = current?.name || 'current workspace';
    broadcastSnapshot = null;
    broadcastResult = '';
    modal = 'broadcast';
    void tick().then(focusBroadcastInput);
  }
  async function collectBroadcast() {
    const inputs = workspaces
      .filter((w) => broadcastAllWorkspaces || w.id === broadcastWorkspace)
      .flatMap((w) =>
        w.panes
          .filter((p) => broadcastAllHosts || p.hostId === broadcastHost)
          .map((p) => {
            const host = device.hosts.find((h) => h.id === p.hostId),
              inventory = inventories[p.hostId];
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
      connections[input.hostId] === 'Connected'
        ? rpc<PaneStatus>(input.hostId, 'terminal_status', { pid: terminal.processId() })
        : Promise.resolve(unknownStatus),
    );
  }
  async function reviewBroadcast() {
    if (broadcastBusy || voiceStatus !== 'idle' || broadcastResult) return;
    broadcastBusy = true;
    try {
      broadcastSnapshot = await collectBroadcast();
    } finally {
      broadcastBusy = false;
    }
  }
  async function sendBroadcast() {
    if (broadcastBusy || voiceStatus !== 'idle' || broadcastResult || !broadcastSnapshot) return;
    broadcastBusy = true;
    const reviewed = broadcastSnapshot,
      message = broadcastText;
    try {
      const snapshot = await collectBroadcast();
      broadcastSnapshot = snapshot;
      if (broadcastRevision(snapshot) !== broadcastRevision(reviewed)) {
        notice = 'Broadcast recipients changed. Review the updated list and confirm again.';
        return;
      }
      const result = await createBroadcastDelivery(snapshot, message)(terminals);
      broadcastResult = `Sent to ${result.delivered.length} terminal sessions. ${snapshot.skipped.length} panes skipped; ${snapshot.mirrors} mirrors deduplicated. No delivery will be retried.`;
      if (result.failed.length)
        broadcastResult += `\nDelivery failed or is uncertain for ${result.failed.length} sessions. Some text may already have arrived:\n${result.failed.join('\n')}`;
    } finally {
      broadcastBusy = false;
    }
  }
  let recentDictation = $state<{
    text: string;
    raw: string;
    target: string;
    submitContext?: string;
  } | null>(null);
  let recoveryTimer: ReturnType<typeof setTimeout>;
  async function submitText() {
    if (!dictation.trim()) return;
    if (voiceMode === 'command') {
      commandText = applyFinalDictionary(raw, dictation, $dictionaryState.dictionary);
      modal = 'command';
      return;
    }
    const pane = workspaces.flatMap((w) => w.panes).find((p) => p.id === voiceTarget);
    if (!pane)
      throw new Error(
        'The recording destination was closed. Use Copy dictation to recover the text.',
      );
    const terminal = terminals.get(pane.id);
    if (!terminal) throw new Error('Terminal is unavailable. Your dictation is preserved.');
    // PTY delivery does not require OS/window focus. A focus failure is advisory.
    await focusDictationTarget(pane.id).catch(() => {});
    await deliverDictation(terminal, dictation, device.voice.automatic && !finishWithoutSubmit);
    recentDictation = {
      text: dictation,
      raw,
      target: pane.id,
      submitContext:
        device.voice.automatic && !finishWithoutSubmit ? undefined : terminal.context(),
    };
    if (recentDictation.submitContext && device.inputMode !== 'hold')
      notice = `Review the text in ${pane.name}. Double-tap ${bindingFor('dictation').label} to send.`;
    clearTimeout(recoveryTimer);
    recoveryTimer = setTimeout(() => {
      recentDictation = null;
    }, 120_000);
    dictation = '';
    raw = '';
  }
  async function record(mode: CaptureMode, target = activePane?.id || '') {
    if (!appHasFocus()) return;
    reviewHotkey.cancel();
    if (mode === 'dictation' && voiceStatus === 'idle' && !device.listenOnboarded) {
      listenIntroTarget = target;
      modal = 'listen-intro';
      if (desktop && detachedFocus) {
        await getCurrentWindow().show();
        await getCurrentWindow().unminimize();
        await getCurrentWindow().setFocus();
      }
      return;
    }
    if (voiceStatus !== 'idle' && voiceMode !== mode) return;
    if (voiceStatus === 'listening') {
      const finishingRecording = recordingId;
      const finishingCapture = capture;
      voiceStatus = 'finishing-recording';
      if (!(await recordingTail.wait()) || recordingId !== finishingRecording) return;
      voiceStatus = 'transcribing';
      // Flush the final partial PCM frame and await every queued IPC write before ASR finalization.
      await finishingCapture?.stop();
      if (recordingId !== finishingRecording) return;
      await call('voice_stop');
      return;
    }
    if (voiceStatus !== 'idle' || switchingVoice || filesLocked) return;
    if (mode === 'dictation' && !target) throw new Error('Select a terminal first.');
    if (dictation.trim())
      throw new Error(
        'Right-click a terminal title bar to review or discard undelivered dictation before recording again.',
      );
    voiceMode = mode;
    finishWithoutSubmit = false;
    voiceTarget = mode === 'dictation' ? target : '';
    if (mode === 'broadcast') {
      broadcastText = '';
      broadcastSnapshot = null;
      modal = 'broadcast';
    }
    if (mode === 'command') {
      commandText = '';
      commandReview = null;
      commandError = '';
      modal = 'command';
    }
    dictation = '';
    raw = '';
    voiceStatus = 'loading';
    voicePreparing = 'Preparing…';
    recordingId = uid();
    const startedRecording = recordingId;
    const startedAt = performance.now();
    const mark = (event: string) =>
      diagnostic(
        `voice.start.${event}`,
        startedRecording,
        Math.round(performance.now() - startedAt),
      );
    mark('requested');
    void tick().then(() =>
      requestAnimationFrame(() => {
        if (recordingId === startedRecording) mark('preparing-render-frame');
      }),
    );
    let startedCapture: Capture | undefined;
    try {
      // A hotkey during startup warmup waits for it instead of being discarded.
      if (voiceCacheTask) voicePreparing = 'Waiting for voice models…';
      await voiceCacheTask?.catch(() => {});
      if (recordingId !== startedRecording || !appHasFocus()) return;
      mark('preload-wait-complete');
      if (mode === 'dictation') {
        voicePreparing = 'Focusing terminal…';
        await focusDictationTarget(target);
      }
      if (recordingId !== startedRecording) return;
      mark('focus-complete');
      voicePreparing = 'Preparing speech model…';
      await call('voice_start', {
        recording: recordingId,
        model: device.voice.model,
        backend: device.voice.backend,
        cleanup: device.voice.cleanup && mode !== 'command',
      });
      if (recordingId !== startedRecording) return;
      mark('model-ready');
      voicePreparing = 'Starting microphone…';
      startedCapture = new Capture();
      capture = startedCapture;
      await startedCapture.start(
        device.voice.microphone,
        (e) => {
          fail(e);
          // Drain accepted audio on backpressure; do not discard the dictation.
          if (voiceStatus === 'listening') void task(() => record(mode));
        },
        startedRecording,
      );
      mark('capture-start-returned');
      if (recordingId !== startedRecording) {
        await startedCapture.stop();
        return;
      }
      voiceStatus = 'listening';
      mark('listening-state');
      void tick().then(() =>
        requestAnimationFrame(() => {
          if (recordingId === startedRecording) mark('listening-render-frame');
        }),
      );
    } catch (e) {
      await startedCapture?.stop().catch(() => {});
      if (recordingId !== startedRecording) return;
      await call('voice_cancel').catch(() => {});
      recordingId = '';
      voiceStatus = 'idle';
      throw e;
    }
  }
  async function cancelVoice() {
    recordingId = '';
    recordingTail.cancel();
    await capture?.stop();
    await call('voice_cancel');
    dictation = '';
    raw = '';
    voiceStatus = 'idle';
  }
  function focusCommandInput() {
    if (modal !== 'command' || !appHasFocus()) return;
    const input = document.querySelector<HTMLTextAreaElement>(
      '[role="dialog"][aria-label="command"] textarea',
    );
    if (!input || document.activeElement === input) return;
    // Preserve an explicit edit elsewhere; recover focus lost when the Stop button disappears.
    const active = document.activeElement;
    if (
      active?.matches('input, textarea, [contenteditable="true"]') &&
      input.closest('[role="dialog"]')?.contains(active)
    )
      return;
    input.focus();
  }
  $effect(() => {
    if (modal === 'command') void tick().then(focusCommandInput);
  });
  function commandContext() {
    return {
      hostId: device.activeHost,
      hostName: host?.name || 'selected host',
      workspaceId: current?.id || '',
      workspaceName: current?.name || '',
      paneId: activePane?.id || '',
      paneName: activePane?.name || '',
      paneContext: activePane
        ? `${activePane.hostId}:${activePane.sessionId}:${activePane.stamp || ''}`
        : '',
    };
  }
  function interpretCommand() {
    commandReview = null;
    commandError = '';
    try {
      const action = parseCommand(
        commandText,
        device.hosts,
        device.activeHost,
        inventories[device.activeHost],
        inventories,
      );
      commandReview = reviewCommand(
        commandText,
        action,
        commandContext(),
        action.kind === 'new'
          ? device.hosts.find((h) => h.id === action.hostId)?.name || 'selected host'
          : undefined,
      );
    } catch (e) {
      commandError = String(e);
    }
  }
  function commandSubmit() {
    if (voiceStatus === 'listening' && voiceMode === 'command') void task(() => record('command'));
    else if (voiceStatus === 'idle' && !commandReview) interpretCommand();
  }
  async function implementCommand() {
    if (!commandReview || busy || voiceStatus !== 'idle') return;
    try {
      const action = consumeReview(commandReview, commandText, commandContext());
      commandReview = null;
      await executeCommand(action);
    } catch (e) {
      commandError = String(e);
      commandReview = null;
    }
  }
  async function executeCommand(a: Action) {
    if (a.kind === 'sessions') {
      await showSessions(activePane || null);
    } else if (a.kind === 'split') {
      addTerminal(a.axis === 'row' ? 'right' : 'bottom');
    } else if (a.kind === 'new') {
      if (a.destination === 'current' && !current)
        throw new Error('Open a workspace first, or say “open a new workspace”.');
      showLaunch(a.destination === 'current' ? 'row' : null);
      launchFromCommand = true;
      launchChooseRepo = a.chooseRepo;
      form = { ...form, ...a, brief: true };
      modal = 'launch';
    } else if (a.kind === 'switch') {
      const w = workspaces.find((w) => w.name.toLowerCase() === a.name.toLowerCase());
      if (!w) throw new Error('Workspace not found');
      selectWorkspace(w);
      modal = '';
    } else if (a.kind === 'rename' && activePane) {
      if (!activePane.sessionId) throw new Error('Join or create a tmux session first.');
      renameTarget = activePane;
      renameValue = a.name;
      modal = 'rename';
    } else if (a.kind === 'destroy' && activePane) {
      if (!activePane.sessionId) throw new Error('Join or create a tmux session first.');
      sessionPane = activePane;
      sessionHost = activePane.hostId;
      await refreshSessions(sessionHost);
      destroyTarget = {
        id: activePane.sessionId,
        hostId: activePane.hostId,
        stamp: activePane.stamp,
        name: activePane.name,
        windows: 1,
        attached: 1,
      };
      modal = 'destroy';
    } else if (a.kind === 'worktree') {
      branch = a.branch;
      modal = 'worktree';
    }
  }
  function openUpdates() {
    automaticUpdatePending = false;
    updateReturn = ['settings', 'support'].includes(modal) ? modal : '';
    modal = '';
    updatePrompt = true;
  }
  function closeUpdates() {
    updatePrompt = false;
    modal = updateReturn;
    updateReturn = '';
  }
  async function checkUpdates(manual = false, settingsOpening = false) {
    if (!desktop || !clientInfo.updateSupported || updateState === 'installing') return;
    const origin = modal;
    const originPrompt = updatePrompt;
    try {
      await updateCheck.run(async () => {
        updateBusy = true;
        updateState = 'checking';
        updateStatus = 'Checking for updates…';
        try {
          availableUpdate = await call('update_check');
          updateState = availableUpdate ? 'available' : 'current';
          if (!availableUpdate) automaticUpdatePending = false;
          updateStatus = availableUpdate
            ? `Version ${releaseVersion(availableUpdate.version)} is available.`
            : 'You are up to date.';
        } catch (e) {
          updateState = 'error';
          automaticUpdatePending = false;
          updateStatus = availableUpdate
            ? `Unable to refresh updates: ${e}. Version ${releaseVersion(availableUpdate.version)} was previously found.`
            : String(e);
        } finally {
          updateBusy = false;
        }
        return availableUpdate;
      });
      if (availableUpdate && updateState === 'available') {
        // Each actual Settings opening can remind the user; Later returns without reopening it.
        if (settingsOpening && modal === 'settings') openUpdates();
        else if (manual) {
          // A completed request must not replace a dialog opened after it started.
          if (
            modal === origin &&
            updatePrompt === originPrompt &&
            (modal === 'settings' || (originPrompt && updateCanPresent))
          )
            openUpdates();
        } else if (!settingsOpening) automaticUpdatePending = true;
      }
    } catch (e) {
      updateState = 'error';
      updateStatus = String(e);
    }
  }
  async function installUpdate() {
    if (!availableUpdate || updateBusy) return;
    if (voiceStatus !== 'idle' || dictation.trim())
      throw new Error('Finish dictation and copy or discard retained text before updating.');
    updateBusy = true;
    updateState = 'installing';
    updateProgress = 0;
    updateStatus = 'Downloading and verifying…';
    try {
      await call('update_download', { version: availableUpdate.version });
      clearTimeout(deviceTimer);
      await call('device_save', { value: { ...$state.snapshot(device), recovery } });
      if (voiceStatus !== 'idle' || dictation.trim())
        throw new Error(
          'Finish dictation before restarting to install. The downloaded update is retained.',
        );
      updateStatus = 'Restarting to install…';
      await call('update_install');
    } catch (e) {
      updateStatus = String(e);
      error = updateStatus;
      updateState = 'error';
      updateBusy = false;
    }
  }
  async function loadModels() {
    models = await call<ModelInfo[]>('model_list');
    modelsLoaded = true;
    await configureVoiceCache().catch((e) => {
      notice = `Voice preload failed; recording can retry: ${e}`;
    });
  }
  async function openSettings() {
    modal = 'settings';
    void checkUpdates(false, true);
    roots = (profiles[device.activeHost]?.preferences.roots || []).join('\n');
    settingsHost = device.activeHost;
    guidance = profiles[settingsHost]?.preferences.guidance || '';
    promptTemplate = profiles[settingsHost]?.preferences.promptTemplate ?? defaultPrompt;
    await loadModels();
    if (desktop && !hardware)
      void call<VoiceHardware>('voice_hardware')
        .then((v) => {
          hardware = v;
          hardwareError = '';
        })
        .catch((e) => {
          hardwareError = String(e);
        });
    void refreshMicrophones();
  }
  let systemReducedMotion = $state(false);
  const highlightMotionAllowed = $derived(
    device.highlightMotion !== 'static' &&
      (device.highlightMotion === 'animate' || !systemReducedMotion),
  );
  const voiceStatusText = $derived(
    voiceStatus === 'loading'
      ? voicePreparing
      : voiceStatus === 'finishing-recording'
        ? 'Finishing recording…'
        : voiceStatus === 'listening'
          ? 'Listening'
          : voiceStatus === 'transcribing'
            ? 'Finishing transcription…'
            : voiceStatus === 'cleanup-loading'
              ? 'Loading tidy-up model…'
              : voiceStatus === 'cleanup'
                ? `Tidy-up pass · S1-mini${cleanupTotal > 1 ? ` · ${cleanupPart}/${cleanupTotal}` : ''}${cleanupWords ? ` · ${cleanupWords} words` : ''}`
                : 'Pasting…',
  );
  const voiceListening = $derived(dictationActive && voiceStatus === 'listening');
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
  onMount(() => {
    mounted = true;
    const unsub: (() => void)[] = [];
    for (const subscription of [
      on<{ paneId: string; requestId: string; action: string; args: Record<string, unknown> }>(
        'terminal-window-request',
        (event) => {
          void windowRequest(event);
        },
      ),
      on<{ paneId: string; focused: boolean }>('terminal-window-focus', (event) => {
        if (event.focused && detachedPanes.has(event.paneId)) detachedFocus = event.paneId;
        else if (detachedFocus === event.paneId) {
          detachedFocus = '';
          window.dispatchEvent(new Event('blur'));
        }
      }),
      on<{ paneId: string }>('terminal-window-closed', (event) => {
        detachedPanes.delete(event.paneId);
        if (detachedFocus === event.paneId) detachedFocus = '';
      }),
    ])
      void subscription.then((stop) => {
        if (mounted) unsub.push(stop);
        else stop();
      });
    void (async () => {
      try {
        const data = desktop
          ? await call<Device & { recoveryNotice?: string; recovery?: Record<string, Profile> }>(
              'device_load',
            ).catch((e) => {
              fail(e);
              return null;
            })
          : null;
        if (desktop) aliases = await call<string[]>('ssh_aliases').catch(() => []);
        if (data) {
          if (data.recoveryNotice) notice = data.recoveryNotice;
          device = {
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
          };
          recovery = data.recovery || {};
          profiles = { ...recovery, [HOSTLESS]: device.localWorkspaces || blankProfile() };
        }
        const listeners = await Promise.all([
          on<{ done: number; total: number }>(
            'update-progress',
            (v) => (updateProgress = Math.round((v.done / v.total) * 100)),
          ),
          on<{ hostId: string; attempt: string }>('host-ready', (v) => {
            if (hostAttempts.get(v.hostId) === v.attempt && reconnectHosts.has(v.hostId))
              void joined(v.hostId);
          }),
          on<{ hostId: string; text: string; attempt: string }>('host-output', (v) => {
            if (hostAttempts.get(v.hostId) !== v.attempt || !reconnectHosts.has(v.hostId)) return;
            logs[v.hostId] = ((logs[v.hostId] || '') + v.text).slice(-12000);
          }),
          on<{ hostId: string; attempt: string }>('host-closed', (v) => {
            if (hostAttempts.get(v.hostId) === v.attempt) {
              connections[v.hostId] = 'Disconnected';
              logs[v.hostId] ||= 'Connection closed.';
            }
          }),
          on<{ id: string; done: number; total: number }>(
            'model-progress',
            (v) =>
              (downloadProgress[v.id] =
                v.total > 0
                  ? `${Math.min(100, Math.round((v.done / v.total) * 100))}%`
                  : 'Downloading'),
          ),
          on<{ recording: string; phase: string; part?: number; total?: number; words?: number }>(
            'voice-phase',
            (v) => {
              if (v.recording !== recordingId) return;
              if (voiceStatus !== 'finishing-recording') voiceStatus = v.phase;
              cleanupPart = v.part || 0;
              cleanupTotal = v.total || 0;
              cleanupWords = v.words || 0;
            },
          ),
          on<{
            recording: string;
            text: string;
            raw?: string;
            final: boolean;
            error?: string;
            backend?: string;
          }>('voice-text', (v) => {
            if (v.recording !== recordingId) return;
            if (v.backend) voiceBackend = v.backend;
            dictation = v.text;
            raw = v.raw || v.text;
            if (voiceMode === 'broadcast') broadcastText = v.text;
            if (voiceMode === 'command') {
              commandText = v.text;
              commandReview = null;
              if (v.error) commandError = v.error;
            }
            if (v.final) {
              if (v.error) {
                recordingTail.cancel();
                recordingId = '';
                voiceStatus = 'idle';
                error = v.error;
                if (voiceMode !== 'dictation') {
                  dictation = '';
                  raw = '';
                }
                void capture?.stop();
                void call('voice_cancel');
              } else {
                if (voiceMode === 'broadcast') {
                  voiceStatus = 'idle';
                  broadcastText = applyFinalDictionary(raw, dictation, $dictionaryState.dictionary);
                  dictation = '';
                  raw = '';
                  void tick().then(focusBroadcastInput);
                } else if (voiceMode === 'command') {
                  voiceStatus = 'idle';
                  commandText = applyFinalDictionary(raw, dictation, $dictionaryState.dictionary);
                  dictation = '';
                  modal = 'command';
                  void tick().then(focusCommandInput);
                } else {
                  dictation = applyFinalDictionary(raw, dictation, $dictionaryState.dictionary);
                  voiceStatus = 'pasting';
                  void task(async () => {
                    try {
                      await submitText();
                    } finally {
                      if (recordingId === v.recording) voiceStatus = 'idle';
                    }
                  });
                }
              }
            }
          }),
        ]);
        unsub.push(...listeners);
        if (desktop) {
          clientInfo = await call('client_info');
          await configureShortcut();
          if (clientInfo.updateSupported && device.checkUpdates !== false) void checkUpdates();
          await loadModels();
          if (!device.voiceSetupDismissed && !voiceStackReady(device.voice, models))
            modal = 'voice-setup';
          if (
            needsChangelog(clientInfo.version, device.lastChangelogVersion, !!data?.hosts?.length)
          ) {
            pendingChangelog = clientInfo.version;
          } else if (device.lastChangelogVersion !== clientInfo.version) {
            device.lastChangelogVersion = clientInfo.version;
            persistDevice();
          }
        }
        if (desktop)
          unsub.push(
            await getCurrentWindow().onCloseRequested(async (event) => {
              if (filesLocked || viewerLocked) {
                event.preventDefault();
                notice = 'Save or discard edits, or finish the upload before closing.';
                return;
              }
              event.preventDefault();
              if (voiceStatus !== 'idle' || dictation.trim()) {
                notice = 'Finish dictation and copy or discard retained text before closing.';
                return;
              }
              try {
                const native: Pane[] = [];
                for (const host of device.hosts.filter((h) => h.kind === 'powershell')) {
                  if (connections[host.id] !== 'Connected') await connect(host);
                  const sessions = await rpc<(Session & { running: boolean })[]>(
                    host.id,
                    'sessions',
                  );
                  for (const session of sessions.filter((s) => s.running))
                    native.push({ hostId: host.id, sessionId: session.id } as Pane);
                }
                if (offerNativeClose(native, destroyApp)) return;
                await destroyApp();
              } catch (e) {
                fail(e);
              }
            }),
          );
        if (!desktop && new URLSearchParams(location.search).has('preview')) {
          const h = {
            ...hostDefaults(),
            id: 'preview',
            name: 'Development',
            kind: 'ssh' as const,
            address: 'dev-box',
          };
          device.hosts = [h];
          device.activeHost = h.id;
          const panes = Array.from({ length: 4 }, (_, i): Pane => ({
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
            panes,
            layout: balanced(
              panes.map((p) => p.id),
              stageWidth,
            ),
            focused: panes[0].id,
          };
          profiles[h.id] = { ...blankProfile(), workspaces: [w] };
          device.collapsedHosts = { ...device.collapsedHosts, [w.hostId]: false };
          device.activeWorkspace = w.id;
        }
      } catch (e) {
        fail(e);
      }
    })();
    const captureKeys = new VoiceCaptureKeys();
    const key = (e: KeyboardEvent) => {
      if (!capturingShortcut) {
        const control = captureKeys.down(e, voiceStatus, appHasFocus());
        if (control.consume) {
          e.preventDefault();
          e.stopImmediatePropagation();
          if (control.action) {
            // Retire hold-to-talk ownership so its eventual release cannot stop twice.
            shortcutHeld = null;
            releasePending = true;
            if (control.action === 'cancel') void task(cancelVoice);
            else {
              finishWithoutSubmit = true;
              void task(() => record(voiceMode));
            }
          }
          return;
        }
      }

      if (e.key === 'F11' && desktop) {
        e.preventDefault();
        e.stopImmediatePropagation();
        void task(async () => {
          const w = getCurrentWindow();
          await w.setFullscreen(!(await w.isFullscreen()));
        });
        return;
      }
      if (capturingShortcut) {
        e.preventDefault();
        e.stopImmediatePropagation();
        if (e.key === 'Escape') {
          capturingShortcut = null;
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
        (e.ctrlKey || e.metaKey) &&
        e.shiftKey &&
        e.code === 'KeyF' &&
        !modal &&
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
      if (e.key === 'Escape' && dictionarySelection !== null) {
        dictionarySelection = null;
        e.preventDefault();
        return;
      }
      if (e.key === 'Escape' && paneMenu) {
        paneMenu = null;
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
        if (updatePrompt && !modal && !updateBusy) {
          closeUpdates();
          return;
        }
        if (!busy && !filesLocked && !broadcastBusy)
          modal = modal === 'changelog' ? changelogReturn : '';
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'k' && !filesLocked && !broadcastBusy) {
        e.preventDefault();
        modal = 'command';
      }
    };
    const mouseMode = (e: MouseEvent) =>
      (['dictation', 'command'] as const).find((mode) => mouseMatches(e, bindingFor(mode)));
    const pointer = (e: PointerEvent) => {
      const mode = mouseMode(e);
      if (capturingShortcut || !mode || !appHasFocus()) return;
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

      if (
        shortcutHeld &&
        (keyboardShortcut(e)?.key === bindingFor(shortcutHeld).key ||
          ['Control', 'Alt', 'Shift', 'Meta'].includes(e.key))
      )
        void inputEdge(false, shortcutHeld).catch(fail);
    };
    const releasePointer = (e: PointerEvent) => {
      if (shortcutHeld && [1, 4, 2, 5, 6][e.button] === bindingFor(shortcutHeld).key)
        void inputEdge(false, shortcutHeld).catch(fail);
    };
    let blurTimer: ReturnType<typeof setTimeout>;
    const blur = () => {
      clearTimeout(blurTimer);
      blurTimer = setTimeout(() => {
        if (appHasFocus()) return;
        reviewHotkey.cancel();
        captureKeys.reset();
        shortcutHeld = null;
        releasePending = true;
        capturingShortcut = null;
        if (
          voiceStatus === 'loading' ||
          voiceStatus === 'listening' ||
          voiceStatus === 'finishing-recording'
        ) {
          notice = 'Voice capture cancelled because DazeDev lost focus.';
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
      () => desktop && clientInfo.updateSupported && device.checkUpdates !== false,
    );
    window.addEventListener('online', recoverConnections);
    window.addEventListener('focus', recoverConnections);
    return () => {
      cancelPaneDrag?.();
      reviewHotkey.cancel();
      clearInterval(healthTimer);
      stopUpdatePolling();
      clearTimeout(recoveryTimer);
      window.removeEventListener('online', recoverConnections);
      window.removeEventListener('focus', recoverConnections);
      reconnectHosts.clear();
      void alertAudio?.close();
      mounted = false;
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
  {#if voiceListening}<div
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
        device.sidebar = !device.sidebar;
        persistDevice();
      }}
      >{#if device.sidebar}<PanelLeftClose size={17} />{:else}<PanelLeftOpen
          size={17}
        />{/if}</button
    >
    <Brand />
    <div class="layout-actions">
      {#if current}<button
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
              ...current,
              layout: balanced(ids(layoutFor(current)), stageWidth),
            })}><LayoutGrid size={17} /></button
        ><button
          class="icon"
          aria-label="Arrange in columns"
          title="Arrange in columns"
          onclick={() => updateWorkspace({ ...current, layout: columns(ids(layoutFor(current))) })}
          ><Columns2 size={17} /></button
        >{/if}
    </div>
    <div class="title-notification" class:error={!!error}>
      {#if error || notice}<span role={error ? 'alert' : 'status'} title={error || notice}
          >{error || notice}</span
        ><button
          class="icon"
          aria-label={error ? 'Dismiss error' : 'Dismiss notification'}
          onclick={() => {
            if (error) error = '';
            else notice = '';
          }}><X size={14} /></button
        >{/if}
    </div>
    <div class="title-actions">
      <button
        class="icon"
        aria-label="Search terminals"
        title="Search terminals (Ctrl+Shift+F)"
        onclick={() => openTerminalSearch()}><Search size={17} /></button
      >
      {#if modelsLoaded && !voiceReady}<button
          class="voice-setup-alert"
          onclick={() => (modal = 'voice-setup')}
          ><Download size={14} />Voice stack not set up, click here to install</button
        >{/if}
      <button class="quiet" onclick={() => (modal = 'command')}
        ><Command size={14} /> Commands <kbd>⌘ / Ctrl K</kbd></button
      ><button
        class="icon"
        aria-label="Cycle theme"
        title={`Theme: ${device.theme || 'blue'}`}
        onclick={() => {
          const themes = ['blue', 'charcoal', 'green'] as const;
          device.theme = themes[(themes.indexOf(device.theme || 'blue') + 1) % themes.length];
          persistDevice();
        }}><Palette size={17} /></button
      ><button class="icon" aria-label="Settings" onclick={() => void task(openSettings)}
        ><Settings size={17} /></button
      >
    </div>
  </header>
  <div class="body">
    {#if device.sidebar}<aside class="sidebar" style:width={`${device.sidebarWidth || 238}px`}>
        <button
          class="new-workspace new-host"
          disabled={!device.hosts.length}
          onclick={() => void connectAllHosts()}
          ><ArrowUpRight size={16} /> Connect all hosts</button
        >
        <button class="new-workspace new-host" onclick={addHost}><Plus size={16} /> New host</button
        >
        <section class="local-workspaces" aria-label="Hostless workspaces">
          <button class="new-workspace new-host" onclick={newHostlessWorkspace}
            ><Plus size={16} /> Hostless workspace</button
          >
          {#each profiles[HOSTLESS]?.workspaces || [] as w (w.id)}
            <div
              class="local-workspace-row"
              class:active={device.activeWorkspace === w.id}
              class:drop-empty={dragHover === w.id}
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
                  renameValue = w.name;
                  modal = 'workspace';
                }}>⋯</button
              >
            </div>
          {/each}
        </section>
        <HostTree
          hosts={device.hosts}
          {profiles}
          {connections}
          {logs}
          attempts={hostAttempts}
          onauth={respond}
          oncancel={(h) => disconnectHost(h.id)}
          highlight={recordingHighlight(device.recordingHighlight)}
          highlightColor={device.recordingHighlightColor || '#39bdd7'}
          activeHost={device.activeHost}
          activeWorkspace={device.activeWorkspace}
          {dragHover}
          collapsed={device.collapsedHosts || {}}
          ontoggle={(h) => {
            device.collapsedHosts = {
              ...device.collapsedHosts,
              [h.id]: !device.collapsedHosts?.[h.id],
            };
            persistDevice();
          }}
          onhost={(h) => {
            device.activeHost = h.id;
            persistDevice();
          }}
          onhostreorder={(source, target, after) => {
            const list = device.hosts.filter((h) => h.id !== source.id);
            const index = list.findIndex((h) => h.id === target.id);
            if (index < 0 || source.id === target.id) return;
            list.splice(index + (after ? 1 : 0), 0, source);
            device.hosts = list;
            persistDevice();
          }}
          onreconnect={(h) => void reconnectHost(h)}
          oncolors={(h) => {
            colorHost = h;
            modal = 'host-colors';
          }}
          ontmux={(h) => {
            tmuxHost = h;
            modal = 'tmux-settings';
          }}
          ondocuments={(h) => openFiles({ id: '', hostId: h.id, repo: '' }, 'documents')}
          onedit={(h) => {
            newHost = { ...h, nativeShell: h.nativeShell || 'powershell' };
            modal = 'host';
          }}
          onnew={(h) => {
            device.activeHost = h.id;
            showLaunch();
          }}
          onreorder={(source, target, after) => {
            const profile = profiles[source.hostId];
            if (!profile || dictationActive) return;
            const reordered = reorderWorkspaces(profile.workspaces, source.id, target.id, after);
            if (reordered !== profile.workspaces) {
              profile.workspaces = reordered;
              saved(source.hostId);
            }
          }}
          reorderDisabled={dictationActive || !!draggingPane}
          onselect={selectWorkspace}
          onoptions={(w) => {
            workspaceTarget = w;
            renameValue = w.name;
            modal = 'workspace';
          }}
          ondeletehost={(h) => {
            deleteHostTarget = h;
            modal = 'delete-host';
          }}
          ondelete={closeWorkspace}
        />
        {#each recoveryPrompts.filter((p) => !['Connected', 'Connecting'].includes(connections[p.hostId])) as prompt (prompt.hostId)}
          <section class="mixed-host-recovery" aria-label="Workspace host recovery">
            <p>
              Workspace {prompt.workspaces.join(', ')} has a terminal on {device.hosts.find(
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
                await showSessions(activePane || null);
              })}
            ><TerminalIcon size={16} />
            {device.hosts.find((h) => h.id === device.activeHost)?.kind === 'powershell'
              ? 'PowerShell sessions'
              : 'tmux sessions'}
            <span>{inventories[device.activeHost]?.sessions.length ?? '—'}</span></button
          ><button
            onclick={() => {
              modal = 'repos';
            }}><FolderGit2 size={16} /> Repositories</button
          ><button onclick={() => void task(() => agentPicker())}
            ><Radio size={16} /> Install agents</button
          ><button
            onclick={() => {
              if (voiceStatus === 'idle') modal = 'command';
            }}><Mic size={16} /> Voice command</button
          >
          <button disabled={voiceStatus !== 'idle'} onclick={openBroadcast}
            ><Radio size={16} /> Broadcast</button
          >
          <button onclick={() => void task(openSettings)}><Settings size={16} /> Settings</button>
          <button
            class="update-sidebar"
            class:recording-highlight={device.supportGlow !== false}
            data-highlight={recordingHighlight(device.recordingHighlight)}
            style:--voice-highlight-color={device.recordingHighlightColor || '#39bdd7'}
            disabled={filesLocked || voiceStatus !== 'idle' || updateState === 'installing'}
            onclick={() => {
              modal = 'support';
              void checkUpdates();
            }}
            aria-label={`Feedback, support and about DazeDev ${releaseVersion(clientInfo.version)}: ${updateLabel}`}
          >
            {#if device.supportGlow !== false && recordingHighlight(device.recordingHighlight) !== 'static'}
              <HighlightEffect kind={recordingHighlight(device.recordingHighlight)} />
            {/if}
            <strong>FEEDBACK / SUPPORT</strong>
            <span class="support-sidebar-version">DazeDev {releaseVersion(clientInfo.version)}</span
            >
            <i
              class="update-dot"
              data-state={availableUpdate ? 'available' : updateState}
              aria-hidden="true"
            ></i>
            <span>{updateLabel}</span>
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
        aria-valuenow={device.sidebarWidth || 238}
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
          device.sidebarWidth = 238;
          persistDevice();
        }}
        onkeydown={(e) => {
          if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            e.preventDefault();
            resizeSidebar((device.sidebarWidth || 238) + (e.key === 'ArrowLeft' ? -16 : 16));
            persistDevice();
          }
        }}
      ></div>{/if}
    <main>
      {#if !desktop}<div class="preview-banner">
          UI preview · open the desktop build for SSH, terminals and offline voice.
        </div>{/if}
      <div class="workspace-stage" bind:clientWidth={stageWidth}>
        {#if current && current.panes.some((p) => detachedPanes.has(p.id))}
          <div class="detached-terminal-bar" aria-label="Terminals on other screens">
            {#each current.panes.filter((p) => detachedPanes.has(p.id)) as pane}
              <button
                onclick={() => void call('terminal_window_focus', { paneId: pane.id }).catch(fail)}
                >Show {terminalPresentation(pane, paneStatus[pane.id]).name}</button
              >
              <button
                onclick={() => void dockTerminal(pane.id).catch(fail)}
                aria-label={`Return ${pane.name} to workspace`}>Return here</button
              >
            {/each}
          </div>
        {/if}
        <div class="workspace-canvas">
          {#each workspaces as w (w.id)}
            <div class="layout-handles" class:hidden={current?.id !== w.id || !!maximize}>
              <LayoutView
                node={layoutFor(w)}
                onresize={(id, ratio) =>
                  updateWorkspace({ ...w, layout: resize(layoutFor(w), id, ratio) })}
                >{#snippet pane(id)}{/snippet}</LayoutView
              >
            </div>
            {#if !ids(layoutFor(w)).filter(Boolean).length && current?.id === w.id}
              <div
                class="empty-workspace"
                data-empty-workspace={w.id}
                class:drop-empty={!!draggingPane && dropTarget?.id === ''}
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
          {#each workspaces.flatMap((w) => w.panes.map((p) => ({ w, p }))) as { w, p } (p.id)}
            {@const boxes = workspaceBoxes[w.id]}
            {@const presentation = terminalPresentation(p, paneStatus[p.id])}

            {@const id = p.id}
            {@const tabs = terminalTabs(w, p.id)}
            {@const activeTab = tabs?.panes.find((id) => !!boxes[id])}
            {@const box =
              detachedPanes.has(p.id) || maximize === p.id
                ? { x: 0, y: 0, width: 100, height: 100 }
                : boxes[p.id] || (activeTab ? boxes[activeTab] : undefined)}
            {#if box}<section
                role="group"
                aria-label={`Terminal ${p.name}`}
                data-pane-id={p.id}
                class:hidden={detachedPanes.has(p.id) ||
                  (!!tabs && activeTab !== p.id) ||
                  current?.id !== w.id ||
                  (!!maximize && maximize !== p.id)}
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
                class:drag-selected={selectedPanes.includes(id)}
                class:voice-listening={voiceTarget === p.id && voiceListening}
                style:transform-origin={`${box.x === 0 ? 'left' : box.x + box.width >= 99.9 ? 'right' : 'center'} ${box.y === 0 ? 'top' : box.y + box.height >= 99.9 ? 'bottom' : 'center'}`}
                style:--voice-pop-scale={box.width === 100 && box.height === 100 ? 1 : 1.012}
                onfocusin={() => {
                  w.focused = id;
                  (device.localFocus ||= {})[w.id] = id;
                  persistDevice();
                }}
              >
                {#if voiceTarget === p.id && voiceListening}
                  <div
                    class="voice-pane-border recording-highlight"
                    data-highlight={recordingHighlight(device.recordingHighlight)}
                    style:--voice-highlight-color={device.recordingHighlightColor || '#39bdd7'}
                    aria-hidden="true"
                    transition:fade={{ duration: highlightMotionAllowed ? 200 : 0 }}
                  >
                    {#if recordingHighlight(device.recordingHighlight) !== 'static'}<HighlightEffect
                        kind={recordingHighlight(device.recordingHighlight)}
                      />{/if}
                  </div>
                {/if}
                <div
                  class="pane-header"
                  style:background-color={tabColors(
                    p.colors,
                    w.colors,
                    device.hosts.find((h) => h.id === p.hostId)?.colors,
                  )?.background}
                  style:color={tabColors(
                    p.colors,
                    w.colors,
                    device.hosts.find((h) => h.id === p.hostId)?.colors,
                  )?.foreground}
                  role="toolbar"
                  tabindex="0"
                  aria-label={`Terminal controls for ${p.name}${selectedPanes.includes(p.id) ? ', selected for dragging' : ''}`}
                  title="Drag to move. Shift-click terminal headers to select several."
                  onkeydown={(e) => {
                    if ((e.key === ' ' || e.key === 'Enter') && e.target === e.currentTarget) {
                      e.preventDefault();
                      selectPane(p.id, w, e.shiftKey);
                    } else if (e.key === 'Escape' && e.target === e.currentTarget) {
                      selectedPanes = [];
                    } else if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) {
                      e.preventDefault();
                      const box = e.currentTarget.getBoundingClientRect();
                      paneMenu = {
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
                      maximize = maximize === p.id ? '' : p.id;
                  }}
                >
                  <div class="pane-title">
                    <span class="terminal-name" title={presentation.name}>{presentation.name}</span>
                    <span
                      class="terminal-type"
                      title={`${presentation.type}${presentation.model ? ' / ' + presentation.model : ''}`}>{presentation.type}</span
                    >
                  </div>
                  {#if p.hostId !== w.hostId}
                    <button
                      onclick={() => showHostConnection(p)}
                      aria-label={`Connection options for ${device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable host'}`}
                      class="cross-host-note recording-highlight"
                      style:background-color={tabColors(
                        device.hosts.find((h) => h.id === p.hostId)?.colors,
                      )?.background}
                      style:color={tabColors(device.hosts.find((h) => h.id === p.hostId)?.colors)
                        ?.foreground}
                      data-highlight={recordingHighlight(device.recordingHighlight)}
                      style:--voice-highlight-color={device.recordingHighlightColor || '#39bdd7'}
                      title={`Connection options for ${device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable'}; workspace host: ${device.hosts.find((h) => h.id === w.hostId)?.name || 'Unavailable'}`}
                    >
                      {#if recordingHighlight(device.recordingHighlight) !== 'static'}
                        <HighlightEffect kind={recordingHighlight(device.recordingHighlight)} />
                      {/if}
                      <Server size={12} /><span
                        >Host: {device.hosts.find((h) => h.id === p.hostId)?.name ||
                          'Unavailable'}</span
                      >
                    </button>
                  {/if}
                  <PaneActions
                    name={p.name}
                    visible={current?.id === w.id && (!maximize || maximize === p.id)}
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
                      disabled={!desktop || dictationActive}
                      title="Open this terminal in a window you can drag to another screen"
                      onclick={() => void detachTerminal(p).catch(fail)}
                      ><ArrowUpRight size={14} /> Other screen</button
                    >
                    <button
                      title={`Change connected host: ${device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable'}`}
                      aria-label={`Change host for ${p.name}`}
                      onclick={() => choosePaneHost(p)}><Server size={14} /> Host</button
                    >
                    <button
                      title="Upload a local file to a chosen host"
                      aria-label={`Upload file for ${p.name}`}
                      onclick={() => openFiles(p, 'upload')}><Upload size={14} /> Upload</button
                    >

                    {#if terminalProgress[p.id]?.state}<progress
                        class="pane-progress"
                        aria-label="Terminal task progress"
                        max="100"
                        value={terminalProgress[p.id].state === 3
                          ? undefined
                          : terminalProgress[p.id].value}
                      ></progress>{/if}
                    <button
                      title="Dictate to this terminal"
                      aria-label={`Dictate to ${p.name}`}
                      class:active={voiceTarget === p.id && voiceStatus === 'listening'}
                      onclick={() => void task(() => record('dictation', p.id))}
                      ><Mic size={14} /> Listen</button
                    ><button
                      class="icon"
                      title={maximize === p.id ? 'Restore pane' : 'Maximize pane'}
                      aria-label={`${maximize === p.id ? 'Restore' : 'Maximize'} ${p.name}`}
                      onclick={() => (maximize = maximize === p.id ? '' : p.id)}
                      >{#if maximize === p.id}<Minimize2 size={14} />{:else}<Maximize2
                          size={14}
                        />{/if}<span class="overflow-label"
                        >{maximize === p.id ? 'Restore' : 'Maximize'}</span
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
                            maximize = '';
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
                            maximize = '';
                            const nextId = CSS.escape(tabs.panes[next]);
                            void tick().then(() =>
                              document
                                .querySelector<HTMLButtonElement>(
                                  `[data-pane-id="${nextId}"] [data-terminal-tab="${nextId}"]`,
                                )
                                ?.focus(),
                            );
                          }}>{terminalPresentation(tab, paneStatus[tab.id]).name}</button
                        >{/if}
                    {/each}
                    {#if w.hostId !== HOSTLESS}<button
                        aria-label="New terminal tab"
                        onclick={() => addTerminal('right', p, true)}>+</button
                      >{/if}
                  </div>{/if}
                {#if !p.hideStatus || (voiceTarget === p.id && dictationActive)}{@const status =
                    paneStatus[p.id] || unknownStatus}
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
                    {#if voiceTarget === p.id && dictationActive}
                      <div
                        class="voice-status-alert recording-highlight"
                        title={`${voiceStatusText}. Enter finishes without submitting; Escape abandons capture.`}
                        data-highlight={recordingHighlight(device.recordingHighlight)}
                        style:--voice-highlight-color={device.recordingHighlightColor || '#39bdd7'}
                        role="status"
                        aria-live="polite"
                      >
                        {#if recordingHighlight(device.recordingHighlight) !== 'static'}<HighlightEffect
                            kind={recordingHighlight(device.recordingHighlight)}
                          />{/if}
                        <span> {voiceStatusText} </span>
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
                        device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable',
                        status,
                      )}
                      >{hostLabel(
                        device.hosts.find((h) => h.id === p.hostId)?.name || 'Unavailable',
                        status,
                      )}</span
                    >
                  </div>{/if}
                {#if p.error}<div class="pane-message">
                    <p>{p.error}</p>
                    <button onclick={() => void task(() => retry(p))}>Retry launch</button>
                  </div>{:else if p.sessionId || p.shell}<TerminalView
                    settings={{
                      ...(device.terminal || terminalDefaults),
                      clipboard:
                        device.clipboardByHost?.[p.hostId] || device.terminal?.clipboard || 'write',
                    }}
                    floating={detachedPanes.has(p.id)}
                    onoutput={(value) => publishTerminal(p.id, { kind: 'output', ...value })}
                    onterminalfocus={() => {
                      if (!detachedPanes.has(p.id)) return false;
                      void call('terminal_window_focus', { paneId: p.id }).catch(fail);
                      return true;
                    }}
                    transcript={voiceTarget === p.id && voiceStatus !== 'idle' ? dictation : ''}
                    onprogress={(value) => (terminalProgress[p.id] = value)}
                    theme={device.theme || 'blue'}
                    pane={p}
                    host={device.hosts.find((h) => h.id === p.hostId)}
                    active={w.focused === p.id}
                    visible={!detachedPanes.has(p.id) &&
                      (!tabs || activeTab === p.id) &&
                      current?.id === w.id &&
                      (!maximize || maximize === p.id)}
                    connected={connections[p.hostId] === 'Connected'}
                    ondisconnect={() => void checkConnections(p.hostId)}
                    onreconnect={async () => {
                      const host = device.hosts.find((h) => h.id === p.hostId);
                      if (host) await reconnectHost(host);
                    }}
                    onfocus={() => {
                      if (w.focused !== p.id) {
                        w.focused = p.id;
                        (device.localFocus ||= {})[w.id] = p.id;
                        persistDevice();
                      }
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
                    onmetadata={(value) => (paneStatus[p.id] = value)}
                    onstate={(s) => (terminalState[p.id] = s)}
                  />{:else}<div class="pane-message">
                    <TerminalIcon size={24} />
                    <p>{desktop ? 'Creating session…' : 'Terminal preview'}</p>
                    {#if !desktop}<code>Connect a host to begin.</code>{/if}
                  </div>{/if}
                <div class="pane-footer">
                  <span title={terminalState[p.id]}
                    >{terminalState[p.id] === 'Attached' || terminalState[p.id] === 'Shell'
                      ? ''
                      : terminalState[p.id]}</span
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
                {#if draggingPane && dropTarget?.id === p.id}<div
                    class="dock-guide"
                    aria-hidden="true"
                  >
                    <span class="left">Left</span><span class="right">Right</span>
                    <span class="top">Above</span><span class="bottom">Below</span>
                  </div>{/if}
                {#if dropTarget?.id === p.id}<div class={`drop-preview ${dropTarget.edge}`}>
                    <span
                      >{dropTarget.edge === 'center'
                        ? `${dragCopy ? '+ Duplicate' : 'Move'} here`
                        : `${dragCopy ? '+ Duplicate' : 'Move'}: split ${dropTarget.edge}`}</span
                    >
                  </div>{/if}
              </section>{/if}{/each}
          {#each viewers as viewer, index (viewer.id)}
            <ViewerWindow
              tileId={viewer.id}
              dockRect={workspaceBoxes[viewer.workspaceId]?.[viewer.id] || {
                x: 0,
                y: 0,
                width: 100,
                height: 100,
              }}
              hidden={current?.id !== viewer.workspaceId || (!!maximize && maximize !== viewer.id)}
              maximized={maximize === viewer.id}
              onmaximize={() => (maximize = maximize === viewer.id ? '' : viewer.id)}
              ondrag={(e) => {
                const w = workspaces.find((w) => w.id === viewer.workspaceId);
                if (w) startPaneDrag(e, viewer, w, true);
              }}
              onnew={() => openFiles(viewer.pane, 'documents', viewer.workspaceId)}
              title={viewer.title}
              locked={viewer.locked}
              offset={index % 8}
              layer={viewer.order}
              onactivate={() => {
                if (viewer.order !== viewerOrder) viewer.order = ++viewerOrder;
              }}
              onclose={() => {
                closeViewer(viewer.id);
              }}
            >
              {#if dropTarget?.id === viewer.id}<div class={`drop-preview ${dropTarget.edge}`}>
                  <span>Move: {dropTarget.edge}</span>
                </div>{/if}
              <FilesDialog
                hosts={device.hosts}
                {inventories}
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
                    ? `${device.hosts.find((h) => h.id === hostId)?.name || 'Host'}: ${path}`
                    : 'Files and previews';
                }}
              />
            </ViewerWindow>
          {/each}
        </div>
        {#if !current}<div class="empty-state">
            <Brand large />
            <p class="holding-title">Let's speed things up</p>
            <div class="empty-actions">
              <button
                class="primary"
                onclick={() => {
                  if (!host) {
                    newHost = hostDefaults();
                    modal = 'host';
                  } else showLaunch();
                }}
                ><Plus size={16} />{host ? 'Start a workspace' : 'Connect your first host'}</button
              ><button onclick={() => (modal = 'command')}
                ><Command size={16} /> Explore commands</button
              >
            </div>
          </div>{/if}
      </div>
    </main>
  </div>
</div>
{#if hostlessConnect && hostlessNeeds}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Connect workspace hosts"
      tabindex="-1"
      use:focusDialog
    >
      <h2>{hostlessConnect.name}</h2>
      <p>
        {hostlessNeeds.required.length
          ? `Connect to ${hostlessNeeds.required.length} hosts to open this group's terminals?`
          : 'Some hosts need to be set up on this device.'}
      </p>
      {#each hostlessNeeds.required as required}<p>{required.name}</p>{/each}
      {#if hostlessNeeds.missing.length}<p class="hint">
          Add these hosts with their original user and tmux socket: {hostlessNeeds.missing.join(
            ', ',
          )}.
        </p>{/if}
      <div class="row">
        {#if hostlessNeeds.required.length}<button
            class="primary"
            onclick={() => {
              const hosts = hostlessNeeds!.required;
              hostlessConnect = null;
              void task(async () => {
                await Promise.all(hosts.map((h) => connect(h)));
              });
            }}>Connect to {hostlessNeeds.required.length} hosts</button
          >{/if}
        {#if hostlessNeeds.missing.length}<button
            onclick={() => {
              hostlessConnect = null;
              addHost();
            }}>Add host</button
          >{/if}
        <button onclick={() => (hostlessConnect = null)}>Keep disconnected</button>
      </div>
    </div>
  </div>
{/if}
{#if draggingPane}<div
    class="pane-drag-ghost"
    style:left={`${dragPosition.x}px`}
    style:top={`${dragPosition.y}px`}
  >
    {dragCopy
      ? '+ Duplicate terminal'
      : viewers.some((v) => v.id === draggingPane)
        ? 'Move viewer'
        : 'Move terminal'} · Esc to cancel
  </div>{/if}

{#if modal}<div
    class="modal-backdrop"
    role="presentation"
    onpointerdown={(e) => {
      backdropPress = e.target === e.currentTarget;
    }}
    onclick={(e) => {
      if (backdropPress && e.target === e.currentTarget && !busy && !filesLocked && !broadcastBusy)
        modal = modal === 'changelog' ? changelogReturn : '';
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
        'transcript',
        'documents',
        'upload',
      ].includes(modal)}
      use:focusDialog={{ restore: restoreTerminalFocus }}
      role="dialog"
      aria-modal="true"
      aria-label={modal === 'feedback' ? supportContent.feedback.title : modal}
      tabindex="-1"
    >
      <div class="modal-header">
        <h2>
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
            launch: launchAxis ? 'Add terminals' : 'Start a workspace',
            sessions:
              device.hosts.find((h) => h.id === (sessionHost || device.activeHost))?.kind ===
              'powershell'
                ? 'PowerShell sessions'
                : 'tmux sessions',
            'new-session': ['Name your session', 'Choose an agent', 'Choose a repository'][
              sessionStep
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
          }[modal] || modal}
        </h2>
        <button
          class="icon"
          aria-label="Close dialog"
          disabled={busy || filesLocked || broadcastBusy}
          onclick={() => (modal = modal === 'changelog' ? changelogReturn : '')}
          ><X size={18} /></button
        >
      </div>
      {#if error}<p class="inline-error" role="alert">{error}</p>{/if}
      {#each Object.keys(downloadProgress) as id}<button
          onclick={() =>
            void call('model_cancel', { id })
              .then(() => {
                downloadProgress[id] = 'Cancelling';
              })
              .catch(fail)}>Cancel {models.find((m) => m.id === id)?.name || id} download</button
        >{/each}
      {#if (modal === 'upload' || modal === 'documents') && filesTarget}
        <FilesDialog
          hosts={device.hosts}
          {inventories}
          connected={connections}
          initialHost={filesTarget.hostId}
          directory={filesTarget.repo}
          mode={modal}
          onpaste={pasteFilePath}
          onbusy={(value) => (filesLocked = value)}
        />
      {:else if modal === 'pane-host' && switchTarget}
        <p>
          This pane stays in its workspace. Its current tmux session stays on the original host; a
          plain shell will close.
        </p>
        <label
          >Connect this pane to<select bind:value={switchHost} disabled={busy}>
            {#each device.hosts as h}<option
                value={h.id}
                disabled={connections[h.id] !== 'Connected'}
                >{h.name}{connections[h.id] !== 'Connected' ? ' (not connected)' : ''}</option
              >{/each}
          </select></label
        >
        <p class="hint">
          The new connection starts with a plain shell. Use Tmux to join or create a session on that
          host.
        </p>
        <button
          class="primary"
          disabled={busy || voiceStatus !== 'idle'}
          onclick={() => void task(switchPaneHost)}>Change connection</button
        >
      {:else if modal === 'pane-repo' && repoTarget}
        <label
          >Repository host<select
            bind:value={repoHost}
            disabled={busy}
            onchange={() => {
              repoPath = '';
              void task(refreshRepoChoices);
            }}
          >
            {#each device.hosts as h}<option
                value={h.id}
                disabled={connections[h.id] !== 'Connected'}>{h.name}</option
              >{/each}
          </select></label
        >
        <p class="hint">
          Paste the configured orientation prompt into this existing terminal. Choose a matching
          host if you have manually opened a nested SSH connection.
        </p>
        <RepoPicker
          repos={repoChoices}
          hostId={repoHost}
          bind:value={repoPath}
          warnings={repoWarnings}
          disabled={busy}
        />
        {#if repoPath}<label
            >Prompt preview<textarea
              readonly
              rows="7"
              value={promptFor({ ...repoTarget, hostId: repoHost, repo: repoPath })}
            ></textarea></label
          >{/if}
        <div class="form-row">
          <button disabled={busy} onclick={() => void task(refreshRepoChoices)}
            >Refresh repositories</button
          >
          <button
            class="primary"
            disabled={busy || !repoPath}
            onclick={() => void task(pasteRepoPrompt)}>Paste for review</button
          >
        </div>
      {:else if modal === 'terminal-host-connection' && connectionTarget}
        <p>{connectionTarget.name}: {connections[connectionTarget.id] || 'Disconnected'}</p>
        <p>
          This action applies to every terminal connected to this host. Managed PowerShell and tmux
          sessions keep running. Disconnecting ends plain shells; their visible output is retained.
        </p>
        <div class="form-row">
          <button
            disabled={connections[connectionTarget.id] === 'Connecting'}
            onclick={() => void task(() => connectHostTerminals(connectionTarget!))}
            >Connect host and terminals</button
          >
          <button onclick={() => void task(() => disconnectHostTerminals(connectionTarget!.id))}
            >Disconnect host and terminals</button
          >
        </div>
      {:else if modal === 'support'}
        <SupportDialog
          version={releaseVersion(clientInfo.version)}
          status={updateStatus || updateLabel}
          updateState={availableUpdate ? 'available' : updateState}
          updateAvailable={availableUpdate ? releaseVersion(availableUpdate.version) : ''}
          {updateBusy}
          updateSupported={desktop && clientInfo.updateSupported}
          onfeedback={openFeedback}
          glow={device.supportGlow !== false}
          highlight={recordingHighlight(device.recordingHighlight)}
          highlightColor={device.recordingHighlightColor || '#39bdd7'}
          onglowchange={(enabled) => {
            device.supportGlow = enabled;
            persistDevice();
          }}
          onupdate={() => {
            openUpdates();
            if (!availableUpdate) void checkUpdates(true);
          }}
        />
      {:else if modal === 'feedback' && device.feedbackContact}
        <FeedbackDialog
          appName="DazeDev"
          bind:name={device.feedbackContact.name}
          bind:email={device.feedbackContact.email}
          bind:message={feedbackMessage}
          busy={feedbackBusy}
          sent={feedbackSent}
          error={feedbackError}
          oncontactchange={persistDevice}
          onmessagechange={() => (feedbackSent = false)}
          onsubmit={() => void sendFeedback()}
          onclose={() => (modal = '')}
        />
      {:else if modal === 'transcript'}
        <p class="hint">
          Check the terminal before pasting again; some text may already have arrived.
        </p>
        <textarea
          class="transcript-review"
          aria-label="Full transcription"
          readonly
          value={dictation}
          spellcheck="false"></textarea>
        <div class="form-row">
          <button onclick={() => void navigator.clipboard.writeText(dictation).catch(fail)}
            >Copy dictation</button
          >
          <button onclick={() => void navigator.clipboard.writeText(raw).catch(fail)}
            >Copy original</button
          >
          <button
            class="danger"
            onclick={() => {
              dictation = '';
              raw = '';
              modal = '';
            }}>Discard</button
          >
          <button onclick={() => (modal = '')}>Close</button>
        </div>
      {:else if modal === 'repo-collision'}
        <p>
          Another agent is using this working copy. Concurrent edits can conflict. Separate
          worktrees still share external ports and databases.
        </p>
        <div class="form-row">
          <button onclick={() => (modal = 'new-session')}>Choose another working copy</button
          ><button
            onclick={() => {
              acknowledgedRepo = form.repo;
              void task(createSession);
            }}>Use shared working copy</button
          >
        </div>
      {:else if modal === 'close-native'}
        <p>
          These PowerShell sessions may have running work. Choose what happens when this view
          closes.
        </p>
        <p class="hint">
          Background sessions can be reattached from Sessions. Continuing in a PowerShell window
          keeps the same process and waits for the window to attach.
        </p>
        <div class="native-close-actions">
          <button disabled={busy} onclick={() => void task(() => finishNativeClose('background'))}
            >Keep in background</button
          >
          <button disabled={busy} onclick={() => void task(() => finishNativeClose('external'))}
            >Continue in PowerShell window</button
          >
          <button
            class="danger"
            disabled={busy}
            onclick={() => void task(() => finishNativeClose('end'))}>End sessions</button
          >
          <button
            disabled={busy}
            onclick={() => {
              nativeCloseDone = null;
              modal = '';
            }}>Cancel</button
          >
        </div>
      {:else if modal === 'close-terminal'}
        <p>
          Closing a plain terminal stops its shell and may stop running work. Attached tmux sessions
          keep running.
        </p>
        <div class="form-row">
          <button onclick={() => (modal = '')}>Cancel</button><button
            class="danger"
            onclick={() => {
              const action = pendingClose;
              pendingClose = null;
              modal = '';
              action?.();
            }}>Close terminal</button
          >
        </div>
      {:else if modal === 'listen-intro'}
        <h2>Speak into your terminal</h2>
        <p>
          Select a terminal, then tap <strong>{bindingFor('dictation').label}</strong> to listen. In toggle
          mode, tap again to stop and review the text in that terminal. Double-tap the same shortcut to
          send it with Enter.
        </p>
        <p>
          Keyboard shortcuts and mouse or device buttons work while DazeDev is focused. You can also
          choose hold-to-talk: hold to record, release to stop.
        </p>
        <p class="hint">
          Transcription and optional tidy-up run locally. {voiceReady
            ? 'Your voice models are ready.'
            : 'The selected voice stack needs a one-time download. Installed models are reused.'}
        </p>
        {#if device.voice.automatic}<p class="hint">
            Automatic Enter is currently enabled. Dictation will submit as soon as transcription
            finishes. Turn it off in settings to review first.
          </p>{/if}
        <div class="row">
          <button
            class="primary"
            onclick={() => {
              device.listenOnboarded = true;
              persistDevice();
              if (!voiceReady) {
                modal = 'voice-setup';
                return;
              }
              modal = '';
              void task(() => record('dictation', listenIntroTarget));
            }}>{voiceReady ? 'Start listening' : 'Set up local voice'}</button
          >
          <button
            onclick={() => {
              device.listenOnboarded = true;
              persistDevice();
              void task(async () => {
                await openSettings();
                await tick();
                const settings = document.querySelector<HTMLDetailsElement>('#voice-shortcuts');
                if (settings) {
                  settings.open = true;
                  settings.scrollIntoView({ block: 'start' });
                }
              });
            }}>Change keyboard or mouse shortcut</button
          >
          <button
            onclick={() => {
              device.listenOnboarded = true;
              persistDevice();
              modal = '';
            }}>Later</button
          >
        </div>
      {:else if modal === 'voice-setup'}
        <p>
          Voice models are downloaded separately from DazeDev to keep the app small and let you
          choose your stack. Downloads use the verified versions supported by this release.
        </p>
        <p class="hint">
          Default stack: Parakeet transcription, Silero silence detection and S1-mini cleanup.
          Download once; everything runs offline on this device with no account or per-use cost.
        </p>
        <p>
          Selected: <strong
            >{models.find((m) => m.id === device.voice.model)?.name || device.voice.model}</strong
          >
        </p>
        <button onclick={() => void task(openSettings)}>Choose a model in Settings</button>
        <div class="stack-options">
          {#each models.filter((m) => m.family === 'asr') as m}<p>
              <strong>{m.name}</strong>
              {modelDescriptions[m.id]}
            </p>{/each}
        </div>
        <p><strong>Silero:</strong> {modelDescriptions.silero}</p>
        <label class="check"
          ><input
            type="checkbox"
            bind:checked={device.voice.cleanup}
            disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
            onchange={() => void changeVoiceModel()}
          /> S1-mini cleanup</label
        >
        <p class="hint">
          {modelDescriptions['s1-mini']} Review important names and commands before pressing Enter.
        </p>
        <div class="form-row">
          <button
            disabled={busy}
            onclick={() => {
              device.voiceSetupDismissed = true;
              persistDevice();
              modal = '';
            }}>Later</button
          >
          <button
            class="primary"
            disabled={busy}
            onclick={() =>
              void task(async () => {
                await setupVoice();
                modal = '';
              })}>{busy ? 'Setting up…' : 'Download selected voice stack'}</button
          >
        </div>
        {#each Object.entries(downloadProgress) as [id, progress]}<div
            class="download-progress"
            role="status"
          >
            <span>{id}: {progress}</span><progress
              aria-label={`${id} download`}
              max="100"
              value={progress.endsWith('%') ? parseInt(progress) : undefined}
            ></progress>
          </div>{/each}
      {:else if modal === 'changelog'}
        <div class="row" aria-label="Release platform">
          {#each ['all', 'desktop', 'mobile'] as platform}
            <button
              aria-pressed={releaseFilter === platform}
              onclick={() => (releaseFilter = platform as typeof releaseFilter)}
              >{platform === 'all' ? 'All' : platform === 'desktop' ? 'Desktop' : 'Mobile'}</button
            >
          {/each}
        </div>
        {#if historyStatus}<p class="hint" role="status">{historyStatus}</p>{/if}
        {#if releaseFilter !== 'mobile' && availableUpdate && !releaseHistory.some((entry) => entry.platform === 'desktop' && entry.version === availableUpdate?.version)}<article
            class="release-entry"
          >
            <h3>Desktop · {releaseHeading(availableUpdate.version)}</h3>
            <p>{availableUpdate.notes}</p>
          </article>{/if}
        {#each releaseHistory.filter((entry) => releaseFilter === 'all' || entry.platform === releaseFilter) as entry}<article
            class="release-entry"
          >
            <h3>
              {entry.platform === 'desktop' ? 'Desktop' : 'Mobile'} · {entry.platform === 'desktop'
                ? releaseHeading(entry.version, entry.date)
                : `${entry.date} : ${entry.version}`}{entry.channel === 'development'
                ? ' · Development build'
                : ''}
            </h3>
            <p>{entry.notes}</p>
          </article>{/each}
        <button onclick={() => (modal = changelogReturn)}>Back</button>
      {:else if modal === 'delete-host' && deleteHostTarget}
        <p>
          Remove <strong>{deleteHostTarget.name}</strong> and its workspace list from this device?
        </p>
        <p class="hint">
          Plain shells close and tmux terminals detach. Remote tmux sessions and the host's saved
          workspace configuration stay intact.
        </p>
        <div class="form-row">
          <button disabled={busy} onclick={() => (modal = '')}>Cancel</button><button
            class="danger"
            disabled={busy}
            onclick={() => void task(() => deleteHost(deleteHostTarget!))}>Delete host</button
          >
        </div>
      {:else if modal === 'host-colors' && colorHost}
        <p>
          Colors for {colorHost.name}. Workspaces and terminals inherit these unless they have their
          own colors. This choice is saved on this device.
        </p>
        <ColorPicker
          value={device.hosts.find((h) => h.id === colorHost?.id)?.colors}
          onChange={(colors) => {
            device.hosts = device.hosts.map((h) => (h.id === colorHost?.id ? { ...h, colors } : h));
            persistDevice();
          }}
        />
      {:else if modal === 'tmux-settings' && tmuxHost}
        <TmuxSettings host={tmuxHost} appScrollback={device.terminal?.scrollback || 4000} />
      {:else if modal === 'pane-colors' && colorPane}
        {@const owner = workspaces.find((w) => w.panes.some((p) => p.id === colorPane?.id))}
        <p class="hint">
          Overrides this terminal only. Other terminals follow their workspace colors.
        </p>
        <ColorPicker
          value={colorPane.colors}
          inherited={tabColors(
            owner?.colors,
            device.hosts.find((h) => h.id === colorPane?.hostId)?.colors,
          )}
          resetLabel="Use workspace colors"
          onChange={(colors) => {
            if (!owner || !colorPane) return;
            colorPane.colors = colors;
            updateWorkspace({
              ...owner,
              panes: owner.panes.map((p) => (p.id === colorPane!.id ? { ...p, colors } : p)),
            });
          }}
        />
      {:else if modal === 'workspace' && workspaceTarget}<form
          onsubmit={(e) => {
            e.preventDefault();
            if (workspaceTarget) {
              updateWorkspace({
                ...workspaceTarget,
                name: renameValue.trim() || workspaceTarget.name,
              });
              modal = '';
            }
          }}
        >
          <label>Workspace name<input bind:value={renameValue} /></label>
          <p class="hint">
            Applies to this sidebar tab and all terminals without their own color override,
            including new terminals.
          </p>
          <ColorPicker
            value={workspaceTarget.colors}
            inherited={device.hosts.find((h) => h.id === workspaceTarget?.hostId)?.colors}
            resetLabel="Use host colors"
            onChange={(colors) => {
              if (workspaceTarget) {
                workspaceTarget = { ...workspaceTarget, colors };
                updateWorkspace(workspaceTarget);
              }
            }}
          />
          <div class="form-row">
            <button
              type="button"
              onclick={() => {
                if (!workspaceTarget) return;
                const list = profiles[workspaceTarget.hostId].workspaces;
                const i = list.findIndex((w) => w.id === workspaceTarget!.id);
                if (i > 0) {
                  [list[i - 1], list[i]] = [list[i], list[i - 1]];
                  saved(workspaceTarget.hostId);
                }
              }}>Move up</button
            ><button
              type="button"
              onclick={() => {
                if (!workspaceTarget) return;
                const list = profiles[workspaceTarget.hostId].workspaces;
                const i = list.findIndex((w) => w.id === workspaceTarget!.id);
                if (i < list.length - 1) {
                  [list[i + 1], list[i]] = [list[i], list[i + 1]];
                  saved(workspaceTarget.hostId);
                }
              }}>Move down</button
            >
          </div>
          <div class="form-row">
            <button
              type="button"
              onclick={() => {
                modal = '';
                closeWorkspace(workspaceTarget!);
              }}>Delete workspace</button
            ><button class="primary">Save name</button>
          </div>
          <p class="hint">
            Deleting removes the saved layout and closes plain shells. Attached tmux sessions stay
            alive.
          </p>
        </form>{:else if modal === 'host'}<form
          onsubmit={(e) => {
            e.preventDefault();
            void task(async () => {
              if (!newHost.name.trim()) throw new Error('Give this connection a name.');
              device.hosts = device.hosts.some((h) => h.id === newHost.id)
                ? device.hosts.map((h) => (h.id === newHost.id ? { ...newHost } : h))
                : [...device.hosts, { ...newHost }];
              device.activeHost = newHost.id;
              persistDevice();
              modal = '';
              void connect({ ...newHost });
            });
          }}
        >
          <label
            >Connection name<input
              bind:value={newHost.name}
              placeholder="Development box"
              required
            /></label
          ><label
            >Location<select bind:value={newHost.kind}
              ><option value="ssh">SSH · any network</option><option value="tailscale"
                >Tailscale SSH · device identity</option
              ><option value="powershell">Local Windows PowerShell</option><option value="wsl"
                >Local WSL distribution</option
              ><option value="local">Local macOS / Linux</option></select
            ></label
          >{#if newHost.kind === 'ssh' || newHost.kind === 'tailscale'}<label
              >{newHost.kind === 'tailscale'
                ? 'Tailscale hostname or IP'
                : 'SSH alias or hostname'}<input
                list="ssh-aliases"
                bind:value={newHost.address}
                placeholder="dev-box or 100.x.x.x"
                required
              /></label
            >
            <div class="form-row">
              <label
                >SSH user<input
                  bind:value={newHost.user}
                  required={newHost.kind === 'tailscale'}
                  placeholder={newHost.kind === 'tailscale'
                    ? 'Linux username, e.g. ubuntu'
                    : 'From SSH config'}
                /></label
              >{#if newHost.kind === 'ssh'}<label
                  >Port<input
                    type="number"
                    bind:value={newHost.port}
                    min="1"
                    max="65535"
                    placeholder="22"
                  /></label
                >{/if}
            </div>
            <details>
              <summary>Connection options</summary>{#if newHost.kind === 'ssh'}<label
                  >Private key path<input
                    bind:value={newHost.key}
                    placeholder="Use SSH config / agent"
                  /></label
                ><label>Jump host<input bind:value={newHost.jump} placeholder="bastion" /></label
                >{/if}<label
                >tmux socket name<input bind:value={newHost.socket} placeholder="default" /></label
              >
            </details>
            <datalist id="ssh-aliases"
              >{#each aliases as alias}<option value={alias}></option>{/each}</datalist
            >
            <p class="hint">
              {#if newHost.kind === 'tailscale'}
                Uses your signed-in Tailscale device identity and Tailscale-verified host keys. No
                SSH password or key is needed. The target must enable Tailscale SSH; an accept
                policy connects directly, while a check policy can require browser reauthentication.
                Native Windows cannot serve Tailscale SSH. For WSL, use its Linux endpoint's own
                tailnet name. DazeDev remote hosts need Python 3 and tmux.
              {:else}
                Your OpenSSH config, keys, certificates and agent are respected. Password and
                host-key prompts appear next. Passwords are never saved.
              {/if}
            </p>{:else if newHost.kind === 'powershell'}
            <label
              >Shell<select bind:value={newHost.nativeShell}>
                <option value="powershell">Windows PowerShell</option><option value="pwsh"
                  >PowerShell 7 (installed)</option
                >
              </select></label
            >
            <p class="hint">
              Runs natively on this Windows machine, with hardware access and no WSL requirement.
              Sessions can stay running when DazeDev closes. PowerShell 7 must already be installed.
            </p>
          {:else if newHost.kind === 'wsl'}<label
              >Distribution<input
                bind:value={newHost.distro}
                placeholder="Default WSL distribution"
              /></label
            >{/if}
          {#if newHost.kind !== 'powershell'}<p class="hint">
              The host needs Python 3 and tmux. DazeDev runs its management helper in memory.
            </p>{/if}
          <button class="primary" disabled={busy}>Save & connect <ArrowUpRight size={15} /></button>
        </form>
      {:else if modal === 'launch'}<form
          onsubmit={(e) => {
            e.preventDefault();
            void task(launch);
          }}
        >
          <div class="form-row">
            <label
              >Workspace name<input
                required={!launchAxis}
                bind:value={form.name}
                placeholder="Core · planning"
              /></label
            ><label>Terminals<input type="number" min="1" step="1" bind:value={form.count} /></label
            >
          </div>
          <label
            >Host<select
              bind:value={form.hostId}
              onchange={() => {
                form.repo = '';
                form.identity = '';
                form.model = '';
                form.effort = '';
              }}
              ><option value="">Choose a connected host</option>{#each device.hosts as h}<option
                  value={h.id}>{h.name} · {connections[h.id] || 'disconnected'}</option
                >{/each}</select
            ></label
          >
          {#if launchFromCommand}
            <p class="hint">
              {launchAxis ? 'Add to the current workspace' : 'Create a new workspace'} · {form.agent ===
              'shell'
                ? form.repo
                  ? 'New tmux shells'
                  : 'Plain shells'
                : `New ${form.agent} sessions`}. Review these options before starting.
            </p>
            <label
              >Agent<select bind:value={form.agent}
                ><option value="shell">Plain shell</option
                >{#each ['codex', 'claude', 'gemini', 'opencode'] as agent}<option value={agent}
                    >{agent}</option
                  >{/each}</select
              ></label
            >
            {#if form.agent !== 'shell'}<p class="hint">
                The agent must already be installed on the selected host. Each terminal starts a
                separate session.
              </p>{/if}
            <label class="check"
              ><input type="checkbox" bind:checked={launchChooseRepo} /> Choose repository before starting</label
            >
            {#if launchChooseRepo}
              <RepoPicker
                repos={inventories[form.hostId]?.repos || []}
                hostId={form.hostId}
                bind:value={form.repo}
                warnings={inventories[form.hostId]?.warnings || []}
                disabled={busy}
              />
            {:else if form.repo}<p class="hint">Repository: {form.repo}</p>{/if}
            {#if form.agent !== 'shell' && form.repo}<label class="check"
                ><input type="checkbox" bind:checked={form.brief} /> Read repository instructions and
                give a concise orientation</label
              >{/if}
          {:else}
            <p class="hint">
              Open plain shells in this host’s home directory. Use each terminal’s tmux button to
              join or create a session.
            </p>
          {/if}
          <button class="primary" disabled={busy || !form.hostId}
            >{busy
              ? 'Starting…'
              : `Open ${form.count} ${form.count === 1 ? 'terminal' : 'terminals'}`}
            <ArrowUpRight size={16} /></button
          >
        </form>
      {:else if modal === 'sessions'}
        <div class="list-toolbar">
          <p>
            {device.hosts.find((h) => h.id === sessionHost)?.name}{sessionPane
              ? ' · ' + sessionPane.name
              : ''}
          </p>
          <button
            aria-label="Refresh sessions"
            disabled={busy}
            onclick={() =>
              void task(async () => {
                await refreshSessions(sessionHost);
              })}><RefreshCw size={16} /></button
          >
        </div>
        <button class="primary" disabled={busy} onclick={() => void task(startSession)}
          ><Plus size={16} />
          {device.hosts.find((h) => h.id === sessionHost)?.kind === 'powershell'
            ? 'Create new PowerShell session'
            : 'Create new tmux session'}</button
        >
        <p class="hint">
          Joining attaches only. It never restarts the agent or sends an orientation prompt.
          Switching replaces this terminal view; existing sessions keep running. Attached views
          share input and terminal size. Native PowerShell work stops only when you end the session
          or shut down Windows.
        </p>
        {#each sessions as s}<div class="item-row">
            <TerminalIcon size={18} />
            <div>
              <strong>{s.name}</strong><small>{s.windows} windows · {s.attached} attached</small>
            </div>
            <button disabled={busy} onclick={() => void task(() => attach(s))}>Join</button>
            <button
              class="icon danger"
              disabled={busy}
              aria-label={`Delete ${s.name}`}
              onclick={() => {
                destroyTarget = { ...s, hostId: sessionHost };
                modal = 'destroy';
              }}><Trash2 size={15} /></button
            >
          </div>{:else}<p class="hint">
            No sessions on this host. Create a named session above.
          </p>{/each}
      {:else if modal === 'new-session'}
        <p class="hint">
          Step {sessionStep + 1} of 3 · {device.hosts.find((h) => h.id === sessionHost)?.name}
        </p>
        <form
          onsubmit={(e) => {
            e.preventDefault();
            nameError = sessionNameError(form.name);
            if (nameError) {
              sessionStep = 0;
              return;
            }
            if (sessionStep < 2) sessionStep += 1;
            else void task(createSession);
          }}
        >
          {#if sessionStep === 0}
            <label
              >Session name<input
                required
                aria-invalid={!!nameError}
                aria-describedby="session-name-error"
                oninput={() => {
                  nameError = '';
                }}
                bind:value={form.name}
                placeholder="planning"
              /></label
            >
            {#if nameError}<p id="session-name-error" class="inline-error" role="alert">
                {nameError}
              </p>{:else}<p id="session-name-error" class="hint">
                Use letters, numbers, underscores or hyphens.
              </p>{/if}
          {:else if sessionStep === 1}
            <label
              >Agent<select
                bind:value={form.agent}
                onchange={() => {
                  form.model = '';
                  form.effort = '';
                }}
                ><option value="shell">None · plain shell</option
                >{#each inv?.agents || [] as a}<option value={a.id}
                    >{a.id}{a.path ? '' : ' · install and start'}</option
                  >{/each}</select
              ></label
            >
            {#if form.agent !== 'shell'}
              <label
                >Model<input
                  bind:value={form.model}
                  disabled={!selectedAgent?.modelFlag}
                  placeholder="Agent default"
                  list="session-models"
                /></label
              >
              <datalist id="session-models"
                >{#each selectedAgent?.models || [] as m}<option value={m.id}>{m.name}</option
                  >{/each}</datalist
              >
              <label
                >Effort<select bind:value={form.effort} disabled={!selectedAgent?.effortFlag}
                  ><option value="">Agent default</option
                  >{#each selectedAgent?.models.find((m) => m.id === form.model)?.efforts || ['low', 'medium', 'high'] as effort}<option
                      value={effort}>{effort}</option
                    >{/each}</select
                ></label
              >
            {/if}
          {:else}
            <button type="button" onclick={() => (form.repo = '')}>Use host home directory</button>
            <RepoPicker
              repos={inv?.repos || []}
              hostId={form.hostId || device.activeHost}
              bind:value={form.repo}
              warnings={inv?.warnings || []}
              disabled={busy}
            />
            <label
              >GitHub identity<select bind:value={form.identity}
                ><option value="">Existing host environment</option
                >{#each inv?.identities || [] as i}<option value={i.login}>{i.login}</option
                  >{/each}</select
              ></label
            >
            {#if form.repo && form.agent !== 'shell'}<label class="check"
                ><input type="checkbox" bind:checked={form.brief} /> Read repo instructions and give a
                concise orientation</label
              >{/if}
            <p class="hint">
              {form.repo
                ? 'Starts in the selected working copy. No branch or worktree is created automatically.'
                : 'Starts in the host’s home directory without a repository orientation prompt.'}
            </p>
          {/if}
          <div class="form-row">
            <button
              type="button"
              disabled={busy}
              onclick={() => {
                if (sessionStep) sessionStep -= 1;
                else modal = 'sessions';
              }}>Back</button
            ><button class="primary" disabled={busy}
              >{busy
                ? 'Creating…'
                : sessionStep < 2
                  ? 'Next'
                  : form.agent !== 'shell' && !selectedAgent?.path
                    ? 'Trust upstream installer and start session'
                    : 'Create session & join'}</button
            >
          </div>
        </form>
      {:else if modal === 'rename'}<form
          onsubmit={(e) => {
            e.preventDefault();
            void task(renameSession);
          }}
        >
          <label
            >Session name<input bind:value={renameValue} required pattern="[A-Za-z0-9_-]+" /></label
          ><button class="primary" disabled={busy}>Rename</button>
        </form>
      {:else if modal === 'destroy'}<p>
          Destroy <strong>{destroyTarget?.name}</strong> on
          <strong
            >{device.hosts.find((h) => h.id === (destroyTarget?.hostId || device.activeHost))
              ?.name}</strong
          > and terminate all its running processes? All unsaved running work will be lost, including
          work in other clients attached to this session. This cannot be undone.
        </p>
        <div class="form-row">
          <button onclick={() => (modal = 'sessions')}>Keep session</button><button
            class="danger"
            disabled={busy}
            onclick={() => void task(destroySession)}>Destroy session</button
          >
        </div>
      {:else if modal === 'broadcast'}
        <BroadcastDialog
          bind:text={broadcastText}
          bind:allHosts={broadcastAllHosts}
          bind:allWorkspaces={broadcastAllWorkspaces}
          hostName={broadcastHostName}
          workspaceName={broadcastWorkspaceName}
          phase={voiceMode === 'broadcast' ? voiceStatus : 'idle'}
          busy={broadcastBusy}
          snapshot={broadcastSnapshot}
          result={broadcastResult}
          onrecord={() => void task(() => record('broadcast'))}
          onreview={() => void task(reviewBroadcast)}
          onsend={() => void task(sendBroadcast)}
          onclose={() => {
            if (!broadcastBusy) {
              if (voiceMode === 'broadcast' && voiceStatus !== 'idle') void task(cancelVoice);
              modal = '';
            }
          }}
        />
      {:else if modal === 'command'}<form
          onsubmit={(e) => {
            e.preventDefault();
            commandSubmit();
          }}
        >
          <p role="note">
            <strong
              >Early alpha: commands may be interpreted incorrectly. Review before running.</strong
            >
          </p>
          <p class="hint">
            IDE commands control DazeDev. They are never pasted into a terminal. While listening,
            Enter finishes transcription and Escape abandons it.
          </p>
          <label
            >Command<textarea
              bind:value={commandText}
              rows="3"
              onkeydown={(e) => {
                if (
                  e.key === 'Enter' &&
                  !e.repeat &&
                  !e.isComposing &&
                  e.keyCode !== 229 &&
                  !e.shiftKey &&
                  !e.ctrlKey &&
                  !e.altKey &&
                  !e.metaKey
                ) {
                  e.preventDefault();
                  commandSubmit();
                }
              }}
              readonly={voiceStatus !== 'idle'}
              oninput={() => {
                commandReview = null;
                commandError = '';
              }}
              placeholder="Open four new terminals with Codex"></textarea></label
          >
          {#if voiceMode === 'command' && voiceStatus !== 'idle'}<p role="status">
              {voiceStatus === 'finishing-recording'
                ? 'Finishing recording…'
                : voiceStatus === 'listening'
                  ? 'Listening…'
                  : voiceStatus === 'loading'
                    ? voicePreparing
                    : 'Finishing transcription…'}
            </p>{/if}
          {#if commandError}<p role="alert">{commandError}</p>{/if}
          {#if commandReview}
            <section class="settings-section" aria-label="Proposed command">
              <h3>Proposed action</h3>
              <p>{commandReview.summary}</p>
            </section>
            <div class="form-row">
              <button
                type="button"
                onclick={() => {
                  commandReview = null;
                  commandError = '';
                }}>Decline / edit</button
              >
              <button
                type="button"
                class="primary"
                disabled={busy || voiceStatus !== 'idle'}
                onclick={() => void implementCommand()}>Implement</button
              >
            </div>
          {:else}
            <div class="form-row">
              <button
                type="button"
                disabled={voiceStatus !== 'idle' && voiceStatus !== 'listening'}
                onclick={() => void task(() => record('command'))}
                ><Mic size={15} />{voiceMode === 'command' && voiceStatus === 'listening'
                  ? 'Stop and review'
                  : 'Speak command'}</button
              >
              <button
                class="primary"
                disabled={!commandText.trim() || busy || voiceStatus !== 'idle'}>Interpret</button
              >
            </div>
            <details>
              <summary>Command library</summary>
              <p class="hint">
                Choose an example, edit it, then interpret. “Codecs” is accepted as Codex here.
              </p>
              <div class="command-examples">
                {#each commandCatalogue as command}<button
                    type="button"
                    disabled={voiceStatus !== 'idle'}
                    title={command.description}
                    onclick={() => {
                      commandText = command.example;
                      commandReview = null;
                      commandError = '';
                    }}>{command.example}<ArrowUpRight size={13} /></button
                  >{/each}
              </div>
            </details>
          {/if}
        </form>
      {:else if modal === 'settings'}
        <div class="settings-compact settings-page">
          <section class="settings-section" aria-label="Updates">
            <h3>Updates · DazeDev {releaseVersion(clientInfo.version)}</h3>

            {#if clientInfo.updateSupported}<label class="check"
                ><input
                  type="checkbox"
                  checked={device.checkUpdates !== false}
                  onchange={(e) => {
                    device.checkUpdates = e.currentTarget.checked;
                    persistDevice();
                  }}
                /> Check at startup and every 30 minutes</label
              >
              <div class="settings-actions">
                <button disabled={updateBusy} onclick={() => void checkUpdates(true)}
                  >Check for updates</button
                ><button onclick={() => showChangelog()}>Changelog</button><button
                  onclick={() => exportDiagnostics(clientInfo.version)}>Export diagnostics</button
                >
              </div>{:else}<p class="hint">
                Test build: download updates manually for this platform.
              </p>
              <div class="settings-actions">
                <button onclick={() => showChangelog()}>Changelog</button><button
                  onclick={() => exportDiagnostics(clientInfo.version)}>Export diagnostics</button
                >
              </div>{/if}
            <p class="hint">
              Opening Settings always checks for updates, even when automatic checks are off.
            </p>
            {#if availableUpdate}<button onclick={openUpdates}
                >Update to {releaseVersion(availableUpdate.version)}</button
              >{/if}
            <p class="hint" role="status">{updateStatus || updateLabel}</p>
            {#if clientInfo.migration}<p class="hint">{clientInfo.migration}</p>{/if}
          </section>

          <PinSettings />
          <DictionarySettings
            compact
            hosts={device.hosts.filter((h) => h.kind === 'ssh' || h.kind === 'tailscale')}
          />
          <div class="voice-readiness" role="status">
            <span class="readiness-light" class:ready={voiceReady}></span>
            <strong
              >{voiceReady
                ? 'Voice ready'
                : modelsLoaded
                  ? 'Voice setup incomplete'
                  : 'Checking voice models…'}</strong
            >
          </div>
          <details class="settings-section settings-disclosure" open={!voiceReady}>
            <summary
              >Voice models <span
                >{models.find((m) => m.id === device.voice.model)?.name || 'Setup required'}</span
              ></summary
            >
            {#if hardware && recommendation}
              <p class="hint">
                {hardware.logicalCpus} CPU threads · {hardware.memory
                  ? `${(hardware.memory / 1024 ** 3).toFixed(0)} GB RAM`
                  : 'RAM unknown'} · {hardware.vulkan ? 'Vulkan GPU available' : 'CPU only'}
              </p>
              <p class="hint">{recommendation.reason} Hardware guidance, not a speed benchmark.</p>
            {:else}<p class="hint">{hardwareError || 'Detecting hardware…'}</p>{/if}
            <div class="model-list" role="radiogroup" aria-label="Transcription model">
              {#each models.filter((m) => m.family === 'asr') as m}
                <div class="model-row" class:selected={device.voice.model === m.id}>
                  <input
                    type="radio"
                    name="voice-model"
                    aria-label={`Select ${m.name}${m.installed ? '' : ' (download required)'}`}
                    checked={device.voice.model === m.id}
                    disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading || busy}
                    onclick={(e) => {
                      e.preventDefault();
                      void task(() => selectVoiceModel(m.id));
                    }}
                  />
                  <div>
                    <strong>{m.name}</strong>{#if recommendation?.model === m.id}<span
                        class="recommended">Recommended</span
                      >{/if}
                    <small>{Math.round(m.size / 1e6)} MB · {modelDescriptions[m.id]}</small>
                    {#if hardware && device.voice.model === m.id}<small
                        >{accelerationAdvice(hardware, m.id, device.voice.backend)}</small
                      >{/if}
                  </div>
                  {#if m.installed}<span class="installed"><Check size={13} /> Installed</span>
                    <button
                      class="icon"
                      aria-label={`Remove ${m.name}`}
                      disabled={voiceStatus !== 'idle' ||
                        switchingVoice ||
                        voiceCacheLoading ||
                        busy}
                      onclick={() =>
                        void task(async () => {
                          await call('voice_release');
                          voiceCacheSignature = '';
                          await call('model_remove', { id: m.id });
                          await loadModels();
                        })}><Trash2 size={14} /></button
                    >
                  {:else}<button
                      disabled={voiceStatus !== 'idle' ||
                        switchingVoice ||
                        voiceCacheLoading ||
                        busy}
                      onclick={() => void task(() => selectVoiceModel(m.id))}
                      ><Download size={14} />{downloadProgress[m.id] || 'Download & select'}</button
                    >{/if}
                  {#if downloadProgress[m.id]}<progress
                      aria-label={`${m.name} download`}
                      max="100"
                      value={downloadProgress[m.id].endsWith('%')
                        ? parseInt(downloadProgress[m.id])
                        : undefined}
                    ></progress>{/if}
                </div>
              {/each}
            </div>
            {#if switchingVoice}<p role="status">
                {Object.keys(downloadProgress).length ? 'Downloading model…' : 'Switching model…'}
              </p>{/if}
            <label
              >Acceleration<select
                bind:value={device.voice.backend}
                disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
                onchange={() => void changeVoiceModel()}
              >
                <option value="auto">Automatic</option><option value="cpu">CPU</option><option
                  value="vulkan"
                  disabled={!!hardware && !hardware.vulkan}>GPU · Vulkan</option
                >
              </select></label
            >
            <details>
              <summary>Supporting models · Silero / S1-mini</summary>
              <div class="model-list">
                {#each models.filter((m) => m.family !== 'asr') as m}<div class="model-row">
                    <div>
                      <strong>{m.name}</strong><small
                        >{Math.round(m.size / 1e6)} MB · {m.license}</small
                      >
                    </div>
                    {#if m.installed}<span class="installed"><Check size={13} /> Installed</span
                      ><button
                        class="icon"
                        aria-label={`Remove ${m.name}`}
                        disabled={voiceStatus !== 'idle' ||
                          switchingVoice ||
                          voiceCacheLoading ||
                          busy}
                        onclick={() =>
                          void task(async () => {
                            await call('model_remove', { id: m.id });
                            await loadModels();
                          })}><Trash2 size={14} /></button
                      >{:else}<button
                        disabled={!!downloadProgress[m.id]}
                        onclick={() =>
                          void task(async () => {
                            downloadProgress[m.id] = 'Starting';
                            try {
                              await call('model_download', { id: m.id });
                              await loadModels();
                            } finally {
                              delete downloadProgress[m.id];
                            }
                          })}><Download size={14} />{downloadProgress[m.id] || 'Download'}</button
                      >{/if}
                    {#if downloadProgress[m.id]}<progress
                        aria-label={`${m.name} download`}
                        max="100"
                        value={downloadProgress[m.id].endsWith('%')
                          ? parseInt(downloadProgress[m.id])
                          : undefined}
                      ></progress>{/if}
                  </div>{/each}
              </div>
            </details>
            {#if !voiceReady}<button
                disabled={busy || switchingVoice || voiceStatus !== 'idle'}
                onclick={() => void task(setupVoice)}
                ><Download size={14} /> Install missing stack components</button
              >{/if}
          </details>
          <details class="settings-section settings-disclosure" id="voice-shortcuts">
            <summary
              >Voice hotkeys and input <span
                >{bindingFor('dictation').label} · {bindingFor('command').label}</span
              ></summary
            >
            <p class="hint">
              Choose separate shortcuts for terminal dictation and IDE commands. Capture stops if
              DazeDev loses focus.
            </p>
            <p>
              In toggle mode: tap to listen, tap to stop, then double-tap to submit the reviewed
              text. The terminal must still be unchanged. Hold-to-talk keeps its press and release
              behavior.
            </p>
            {#each ['dictation', 'command'] as mode}
              {@const voiceInput = mode as VoiceMode}
              <h4>{voiceInput === 'dictation' ? 'Terminal dictation' : 'IDE voice command'}</h4>
              <div class="hotkey-assignments">
                <div class="hotkey-record">
                  <label
                    >Keyboard / device<input readonly value={bindingFor(voiceInput).label} /></label
                  >
                  <button onclick={() => captureShortcut(voiceInput)}
                    >{capturingShortcut === voiceInput ? 'Press hotkey…' : 'Record hotkey'}</button
                  >
                </div>
                <label
                  >Mouse / device<select
                    value=""
                    onchange={(e) => {
                      const key = Number(e.currentTarget.value);
                      if (key)
                        void task(() =>
                          saveShortcut(
                            {
                              key,
                              ctrl: false,
                              alt: false,
                              shift: false,
                              meta: false,
                              label:
                                e.currentTarget.selectedOptions[0].textContent || 'Device button',
                            },
                            voiceInput,
                          ),
                        );
                    }}
                  >
                    <option value="">Choose a button</option><option value="4"
                      >Middle mouse button</option
                    ><option value="5">Mouse button 4</option><option value="6"
                      >Mouse button 5</option
                    ><option value="179">Media play/pause</option><option value="176"
                      >Media next</option
                    ><option value="177">Media previous</option><option value="173"
                      >Volume mute</option
                    >
                  </select></label
                >
              </div>
            {/each}
            {#if shortcutStatus}<p role="status" class="hint">{shortcutStatus}</p>{/if}
            <details class="settings-advanced">
              <summary>Recording behavior and model memory</summary>
              <p class="hint">
                While listening, Enter finishes transcription and leaves dictated text for review,
                even when automatic submission is enabled. Escape abandons capture. These keys never
                submit a terminal prompt or implement an IDE command while recording.
              </p>
              <label
                >Activation<select bind:value={device.inputMode} onchange={persistDevice}
                  ><option value="toggle">Press to start / stop</option><option value="hold"
                    >Hold to talk</option
                  ></select
                ></label
              >
              <div class="settings-checks">
                <label class="check"
                  ><input
                    type="checkbox"
                    bind:checked={device.voice.cleanup}
                    disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
                    onchange={() => void changeVoiceModel()}
                  /> S1-mini tidy-up</label
                ><label
                  >Voice model memory<select
                    value={device.voice.keepLoaded === undefined
                      ? 'legacy'
                      : device.voice.keepLoaded
                        ? 'ready'
                        : 'release'}
                    disabled={voiceStatus !== 'idle' || switchingVoice || voiceCacheLoading}
                    onchange={(e) => {
                      device.voice.keepLoaded =
                        e.currentTarget.value === 'legacy'
                          ? undefined
                          : e.currentTarget.value === 'ready';
                      void changeVoiceModel();
                    }}
                  >
                    <option value="legacy">Load on first recording (current behavior)</option>
                    <option value="ready">Preload and keep all voice models ready</option>
                    <option value="release">Release models after each recording</option>
                  </select></label
                ><label class="check"
                  ><input
                    type="checkbox"
                    bind:checked={device.voice.automatic}
                    onchange={persistDevice}
                  /> Automatically press Enter after paste</label
                >
              </div>
              <p class="hint">
                {voiceCacheLoading ? 'Preparing voice models… ' : ''}Preload uses more RAM to
                prepare installed models before recording and retain the cleanup service. Release
                mode saves RAM between recordings. The current behavior keeps speech recognition
                after its first use and reloads cleanup when needed. None of these options keeps the
                microphone on.
              </p>
              <p class="hint">
                Text is pasted when ready. Press Enter to send unless automatic entry is enabled.
              </p>
            </details>
            <div class="microphone-settings">
              <label
                >Microphone<select bind:value={device.voice.microphone} onchange={persistDevice}
                  ><option value="">System default</option
                  >{#if device.voice.microphone && !microphones.some((m) => m.deviceId === device.voice.microphone)}<option
                      value={device.voice.microphone}
                      >Selected input unavailable - refresh devices</option
                    >{/if}{#each microphones as m}<option value={m.deviceId}
                      >{m.label || `Audio input ${microphones.indexOf(m) + 1}`}</option
                    >{/each}</select
                ></label
              ><button disabled={microphoneLoading} onclick={() => void refreshMicrophones()}
                >{microphoneLoading ? 'Checking microphones…' : 'Refresh microphones'}</button
              >
              {#if microphoneError}<p class="inline-error" role="alert">{microphoneError}</p>{/if}
            </div>
          </details>
          <details class="settings-section settings-disclosure">
            <summary>Appearance <span>{device.theme}</span></summary>
            <div class="settings-grid">
              <label
                >Theme<select bind:value={device.theme} onchange={persistDevice}
                  ><option value="blue">Blue</option><option value="charcoal">Charcoal</option
                  ><option value="green">Green</option></select
                ></label
              >
              <label
                >Recording highlight<select
                  value={recordingHighlight(device.recordingHighlight)}
                  onchange={(e) => {
                    device.recordingHighlight = recordingHighlight(e.currentTarget.value);
                    persistDevice();
                  }}
                  >{#each recordingHighlights as effect}<option value={effect.id}
                      >{effect.name}</option
                    >{/each}</select
                ></label
              >
              <label
                >Highlight animation<select
                  bind:value={device.highlightMotion}
                  onchange={persistDevice}
                >
                  <option value="system">Follow system</option>
                  <option value="animate">Always animate highlights</option>
                  <option value="static">Keep highlights still</option>
                </select></label
              >
              {#if systemReducedMotion && (!device.highlightMotion || device.highlightMotion === 'system')}
                <p class="muted">
                  Your system has reduced motion enabled. Choose Always animate highlights to enable
                  movement here.
                </p>
              {/if}
              {#if recordingHighlight(device.recordingHighlight) === 'static'}<label
                  >Highlight colour<input
                    type="color"
                    value={device.recordingHighlightColor || '#39bdd7'}
                    oninput={(e) => {
                      device.recordingHighlightColor = e.currentTarget.value;
                      persistDevice();
                    }}
                  /></label
                >{/if}
            </div>
            <div
              class="recording-highlight highlight-preview"
              data-highlight={recordingHighlight(device.recordingHighlight)}
              style:--voice-highlight-color={device.recordingHighlightColor || '#39bdd7'}
              aria-label="Recording highlight preview"
            >
              {#if recordingHighlight(device.recordingHighlight) !== 'static'}<HighlightEffect
                  kind={recordingHighlight(device.recordingHighlight)}
                />{/if}
              Highlight preview
            </div>
          </details>
          {#if device.terminal}<details class="settings-section settings-disclosure">
              <summary>Terminal <span>Text, notifications and clipboard</span></summary>
              <label class="check"
                ><input
                  type="checkbox"
                  checked={device.terminalAlerts?.visual || false}
                  onchange={(e) => {
                    device.terminalAlerts = {
                      sound: device.terminalAlerts?.sound || false,
                      visual: e.currentTarget.checked,
                    };
                    persistDevice();
                  }}
                /> Show terminal completion notifications</label
              >
              <label class="check"
                ><input
                  type="checkbox"
                  checked={device.terminalAlerts?.sound || false}
                  onchange={(e) => {
                    device.terminalAlerts = {
                      visual: device.terminalAlerts?.visual || false,
                      sound: e.currentTarget.checked,
                    };
                    persistDevice();
                    if (e.currentTarget.checked) void softAlert().catch(fail);
                  }}
                /> Play a soft notification sound</label
              >
              <p class="hint">
                Uses explicit terminal notifications or shell command-completion markers. Support
                depends on the agent or shell integration; DazeDev does not infer completion from
                inactivity. Alerts are limited to one every three seconds.
              </p>
              <div class="form-row">
                <label
                  >Font size<input
                    type="number"
                    min="8"
                    max="32"
                    bind:value={device.terminal.fontSize}
                    onchange={persistDevice}
                  /></label
                >
                <label
                  >History lines<input
                    type="number"
                    min="100"
                    max="100000"
                    step="100"
                    bind:value={device.terminal.scrollback}
                    onchange={persistDevice}
                  /></label
                >
              </div>
              <div class="settings-checks">
                <label class="check"
                  ><input
                    type="checkbox"
                    bind:checked={device.terminal.gpu}
                    onchange={persistDevice}
                  /> GPU rendering</label
                >
                <label class="check"
                  ><input
                    type="checkbox"
                    bind:checked={device.terminal.images}
                    onchange={persistDevice}
                  /> Terminal images</label
                >
                <label class="check"
                  ><input
                    type="checkbox"
                    bind:checked={device.terminal.ligatures}
                    onchange={persistDevice}
                  /> Font ligatures</label
                >
              </div>
              <label class="check"
                ><input
                  type="checkbox"
                  bind:checked={device.terminal.screenReader}
                  onchange={persistDevice}
                />Screen reader terminal output</label
              >
              <label
                >Remote clipboard<select
                  bind:value={device.terminal.clipboard}
                  onchange={persistDevice}
                >
                  <option value="off">Off</option><option value="write">Allow remote copy</option
                  ><option value="read-write">Allow remote copy and paste access</option>
                </select></label
              >
              <p class="hint">
                Copy: Ctrl+Shift+C. Paste: Ctrl+V or Ctrl+Shift+V. Search: Ctrl+Shift+F. Remote
                paste access lets programs on the host read this device's clipboard.
              </p>
            </details>{/if}
          <details class="settings-section settings-disclosure">
            <summary>Connections <span>{device.hosts.length} hosts</span></summary>
            <p class="hint">
              Windows local terminals can use native PowerShell or WSL. Native sessions support
              background persistence and same-process handoff to a PowerShell window. Remote
              terminals use the host login shell.
            </p>
            {#if !device.hosts.length}<p class="hint">No hosts configured.</p>{/if}
            <button onclick={addHost}><Plus size={14} /> Add host</button>
            {#each device.hosts as h}<div class="item-row">
                <div><strong>{h.name}</strong><small>{h.address || h.kind}</small></div>
                <button
                  onclick={() => {
                    newHost = { ...h, nativeShell: h.nativeShell || 'powershell' };
                    modal = 'host';
                  }}>Edit</button
                ><button
                  onclick={() =>
                    void task(async () => {
                      await disconnectHost(h.id);
                      connections[h.id] = 'Disconnected';
                    })}>Disconnect</button
                >
              </div>{/each}
            {#if settingsHost}<label
                >Clipboard access for selected host<select
                  value={device.clipboardByHost?.[settingsHost] ||
                    device.terminal?.clipboard ||
                    'write'}
                  onchange={(e) => {
                    (device.clipboardByHost ||= {})[settingsHost] = e.currentTarget.value as
                      'off' | 'write' | 'read-write';
                    persistDevice();
                  }}
                  ><option value="off">Off</option><option value="write">Allow remote copy</option
                  ><option value="read-write">Allow remote copy and paste access</option></select
                ></label
              >{/if}
          </details>
          {#if profiles[settingsHost]}<details class="settings-section settings-disclosure">
              <summary>
                {device.hosts.find((h) => h.id === settingsHost)?.name} preferences
                <span>Saved on host</span>
              </summary>
              <label
                >Repository roots · one per line<textarea rows="3" bind:value={roots}
                ></textarea></label
              ><label
                >Additional orientation guidance<textarea rows="3" bind:value={guidance}
                ></textarea></label
              >
              <label
                >Repository-start prompt template<textarea rows="8" bind:value={promptTemplate}
                ></textarea></label
              >
              <p class="hint">
                Used when starting an agent with orientation enabled and by the terminal Repo
                picker. Saved on this host.
              </p>
              <p class="hint">
                Variables: {promptVariables.map((v) => '{{' + v + '}}').join(', ')}. Values are
                inserted literally.
              </p>
              {#if unknownVariables(promptTemplate).length}<p class="inline-error">
                  Unknown variables: {unknownVariables(promptTemplate).join(', ')}
                </p>{/if}
              <button onclick={() => (promptTemplate = defaultPrompt)}>Reset template</button>
              <details>
                <summary>Preview with example repository</summary>
                <pre class="prompt-preview">{renderPrompt(promptTemplate, promptValues())}</pre>
              </details>
              <div class="form-row">
                <button
                  disabled={unknownVariables(promptTemplate).length > 0}
                  onclick={() => {
                    profiles[settingsHost].preferences.roots = roots
                      .split('\n')
                      .map((x) => x.trim())
                      .filter(Boolean);
                    profiles[settingsHost].preferences.guidance = guidance;
                    profiles[settingsHost].preferences.promptTemplate = promptTemplate;
                    saved(settingsHost);
                    notice = 'Host preferences queued for saving.';
                  }}>Save host preferences</button
                ><button
                  onclick={() =>
                    void task(async () => {
                      const local = $state.snapshot(
                        profiles[settingsHost] || recovery[settingsHost],
                      );
                      if (local) {
                        (device.profileBackups ||= {})[settingsHost] = [
                          local,
                          ...(device.profileBackups?.[settingsHost] || []),
                        ].slice(0, 3);
                        await call('device_save', {
                          value: { ...$state.snapshot(device), recovery },
                        });
                      }
                      const backup = JSON.stringify(local, null, 2);
                      const url = URL.createObjectURL(
                        new Blob([backup], { type: 'application/json' }),
                      );
                      const a = document.createElement('a');
                      a.href = url;
                      a.download = 'dazedev-workspace-recovery.json';
                      a.click();
                      setTimeout(() => URL.revokeObjectURL(url), 1000);
                      replaceProfile(
                        settingsHost,
                        rebindOwner(await rpc<Profile>(settingsHost, 'profile_load'), settingsHost),
                      );
                      profileBlocked[settingsHost] = false;
                      delete recovery[settingsHost];
                      saveState[settingsHost] = 'Restored';
                      persistDevice();
                    })}>Reload host profile</button
                >
              </div>
            </details>{/if}
          <details class="settings-section settings-disclosure">
            <summary>Licenses and credits</summary>
            <p class="hint">Portable data: {clientInfo.dataPath || 'beside the executable'}</p>
            <Licenses />
          </details>
        </div>
      {:else if modal === 'repos'}
        <button disabled={busy} onclick={() => void task(() => refresh())}
          >Refresh repositories</button
        >
        <RepoPicker
          repos={inventories[device.activeHost]?.repos || []}
          hostId={device.activeHost}
          warnings={inventories[device.activeHost]?.warnings || []}
          disabled={busy}
          onchoose={(repo) => {
            showLaunch();
            form.repo = repo.path;
          }}
        />
        <div class="settings-section">
          <h3>GitHub repositories</h3>
          <div class="form-row">
            <label
              >Account<select bind:value={form.identity}
                ><option value="">Select account</option
                >{#each inventories[device.activeHost]?.identities || [] as i}<option
                    value={i.login}>{i.login}</option
                  >{/each}</select
              ></label
            ><button
              disabled={!form.identity || busy}
              onclick={() =>
                void task(async () => {
                  githubRepos = (
                    await rpc<{ full_name: string; description: string }[][]>(
                      device.activeHost,
                      'github_repos',
                      { identity: form.identity },
                    )
                  ).flat();
                })}>Load repositories</button
            >
          </div>
          {#each githubRepos.filter((r) => r.full_name
              .toLowerCase()
              .includes(search.toLowerCase())) as r}<div class="item-row">
              <div>
                <strong>{r.full_name}</strong><small>{r.description || 'GitHub repository'}</small>
              </div>
              <button
                disabled={busy}
                onclick={() =>
                  void task(async () => {
                    const root = profiles[device.activeHost].preferences.roots[0];
                    if (!root) throw new Error('Set a repository root in host settings.');
                    await rpc(device.activeHost, 'clone', {
                      repo: r.full_name,
                      root,
                      identity: form.identity,
                    });
                    await refresh();
                  })}>Clone</button
              >
            </div>{/each}
        </div>
      {:else if modal === 'agents'}<p class="hint">
          Detected on {host?.name || 'the selected host'}. Authentication stays in each agent’s own
          CLI.
        </p>
        {#each inventories[device.activeHost]?.agents || [] as a}<div class="agent-card">
            <div>
              <strong>{a.id}</strong><span class="badge">{a.path ? 'Installed' : 'Not found'}</span>
            </div>
            <small>{a.path || a.install}</small>{#if a.path}<small>{a.version}</small>{/if}
            <button disabled={busy} onclick={() => void task(() => startAgentFromPicker(a.id))}>
              {#if !a.path}<Download size={14} />{/if}{a.path
                ? host?.kind === 'powershell'
                  ? 'Start in new PowerShell session'
                  : 'Start in new tmux session'
                : 'Install and start'}</button
            >
          </div>{/each}<button onclick={() => void task(() => refresh())}
          ><RefreshCw size={14} /> Refresh discovery</button
        >
      {:else if modal === 'worktree'}<form
          onsubmit={(e) => {
            e.preventDefault();
            void task(async () => {
              if (!activePane?.repo) throw new Error('Select a repository terminal first.');
              await rpc(activePane.hostId, 'worktree', {
                path: activePane.repo,
                branch,
                destination,
                identity: activePane.identity,
              });
              await refresh();
              modal = 'repos';
            });
          }}
        >
          <label>Branch<input bind:value={branch} required /></label><label
            >Worktree directory<input bind:value={destination} required /></label
          >
          <p class="hint">Uses scripts/new-worktree.sh when the repository provides it.</p>
          <button class="primary" disabled={busy}>Create worktree</button>
        </form>{/if}
    </div>
  </div>{/if}

{#if updatePrompt && updateCanPresent}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="DazeDev updates"
      tabindex="-1"
      use:focusDialog
    >
      <h2>
        {availableUpdate
          ? `DazeDev ${releaseVersion(availableUpdate.version)} is available`
          : 'DazeDev updates'}
      </h2>
      <p class="hint">
        Installed: {releaseVersion(clientInfo.version)}.{availableUpdate
          ? ` Download: ${(availableUpdate.size / 1000000).toFixed(1)} MB.`
          : ''}
      </p>
      <button disabled={updateState === 'installing'} onclick={() => showChangelog('')}
        >Changelog</button
      >
      {#if availableUpdate}<p class="hint">
          The app restarts after updating. Your settings, models and remote tmux sessions are
          preserved.
        </p>{/if}
      {#if !desktop || !clientInfo.updateSupported}<p class="hint">
          Download updates manually for this platform.
        </p>{/if}
      <p role="status">
        {updateStatus || updateLabel}{updateState === 'installing' ? ` ${updateProgress}%` : ''}
      </p>
      <div class="form-row">
        <button disabled={updateState === 'installing'} onclick={closeUpdates}
          >{availableUpdate ? 'Later' : 'Close'}</button
        >
        {#if desktop && clientInfo.updateSupported}<button
            disabled={updateBusy}
            onclick={() => void checkUpdates()}>Check again</button
          >{/if}
        {#if availableUpdate}<button
            class="primary"
            disabled={updateBusy || voiceStatus !== 'idle' || busy}
            onclick={() => void installUpdate()}>Download &amp; restart</button
          >{/if}
      </div>
    </div>
  </div>
{/if}

{#if paneMenu}
  <div class="pane-menu-dismiss" role="presentation" onpointerdown={() => (paneMenu = null)}></div>
  <div
    class="pane-menu"
    role="dialog"
    aria-label="Terminal actions"
    tabindex="-1"
    use:focusDialog
    style={`left:${paneMenu.x}px;top:${paneMenu.y}px;max-height:calc(100vh - ${paneMenu.y + 8}px)`}
  >
    <TerminalActionMenu
      items={terminalMenuItems}
      order={device.paneMenuOrder}
      onorder={(order) => {
        device.paneMenuOrder = order;
        persistDevice();
      }}
      onsplit={(edge) => addTerminal(edge, paneMenu!.pane)}
      transfers={workspaces.map((w) => ({
        id: w.id,
        label: `${w.hostId === HOSTLESS ? 'Hostless' : device.hosts.find((h) => h.id === w.hostId)?.name} / ${w.name}`,
      }))}
      transferDisabled={dictationActive}
      ontransfer={(id, duplicate) => {
        const destination = workspaces.find((w) => w.id === id);
        if (destination) menuPaneDrop(paneMenu!.pane, destination, duplicate);
      }}
    />
  </div>
{/if}{#if dictionarySelection !== null}
  <div class="modal-backdrop" role="presentation">
    <div
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Add dictation phrase"
      tabindex="-1"
      use:focusDialog
    >
      <button onclick={() => (dictionarySelection = null)}>Close</button>
      <DictionarySettings
        hosts={device.hosts.filter((h) => h.kind === 'ssh' || h.kind === 'tailscale')}
        selection={dictionarySelection}
        ondone={() => (dictionarySelection = null)}
      />
    </div>
  </div>
{/if}
