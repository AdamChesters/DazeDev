import { it, expect, vi, beforeEach } from 'vitest';
import { get } from 'svelte/store';
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
  const m = await import('../src/lib/dictionary-store');
  await m.loadDictionary();
  await m.saveEntry('local', 'DazeDev', ['days dev']);
  await m.setDictionaryHost('host');
  const remote = editDictionary(emptyDictionary(), 'remote', 'project', 'ExampleProject', [
    'example project',
  ]);
  io.rpc.mockImplementation(async (_h, method) =>
    method === 'dictionary_read' ? { dictionary: remote, revision: 'one' } : { saved: true },
  );
  await m.syncDictionary();
  expect(get(m.dictionaryAdoption)).toEqual(remote);
  expect(io.rpc.mock.calls.every((c) => c[1] === 'dictionary_read')).toBe(true);
  let saves = 0;
  io.rpc.mockImplementation(async (_h, method) =>
    method === 'dictionary_read'
      ? { dictionary: remote, revision: String(saves) }
      : { saved: ++saves > 1 },
  );
  await m.syncDictionary(true);
  expect(saves).toBe(2);
  expect(Object.keys(get(m.dictionaryState).dictionary.entries).sort()).toEqual([
    'local',
    'project',
  ]);
  expect(get(m.dictionaryState).adopted).toBe(true);
});
it('retains local edits and reports an unavailable host without writing remotely', async () => {
  io.call.mockResolvedValue(null);
  const m = await import('../src/lib/dictionary-store');
  await m.loadDictionary();
  await m.saveEntry('local', 'DazeDev', ['days dev']);
  await m.setDictionaryHost('host');
  io.rpc.mockRejectedValue(Error('offline'));
  await m.syncDictionary(true);
  expect(get(m.dictionaryState).dictionary.entries.local[0].preferred).toBe('DazeDev');
  expect(get(m.dictionaryStatus)).toContain('Local copy retained');
});
it('does not replace local state when disk persistence fails', async () => {
  io.call.mockResolvedValue(null);
  const m = await import('../src/lib/dictionary-store');
  await m.loadDictionary();
  io.call.mockRejectedValue(Error('disk full'));
  await expect(m.saveEntry('local', 'DazeDev', ['days dev'])).rejects.toThrow('disk full');
  expect(Object.keys(get(m.dictionaryState).dictionary.entries)).toHaveLength(0);
});
it('can recover from unreadable local files by explicit import', async () => {
  io.call.mockRejectedValueOnce(Error('corrupt'));
  const m = await import('../src/lib/dictionary-store');
  await expect(m.loadDictionary()).rejects.toThrow();
  io.call.mockResolvedValue(null);
  await m.importDictionary(
    editDictionary(emptyDictionary(), 'other', 'saved', 'DazeDev', ['days dev']),
  );
  expect(get(m.dictionaryState).dictionary.entries.saved[0].preferred).toBe('DazeDev');
});
it('rejects a stale edit rather than overwriting a newly synced revision', async () => {
  io.call.mockResolvedValue(null);
  const m = await import('../src/lib/dictionary-store');
  await m.loadDictionary();
  await m.saveEntry('entry', 'Old', ['old']);
  const { entryRevision } = await import('../src/lib/dictionary');
  const seen = entryRevision(get(m.dictionaryState).dictionary, 'entry');
  await m.saveEntry('entry', 'New', ['new']);
  await expect(m.saveEntry('entry', 'Stale', ['old'], false, false, seen)).rejects.toThrow(
    'changed on another device',
  );
  expect(get(m.dictionaryState).dictionary.entries.entry[0].preferred).toBe('New');
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
  const m = await import('../src/lib/dictionary-store');
  const loading = m.loadDictionary();
  await Promise.resolve();
  const imported = m.importDictionary(
    editDictionary(emptyDictionary(), 'other', 'imported', 'New', ['new']),
  );
  resolve({
    schema: 1,
    deviceId: 'local',
    dictionary: editDictionary(emptyDictionary(), 'old', 'existing', 'Old', ['old']),
    syncHost: '',
    adopted: false,
  });
  await Promise.all([loading, imported]);
  expect(Object.keys(get(m.dictionaryState).dictionary.entries).sort()).toEqual([
    'existing',
    'imported',
  ]);
});
it('polls an unchanged adopted host without rewriting either copy', async () => {
  io.call.mockResolvedValue(null);
  const m = await import('../src/lib/dictionary-store');
  await m.loadDictionary();
  await m.saveEntry('entry', 'DazeDev', ['days dev']);
  await m.setDictionaryHost('host');
  const dictionary = get(m.dictionaryState).dictionary;
  io.rpc.mockResolvedValue({ dictionary, revision: 'unchanged' });
  await m.syncDictionary(true);
  io.call.mockClear();
  io.rpc.mockClear();
  await m.syncDictionary();
  expect(io.call).not.toHaveBeenCalled();
  expect(io.rpc.mock.calls.map((args) => args[1])).toEqual(['dictionary_read']);
});
