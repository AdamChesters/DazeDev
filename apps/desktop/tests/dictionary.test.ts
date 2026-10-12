import { describe, it, expect } from 'vitest';
import {
  emptyDictionary,
  editDictionary,
  mergeDictionary,
  applyDictionary,
  parseDictionary,
  dictionaryConflicts,
  ambiguousAliases,
} from '../src/lib/dictionary';
describe('dictation dictionary', () => {
  const base = () =>
    editDictionary(emptyDictionary(), 'a', 'project', 'ExampleProject', [
      'sample project',
      'example project',
    ]);
  it('matches entire phrases, spacing and case without global replacements or cascading', () => {
    const d = base();
    expect(applyDictionary('Example project, sample  project. Road closed.', d)).toBe(
      'ExampleProject, ExampleProject. Road closed.',
    );
    expect(applyDictionary('preexample project and /example project/file', d)).toBe(
      'preexample project and /example project/file',
    );
  });
  it('keeps disjoint offline changes and is commutative/idempotent', () => {
    const a = editDictionary(base(), 'a', 'other', 'DazeDev', ['days dev']);
    const b = editDictionary(base(), 'b', 'third', 'tmux', ['tee mux']);
    expect(mergeDictionary(a, b)).toEqual(mergeDictionary(b, a));
    expect(mergeDictionary(a, a)).toEqual(a);
    expect(Object.keys(mergeDictionary(a, b).entries)).toHaveLength(3);
  });
  it('keeps concurrent edit vs delete visible and disabled until explicit resolution', () => {
    const a = editDictionary(base(), 'a', 'project', 'ExampleProject', ['example project'], true);
    const b = editDictionary(base(), 'b', 'project', 'EXAMPLE', ['example project']);
    const conflict = mergeDictionary(a, b);
    expect(dictionaryConflicts(conflict)).toBe(1);
    expect(applyDictionary('example project', conflict)).toBe('example project');
    const resolved = editDictionary(conflict, 'c', 'project', 'EXAMPLE', ['example project']);
    expect(dictionaryConflicts(mergeDictionary(resolved, a))).toBe(0);
    expect(applyDictionary('example project', resolved)).toBe('EXAMPLE');
  });
  it('tombstones prevent stale offline copy resurrecting deletions', () => {
    const old = base();
    const deleted = editDictionary(
      old,
      'a',
      'project',
      'ExampleProject',
      ['example project'],
      true,
    );
    expect(mergeDictionary(old, deleted)).toEqual(deleted);
  });
  it('does not choose arbitrarily between conflicting alias targets', () => {
    const d = editDictionary(base(), 'b', 'duplicate', 'Other', ['example project']);
    expect(applyDictionary('example project', d)).toBe('example project');
    expect(ambiguousAliases(d)).toEqual(['example project']);
  });
  it('rejects malformed/oversized/untrusted imports', () => {
    expect(() => parseDictionary({ schema: 2, entries: {} })).toThrow();
    expect(() => editDictionary(base(), 'a', 'project', 'X', ['bad\nphrase'])).toThrow();
    const polluted = JSON.parse('{"schema":1,"entries":{"__proto__":[]}}');
    expect(() => parseDictionary(polluted)).toThrow();
  });
});
import { applyFinalDictionary } from '../src/lib/dictionary';
it('falls back to corrected raw speech when cleanup removed a dictionary phrase', () => {
  const d = editDictionary(emptyDictionary(), 'a', 'project', 'ExampleProject', [
    'example project',
  ]);
  expect(applyFinalDictionary('um example project uses Rust', 'Uses Rust.', d)).toBe(
    'um ExampleProject uses Rust',
  );
  expect(
    applyFinalDictionary('um example project uses Rust', 'Example project uses Rust.', d),
  ).toBe('ExampleProject uses Rust.');
  const disabled = editDictionary(
    d,
    'a',
    'project',
    'ExampleProject',
    ['example project'],
    false,
    true,
  );
  expect(applyDictionary('example project', disabled)).toBe('example project');
});
