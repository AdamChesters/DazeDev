import type { RecordingHighlight } from './highlights';
export interface Host {
  colors?: import('./colors').TabColors;
  id: string;
  name: string;
  kind: 'ssh' | 'tailscale' | 'local' | 'wsl' | 'powershell';
  nativeShell?: 'powershell' | 'pwsh';
  address: string;
  user: string;
  port?: number;
  key: string;
  jump: string;
  distro: string;
  socket: string;
  /** Use the system OpenSSH client instead of the built-in one. */
  systemSsh?: boolean;
  fingerprint?: string;
}
export interface Session {
  agent?: string;
  id: string;
  name: string;
  windows: number;
  attached: number;
  stamp?: string;
}
export interface Repo {
  name: string;
  path: string;
  branch: string;
  dirty: boolean;
  remote: string;
  worktrees: string;
}
export interface Agent {
  id: string;
  path: string | null;
  version: string;
  models: { id: string; name: string; efforts: string[] }[];
  modelFlag: boolean;
  effortFlag: boolean;
  install: string;
}
export interface Inventory {
  warnings?: string[];
  hostname: string;
  user: string;
  machine: string;
  home: string;
  tmux: string | null;
  nativeSessions?: boolean;
  agents: Agent[];
  identities: { login: string; active: boolean }[];
  repos: Repo[];
  sessions: Session[];
}
export interface Pane {
  hostDetails?: Pick<Host, 'name' | 'kind' | 'address' | 'user' | 'port' | 'socket'>;
  hideStatus?: boolean;
  colors?: import('./colors').TabColors;
  id: string;
  hostId: string;
  sessionId: string;
  shell?: boolean;
  directory?: string;
  stamp?: string;
  hostKey?: string;
  name: string;
  repo: string;
  identity: string;
  agent: string;
  model: string;
  effort: string;
  operation: string;
  error?: string;
}
export type Layout =
  | { kind: 'pane'; id: string }
  | {
      kind: 'split';
      id: string;
      axis: 'row' | 'column';
      ratio: number;
      first: Layout;
      second: Layout;
    };
export interface Workspace {
  tabGroups?: import('./terminal-tabs').TerminalTabGroup[];
  colors?: import('./colors').TabColors;
  id: string;
  name: string;
  hostId: string;
  panes: Pane[];
  layout: Layout;
  focused: string;
}
export interface Profile {
  schema: number;
  revision: number;
  preferences: {
    roots: string[];
    guidance: string;
    promptTemplate?: string;
    identities: unknown[];
  };
  workspaces: Workspace[];
  discovery: Record<string, unknown>;
}
export interface Device {
  localWorkspaces?: Profile;
  activeTerminalTabs?: Record<string, string>;
  listenOnboarded?: boolean;
  releaseHistory?: import('./changelog').ReleaseEntry[];
  paneMenuOrder?: string[];
  lastChangelogVersion?: string;
  terminalAlerts?: { visual: boolean; sound: boolean };
  hosts: Host[];
  activeHost: string;
  activeWorkspace: string;
  voice: {
    model: string;
    backend: string;
    cleanup: boolean;
    keepLoaded?: boolean;
    automatic: boolean;
    microphone: string;
  };
  clipboardByHost?: Record<string, import('./terminals').TerminalSettings['clipboard']>;
  clipboardPolicy?: number;
  appShortcuts?: Partial<
    Record<import('./app-shortcuts').AppAction, import('./shortcut').Shortcut>
  >;
  terminal?: import('./terminals').TerminalSettings;
  inputMode?: 'toggle' | 'hold';
  motionPreferenceVersion?: number;
  localFocus?: Record<string, string>;
  profileBackups?: Record<string, Profile[]>;
  highlightMotion?: 'system' | 'animate' | 'static';
  recordingHighlight?: RecordingHighlight;
  recordingHighlightColor?: string;
  inputShortcut?: import('./shortcut').Shortcut;
  commandShortcut?: import('./shortcut').Shortcut;
  theme?: 'blue' | 'charcoal' | 'green';
  sidebar: boolean;
  sidebarWidth?: number;
  checkUpdates?: boolean;
  supportGlow?: boolean;
  feedbackContact?: { name: string; email: string };
  voiceSetupDismissed?: boolean;
  collapsedHosts?: Record<string, boolean>;
}
export interface ModelInfo {
  id: string;
  name: string;
  size: number;
  installed: boolean;
  family: string;
  license: string;
}
export const uid = () => crypto.randomUUID();
export const blankProfile = (): Profile => ({
  schema: 1,
  revision: 0,
  preferences: { roots: [], guidance: '', identities: [] },
  workspaces: [],
  discovery: {},
});
export const defaults = (): Device => ({
  hosts: [],
  motionPreferenceVersion: 1,
  highlightMotion: 'animate',
  supportGlow: true,
  activeHost: '',
  activeWorkspace: '',
  voice: {
    model: 'parakeet',
    backend: 'auto',
    cleanup: true,
    automatic: false,
    microphone: '',
  },
  sidebar: true,
  theme: 'blue',
});
