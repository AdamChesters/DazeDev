// Runtime checks for IPC results that become app state, and typed wrappers
// that apply them. A malformed result fails with a clear message instead of
// flowing on as an unchecked cast. The checks cover what the app relies on
// (types, arrays it iterates, ids it matches), not every optional field.
import { call, rpc } from './bridge';
import type { UpdateInfo } from './state/updates.svelte';
import type { Device, Inventory, ModelInfo, Profile, Repo, Session } from './types';

export type DeviceFile = Device & { recoveryNotice?: string; recovery?: Record<string, Profile> };

type Check<T> = (value: unknown, path: string) => T;
type Fields = Record<string, unknown>;

class ShapeError extends Error {}

function need(ok: boolean, path: string, wanted: string) {
  if (!ok) throw new ShapeError(`${path} should be ${wanted}`);
}
function object(value: unknown, path: string): Fields {
  need(!!value && typeof value === 'object' && !Array.isArray(value), path, 'an object');
  return value as Fields;
}
function list<T>(value: unknown, path: string, item: Check<T>): T[] {
  need(Array.isArray(value), path, 'a list');
  (value as unknown[]).forEach((entry, index) => item(entry, `${path}[${index}]`));
  return value as T[];
}
function record<T>(value: unknown, path: string, item: Check<T>): Record<string, T> {
  const fields = object(value, path);
  for (const [key, entry] of Object.entries(fields)) item(entry, `${path}.${key}`);
  return fields as Record<string, T>;
}
/** Checks required field types, and optional ones when they are present. */
function fields(
  value: unknown,
  path: string,
  required: Record<string, string>,
  optional: Record<string, string> = {},
) {
  const data = object(value, path);
  for (const [key, type] of Object.entries({ ...optional, ...required })) {
    const field = data[key];
    if (!(key in required) && (field === undefined || field === null)) continue;
    need(typeof field === type, `${path}.${key}`, `a ${type}`);
  }
  return data;
}
const strings: Check<string> = (value, path) => {
  need(typeof value === 'string', path, 'a string');
  return value as string;
};

const checkLayout: Check<unknown> = (value, path) => {
  const layout = fields(value, path, { kind: 'string', id: 'string' });
  if (layout.kind === 'split') {
    checkLayout(layout.first, `${path}.first`);
    checkLayout(layout.second, `${path}.second`);
  } else need(layout.kind === 'pane', `${path}.kind`, '"pane" or "split"');
  return layout;
};
const checkPane: Check<unknown> = (value, path) =>
  fields(
    value,
    path,
    { id: 'string', hostId: 'string' },
    { sessionId: 'string', name: 'string', agent: 'string' },
  );
const checkWorkspace: Check<unknown> = (value, path) => {
  const workspace = fields(value, path, { id: 'string' }, { name: 'string', focused: 'string' });
  list(workspace.panes, `${path}.panes`, checkPane);
  checkLayout(workspace.layout, `${path}.layout`);
  return workspace;
};
export const checkProfile: Check<Profile> = (value, path = 'profile') => {
  const profile = fields(value, path, { revision: 'number' });
  list(profile.workspaces, `${path}.workspaces`, checkWorkspace);
  const preferences = object(profile.preferences, `${path}.preferences`);
  if (preferences.roots !== undefined)
    list(preferences.roots, `${path}.preferences.roots`, strings);
  return profile as unknown as Profile;
};
const checkHost: Check<unknown> = (value, path) =>
  fields(value, path, { id: 'string', name: 'string', kind: 'string' });
const checkSession: Check<Session> = (value, path) =>
  fields(value, path, { id: 'string', name: 'string' }) as unknown as Session;
const checkRepo: Check<Repo> = (value, path) =>
  fields(value, path, { path: 'string' }) as unknown as Repo;

/** device_load: missing fields are filled from defaults, so most are optional. */
export const checkDeviceFile: Check<DeviceFile> = (value, path = 'device') => {
  const device = fields(
    value,
    path,
    {},
    { activeHost: 'string', activeWorkspace: 'string', recoveryNotice: 'string' },
  );
  if (device.hosts !== undefined) list(device.hosts, `${path}.hosts`, checkHost);
  for (const key of ['voice', 'terminal'])
    if (device[key] !== undefined) object(device[key], `${path}.${key}`);
  if (device.localWorkspaces !== undefined)
    checkProfile(device.localWorkspaces, `${path}.localWorkspaces`);
  if (device.recovery !== undefined) record(device.recovery, `${path}.recovery`, checkProfile);
  return device as unknown as DeviceFile;
};
export const checkUpdate: Check<UpdateInfo | null> = (value, path = 'update') =>
  value === null
    ? null
    : (fields(value, path, {
        version: 'string',
        notes: 'string',
        size: 'number',
      }) as unknown as UpdateInfo);
export const checkModels: Check<ModelInfo[]> = (value, path = 'models') =>
  list(value, path, (model, at) =>
    fields(model, at, { id: 'string', name: 'string', size: 'number', installed: 'boolean' }),
  ) as unknown as ModelInfo[];
export const checkSessions: Check<Session[]> = (value, path = 'sessions') =>
  list(value, path, checkSession);
export const checkInventory: Check<Inventory> = (value, path = 'inventory') => {
  const inventory = fields(value, path, { user: 'string' });
  list(inventory.agents, `${path}.agents`, (agent, at) => fields(agent, at, { id: 'string' }));
  list(inventory.repos, `${path}.repos`, checkRepo);
  list(inventory.sessions, `${path}.sessions`, checkSession);
  return inventory as unknown as Inventory;
};
export const checkRepositories: Check<{ repos: Repo[]; warnings: string[] }> = (
  value,
  path = 'result',
) => {
  const result = object(value, path);
  list(result.repos, `${path}.repos`, checkRepo);
  list(result.warnings, `${path}.warnings`, strings);
  return result as { repos: Repo[]; warnings: string[] };
};

/** Applies `check` to an IPC result; a mismatch names the command and the bad field. */
export function checked<T>(name: string, check: Check<T>, value: unknown): T {
  try {
    return check(value, 'result');
  } catch (e) {
    if (!(e instanceof ShapeError)) throw e;
    throw new Error(`The ${name} reply was malformed: ${e.message}.`, { cause: e });
  }
}

export const loadDevice = async () =>
  checked('device_load', checkDeviceFile, await call<unknown>('device_load'));
export const checkForUpdate = async () =>
  checked('update_check', checkUpdate, await call<unknown>('update_check'));
export const loadProfile = async (hostId: string) =>
  checked('profile_load', checkProfile, await rpc<unknown>(hostId, 'profile_load'));
export const listSessions = async <T extends Session = Session>(hostId: string) =>
  checked('sessions', checkSessions, await rpc<unknown>(hostId, 'sessions')) as T[];
export const loadInventory = async (hostId: string) =>
  checked('inventory', checkInventory, await rpc<unknown>(hostId, 'inventory'));
export const listRepositories = async (hostId: string) =>
  checked('repositories', checkRepositories, await rpc<unknown>(hostId, 'repositories'));
