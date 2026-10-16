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
// The dictation dictionary on this device, and its sync with one host.
export class DictionaryStore {
  state = $state.raw<DictionaryState>(fresh());
  status = $state('Stored on this device.');
  /** A host's saved dictionary waiting for the user to adopt it. */
  adoption = $state.raw<Dictionary | null>(null);
  #initialized = false;
  #ready = false;
  #serial = Promise.resolve();
  #syncing = false;

  #queue(work: () => Promise<void>) {
    const next = this.#serial.then(work);
    this.#serial = next.catch(() => {});
    return next;
  }

  async #persist(state: DictionaryState) {
    if (!this.#ready) throw Error('Dictionary has not loaded. Original files are preserved.');
    if (JSON.stringify(state) === JSON.stringify(this.state)) return;
    if (desktop) await call('dictionary_save', { value: state });
    else {
      const old = localStorage.getItem('dazedev-dictionary');
      if (old) localStorage.setItem('dazedev-dictionary-backup', old);
      localStorage.setItem('dazedev-dictionary', JSON.stringify(state));
    }
    this.state = state;
  }

  load = (retry = false) =>
    this.#queue(async () => {
      if (this.#initialized && !retry) return;
      this.#initialized = true;
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
          this.state = { ...raw, dictionary: parseDictionary(raw.dictionary) };
          if (raw.recoveryNotice) this.status = raw.recoveryNotice;
        }
        this.#ready = true;
      } catch (e) {
        this.#initialized = false;
        this.status = `Dictionary could not load: ${String(e)}`;
        throw e;
      }
    });

  saveEntry = (
    id: string,
    preferred: string,
    aliases: string[],
    deleted = false,
    disabled = false,
    expected?: string,
  ) =>
    this.#queue(async () => {
      const state = this.state;
      if (expected !== undefined && entryRevision(state.dictionary, id) !== expected)
        throw Error(
          'This entry changed on another device while you were editing. Your draft is kept. Select Edit again to review the new version before saving.',
        );
      await this.#persist({
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
      this.status = 'Saved locally.';
    });

  import = (value: unknown) => {
    const incoming = parseDictionary(value);
    return this.#queue(async () => {
      const state = this.state;
      const wasReady = this.#ready;
      this.#ready = true; // Explicit JSON import is also the recovery route after unreadable files.
      try {
        await this.#persist({ ...state, dictionary: mergeDictionary(state.dictionary, incoming) });
      } catch (e) {
        this.#ready = wasReady;
        throw e;
      }
      this.status = 'Imported and merged. Review any conflicts below.';
    });
  };

  setHost = (host: string) =>
    this.#queue(async () => {
      await this.#persist({ ...this.state, syncHost: host, adopted: false });
      this.adoption = null;
      this.status = host
        ? 'Check this host to review its saved dictionary.'
        : 'Sync off. Your local dictionary is retained.';
    });

  sync = async (adopt = false) => {
    if (this.#syncing || !this.#ready) return;
    this.#syncing = true;
    try {
      await this.#queue(async () => {
        let state = this.state;
        if (!state.syncHost) return;
        const remote = await rpc<{ dictionary: Dictionary | null; revision: string }>(
          state.syncHost,
          'dictionary_read',
        );
        const incoming = remote.dictionary ? parseDictionary(remote.dictionary) : emptyDictionary();
        if (!state.adopted && !adopt) {
          this.adoption = incoming;
          this.status = 'Review the saved dictionary, then enable sync once for this device.';
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
          await this.#persist(state);
          if (JSON.stringify(merged) === JSON.stringify(snapshot.dictionary)) {
            this.adoption = null;
            this.status = 'Up to date. Offline changes will merge when this host reconnects.';
            return;
          }
          const result = await rpc<{ saved: boolean }>(state.syncHost, 'dictionary_write', {
            dictionary: merged,
            revision: snapshot.revision,
          });
          if (result.saved) {
            this.adoption = null;
            this.status = 'Synced. Offline changes will merge when this host reconnects.';
            return;
          }
        }
        throw Error('The host dictionary changed during sync. Retry shortly.');
      });
    } catch (e) {
      this.status = `Local copy retained. Sync unavailable: ${String(e)}`;
    } finally {
      this.#syncing = false;
    }
  };

  /** Loads the dictionary and syncs an adopted host every minute; returns a stop function. */
  start = () => {
    void this.load().catch(() => {});
    const timer = setInterval(() => {
      if (this.state.adopted && this.state.syncHost) void this.sync();
    }, 60000);
    return () => clearInterval(timer);
  };
}

export const dictionaryStore = new DictionaryStore();
