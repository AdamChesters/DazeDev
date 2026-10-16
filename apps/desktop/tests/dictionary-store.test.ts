import { it, expect, vi, beforeEach } from 'vitest';
import { editDictionary, emptyDictionary } from '../src/lib/dictionary';
const io = vi.hoisted(() => ({ call: vi.fn(), rpc: vi.fn() }));
vi.mock('../src/lib/bridge', () => ({ desktop: true, call: io.call, rpc: io.rpc }));
beforeEach(() => {
  vi.resetModules();
  io.call.mockReset();
  io.rpc.mockReset();
});
it('requires explicit adoption, merges offline entries and retries a racing host', async () => {
  io.call.mockResolvedValue(null);
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  await m.load();
  await m.saveEntry('local', 'DazeDev', ['days dev']);
  await m.setHost('host');
  const remote = editDictionary(emptyDictionary(), 'remote', 'project', 'ExampleProject', [
    'example project',
  ]);
  io.rpc.mockImplementation(async (_h, method) =>
    method === 'dictionary_read' ? { dictionary: remote, revision: 'one' } : { saved: true },
  );
  await m.sync();
  expect(m.adoption).toEqual(remote);
  expect(io.rpc.mock.calls.every((c) => c[1] === 'dictionary_read')).toBe(true);
  let saves = 0;
  io.rpc.mockImplementation(async (_h, method) =>
    method === 'dictionary_read'
      ? { dictionary: remote, revision: String(saves) }
      : { saved: ++saves > 1 },
  );
  await m.sync(true);
  expect(saves).toBe(2);
  expect(Object.keys(m.state.dictionary.entries).sort()).toEqual(['local', 'project']);
  expect(m.state.adopted).toBe(true);
});
it('retains local edits and reports an unavailable host without writing remotely', async () => {
  io.call.mockResolvedValue(null);
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  await m.load();
  await m.saveEntry('local', 'DazeDev', ['days dev']);
  await m.setHost('host');
  io.rpc.mockRejectedValue(Error('offline'));
  await m.sync(true);
  expect(m.state.dictionary.entries.local[0].preferred).toBe('DazeDev');
  expect(m.status).toContain('Local copy retained');
});
it('does not replace local state when disk persistence fails', async () => {
  io.call.mockResolvedValue(null);
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  await m.load();
  io.call.mockRejectedValue(Error('disk full'));
  await expect(m.saveEntry('local', 'DazeDev', ['days dev'])).rejects.toThrow('disk full');
  expect(Object.keys(m.state.dictionary.entries)).toHaveLength(0);
});
it('can recover from unreadable local files by explicit import', async () => {
  io.call.mockRejectedValueOnce(Error('corrupt'));
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  await expect(m.load()).rejects.toThrow();
  io.call.mockResolvedValue(null);
  await m.import(editDictionary(emptyDictionary(), 'other', 'saved', 'DazeDev', ['days dev']));
  expect(m.state.dictionary.entries.saved[0].preferred).toBe('DazeDev');
});
it('rejects a stale edit rather than overwriting a newly synced revision', async () => {
  io.call.mockResolvedValue(null);
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  await m.load();
  await m.saveEntry('entry', 'Old', ['old']);
  const { entryRevision } = await import('../src/lib/dictionary');
  const seen = entryRevision(m.state.dictionary, 'entry');
  await m.saveEntry('entry', 'New', ['new']);
  await expect(m.saveEntry('entry', 'Stale', ['old'], false, false, seen)).rejects.toThrow(
    'changed on another device',
  );
  expect(m.state.dictionary.entries.entry[0].preferred).toBe('New');
});
it('serializes import after the pending initial load', async () => {
  let resolve!: (value: unknown) => void;
  io.call.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  io.call.mockResolvedValue(null);
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  const loading = m.load();
  await Promise.resolve();
  const imported = m.import(editDictionary(emptyDictionary(), 'other', 'imported', 'New', ['new']));
  resolve({
    schema: 1,
    deviceId: 'local',
    dictionary: editDictionary(emptyDictionary(), 'old', 'existing', 'Old', ['old']),
    syncHost: '',
    adopted: false,
  });
  await Promise.all([loading, imported]);
  expect(Object.keys(m.state.dictionary.entries).sort()).toEqual(['existing', 'imported']);
});
it('polls an unchanged adopted host without rewriting either copy', async () => {
  io.call.mockResolvedValue(null);
  const m = (await import('../src/lib/dictionary-store.svelte')).dictionaryStore;
  await m.load();
  await m.saveEntry('entry', 'DazeDev', ['days dev']);
  await m.setHost('host');
  const dictionary = m.state.dictionary;
  io.rpc.mockResolvedValue({ dictionary, revision: 'unchanged' });
  await m.sync(true);
  io.call.mockClear();
  io.rpc.mockClear();
  await m.sync();
  expect(io.call).not.toHaveBeenCalled();
  expect(io.rpc.mock.calls.map((args) => args[1])).toEqual(['dictionary_read']);
});
