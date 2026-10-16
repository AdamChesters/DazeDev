import { writable, get } from 'svelte/store';
import { call, desktop, rpc } from './bridge';
import {
  emptyDictionary,
  parseDictionary,
  mergeDictionary,
  editDictionary,
  entryRevision,
  type Dictionary,
} from './dictionary';
export type DictionaryState = {
  schema: 1;
  deviceId: string;
  dictionary: Dictionary;
  syncHost: string;
  adopted: boolean;
};
const fresh = (): DictionaryState => ({
  schema: 1,
  deviceId: crypto.randomUUID(),
  dictionary: emptyDictionary(),
  syncHost: '',
  adopted: false,
});
export const dictionaryState = writable<DictionaryState>(fresh());
export const dictionaryStatus = writable('Stored on this device.');
export const dictionaryAdoption = writable<Dictionary | null>(null);
let initialized = false,
  ready = false,
  serial = Promise.resolve(),
  syncing = false;
function queue(work: () => Promise<void>) {
  const next = serial.then(work);
  serial = next.catch(() => {});
  return next;
}
async function persist(state: DictionaryState) {
  if (!ready) throw Error('Dictionary has not loaded. Original files are preserved.');
  if (JSON.stringify(state) === JSON.stringify(get(dictionaryState))) return;
  if (desktop) await call('dictionary_save', { value: state });
  else {
    const old = localStorage.getItem('dazedev-dictionary');
    if (old) localStorage.setItem('dazedev-dictionary-backup', old);
    localStorage.setItem('dazedev-dictionary', JSON.stringify(state));
  }
  dictionaryState.set(state);
}
export function loadDictionary(retry = false) {
  return queue(async () => {
    if (initialized && !retry) return;
    initialized = true;
    try {
      const raw = desktop
        ? await call<unknown>('dictionary_load')
        : JSON.parse(localStorage.getItem('dazedev-dictionary') || 'null');
      if (raw) {
        if (
          raw.schema !== 1 ||
          typeof raw.deviceId !== 'string' ||
          !/^[\w-]{1,80}$/.test(raw.deviceId) ||
          typeof raw.syncHost !== 'string' ||
          typeof raw.adopted !== 'boolean'
        )
          throw Error('Invalid dictionary settings.');
        dictionaryState.set({ ...raw, dictionary: parseDictionary(raw.dictionary) });
        if (raw.recoveryNotice) dictionaryStatus.set(raw.recoveryNotice);
      }
      ready = true;
    } catch (e) {
      initialized = false;
      dictionaryStatus.set(`Dictionary could not load: ${String(e)}`);
      throw e;
    }
  });
}
export function saveEntry(
  id: string,
  preferred: string,
  aliases: string[],
  deleted = false,
  disabled = false,
  expected?: string,
) {
  return queue(async () => {
    const state = get(dictionaryState);
    if (expected !== undefined && entryRevision(state.dictionary, id) !== expected)
      throw Error(
        'This entry changed on another device while you were editing. Your draft is kept. Select Edit again to review the new version before saving.',
      );
    await persist({
      ...state,
      dictionary: editDictionary(
        state.dictionary,
        state.deviceId,
        id,
        preferred,
        aliases,
        deleted,
        disabled,
      ),
    });
    dictionaryStatus.set('Saved locally.');
  });
}
export function importDictionary(value: unknown) {
  const incoming = parseDictionary(value);
  return queue(async () => {
    const state = get(dictionaryState);
    const wasReady = ready;
    ready = true; // Explicit JSON import is also the recovery route after unreadable files.
    try {
      await persist({ ...state, dictionary: mergeDictionary(state.dictionary, incoming) });
    } catch (e) {
      ready = wasReady;
      throw e;
    }
    dictionaryStatus.set('Imported and merged. Review any conflicts below.');
  });
}
export function setDictionaryHost(host: string) {
  return queue(async () => {
    const state = get(dictionaryState);
    await persist({ ...state, syncHost: host, adopted: false });
    dictionaryAdoption.set(null);
    dictionaryStatus.set(
      host
        ? 'Check this host to review its saved dictionary.'
        : 'Sync off. Your local dictionary is retained.',
    );
  });
}
export async function syncDictionary(adopt = false) {
  if (syncing || !ready) return;
  syncing = true;
  try {
    await queue(async () => {
      let state = get(dictionaryState);
      if (!state.syncHost) return;
      const remote = await rpc<{ dictionary: Dictionary | null; revision: string }>(
        state.syncHost,
        'dictionary_read',
      );
      const incoming = remote.dictionary ? parseDictionary(remote.dictionary) : emptyDictionary();
      if (!state.adopted && !adopt) {
        dictionaryAdoption.set(incoming);
        dictionaryStatus.set('Review the saved dictionary, then enable sync once for this device.');
        return;
      }
      for (let attempt = 0; attempt < 3; attempt++) {
        const snapshot = attempt
          ? await rpc<{ dictionary: Dictionary | null; revision: string }>(
              state.syncHost,
              'dictionary_read',
            )
          : remote;
        const merged = mergeDictionary(
          state.dictionary,
          snapshot.dictionary ? parseDictionary(snapshot.dictionary) : emptyDictionary(),
        );
        // Save the merge locally before attempting the remote CAS. Offline edits always survive.
        state = { ...state, dictionary: merged, adopted: true };
        await persist(state);
        if (JSON.stringify(merged) === JSON.stringify(snapshot.dictionary)) {
          dictionaryAdoption.set(null);
          dictionaryStatus.set('Up to date. Offline changes will merge when this host reconnects.');
          return;
        }
        const result = await rpc<{ saved: boolean }>(state.syncHost, 'dictionary_write', {
          dictionary: merged,
          revision: snapshot.revision,
        });
        if (result.saved) {
          dictionaryAdoption.set(null);
          dictionaryStatus.set('Synced. Offline changes will merge when this host reconnects.');
          return;
        }
      }
      throw Error('The host dictionary changed during sync. Retry shortly.');
    });
  } catch (e) {
    dictionaryStatus.set(`Local copy retained. Sync unavailable: ${String(e)}`);
  } finally {
    syncing = false;
  }
}
export function startDictionarySync() {
  void loadDictionary().catch(() => {});
  const timer = setInterval(() => {
    const s = get(dictionaryState);
    if (s.adopted && s.syncHost) void syncDictionary();
  }, 60000);
  return () => clearInterval(timer);
}
