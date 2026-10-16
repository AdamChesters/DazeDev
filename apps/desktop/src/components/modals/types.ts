// Shapes shared by App.svelte and the modal body components.
export type Task = (fn: () => Promise<void>) => Promise<void>;
export type VoiceMode = 'dictation' | 'command';
export type { UpdateInfo, UpdateState } from '../../lib/state/updates.svelte';
export type ClientInfo = {
  version: string;
  dataPath: string;
  migration: string;
  updateSupported: boolean;
};
export type SessionForm = {
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
export type BrowserRequest = { requestId: string; hostId: string; url: string };
export type GithubRepo = { full_name: string; description: string };
