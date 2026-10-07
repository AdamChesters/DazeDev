/** Multi-value registers keep concurrent edits/deletions until explicitly resolved. */
export type DictionaryVersion = {
  clock: Record<string, number>;
  preferred: string;
  aliases: string[];
  deleted: boolean;
  disabled?: boolean;
};
export type Dictionary = { schema: 1; entries: Record<string, DictionaryVersion[]> };
export const emptyDictionary = (): Dictionary => ({ schema: 1, entries: {} });
const plain = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v);
export function parseDictionary(value: unknown): Dictionary {
  if (
    !plain(value) ||
    value.schema !== 1 ||
    !plain(value.entries) ||
    Object.keys(value.entries).length > 2000
  )
    throw Error('Invalid dictionary: expected version 1 with up to 2,000 entries.');
  for (const [id, versions] of Object.entries(value.entries)) {
    if (
      !/^[\w-]{1,80}$/.test(id) ||
      ['__proto__', 'constructor', 'prototype'].includes(id) ||
      !Array.isArray(versions) ||
      !versions.length ||
      versions.length > 30
    )
      throw Error('Invalid dictionary entry.');
    for (const v of versions) {
      if (
        !plain(v) ||
        !plain(v.clock) ||
        !Object.keys(v.clock).length ||
        Object.keys(v.clock).length > 100 ||
        Object.entries(v.clock).some(
          ([k, n]) =>
            !/^[\w-]{1,80}$/.test(k) ||
            ['__proto__', 'constructor', 'prototype'].includes(k) ||
            !Number.isSafeInteger(n) ||
            Number(n) < 1,
        ) ||
        typeof v.deleted !== 'boolean' ||
        (v.disabled !== undefined && typeof v.disabled !== 'boolean')
      )
        throw Error('Invalid dictionary revision.');
      validatePhrase(v.preferred);
      if (!Array.isArray(v.aliases) || !v.aliases.length || v.aliases.length > 30)
        throw Error('Add between 1 and 30 heard phrases.');
      v.aliases.forEach(validatePhrase);
    }
  }
  return structuredClone(value) as Dictionary;
}
export function validatePhrase(v: unknown): asserts v is string {
  if (typeof v !== 'string' || !v.trim() || v.length > 160 || /[\x00-\x1f\x7f]/.test(v))
    throw Error('Use a phrase of 1–160 characters without line breaks or control characters.');
}
function dominates(a: DictionaryVersion, b: DictionaryVersion) {
  return (
    Object.entries(b.clock).every(([k, n]) => (a.clock[k] || 0) >= n) &&
    Object.entries(a.clock).some(([k, n]) => n > (b.clock[k] || 0))
  );
}
const key = (v: DictionaryVersion) =>
  JSON.stringify([
    Object.entries(v.clock).sort(),
    v.preferred,
    [...v.aliases].sort(),
    v.deleted,
    !!v.disabled,
  ]);
export function mergeDictionary(a: Dictionary, b: Dictionary): Dictionary {
  const merged = emptyDictionary();
  for (const id of new Set([...Object.keys(a.entries), ...Object.keys(b.entries)])) {
    const versions = [
      ...new Map(
        [...(a.entries[id] || []), ...(b.entries[id] || [])].map((v) => [key(v), v]),
      ).values(),
    ];
    merged.entries[id] = versions
      .filter((v) => !versions.some((other) => dominates(other, v)))
      .sort((x, y) => key(x).localeCompare(key(y)));
  }
  return parseDictionary(merged);
}
export function editDictionary(
  d: Dictionary,
  deviceId: string,
  id: string,
  preferred: string,
  aliases: string[],
  deleted = false,
  disabled = false,
): Dictionary {
  const next = structuredClone(d),
    clock: Record<string, number> = {};
  for (const v of next.entries[id] || [])
    for (const [k, n] of Object.entries(v.clock)) clock[k] = Math.max(clock[k] || 0, n);
  clock[deviceId] = (clock[deviceId] || 0) + 1;
  next.entries[id] = [
    {
      clock,
      preferred: preferred.trim(),
      aliases: [...new Set(aliases.map((a) => a.trim()).filter(Boolean))],
      deleted,
      disabled,
    },
  ];
  return parseDictionary(next);
}
export function dictionaryConflicts(d: Dictionary) {
  return Object.values(d.entries).filter((v) => v.length > 1).length;
}
export function ambiguousAliases(d: Dictionary): string[] {
  const targets = new Map<string, Set<string>>();
  for (const versions of Object.values(d.entries)) {
    if (versions.length !== 1 || versions[0].deleted || versions[0].disabled) continue;
    const version = versions[0];
    for (const phrase of [...version.aliases, version.preferred]) {
      const normalized = phrase.toLocaleLowerCase().replace(/\s+/g, ' ');
      const values = targets.get(normalized) || new Set<string>();
      values.add(version.preferred);
      targets.set(normalized, values);
    }
  }
  return [...targets]
    .filter(([, values]) => values.size > 1)
    .map(([phrase]) => phrase)
    .sort();
}
export function applyDictionary(text: string, d: Dictionary, matched: string[] = []): string {
  const choices = new Map<string, Set<string>>();
  for (const versions of Object.values(d.entries)) {
    if (versions.length !== 1 || versions[0].deleted || versions[0].disabled) continue;
    const v = versions[0];
    for (const alias of [...v.aliases, v.preferred]) {
      const normalized = alias.toLocaleLowerCase().replace(/\s+/g, ' ');
      const values = choices.get(normalized) || new Set<string>();
      values.add(v.preferred);
      choices.set(normalized, values);
    }
  }
  const aliases = [...choices]
    .filter(([, v]) => v.size === 1)
    .map(([a]) => a)
    .sort((a, b) => b.length - a.length);
  if (!aliases.length) return text;
  const escaped = aliases.map((a) =>
    a
      .split(' ')
      .map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
      .join('\\s+'),
  );
  // One pass: never cascade replacements, match only whole phrases, leave URLs/paths/code tokens alone.
  const pattern = new RegExp(
    `(?<![\\p{L}\\p{N}_/\\\\@.-])(?:${escaped.join('|')})(?![\\p{L}\\p{N}_/\\\\@-]|\\.[\\p{L}\\p{N}_])`,
    'giu',
  );
  return text.replace(pattern, (match) => {
    const preferred = [...choices.get(match.toLocaleLowerCase().replace(/\s+/g, ' '))!][0];
    matched.push(preferred);
    return preferred;
  });
}

/** Cleanup must not erase a known dictated phrase. Fall back to the corrected raw transcript. */
export function applyFinalDictionary(raw: string, cleaned: string, d: Dictionary): string {
  const before: string[] = [],
    after: string[] = [];
  const original = applyDictionary(raw, d, before),
    result = applyDictionary(cleaned, d, after);
  return before.some(
    (term) => before.filter((t) => t === term).length > after.filter((t) => t === term).length,
  )
    ? original
    : result;
}

export function entryRevision(d: Dictionary, id: string): string {
  return JSON.stringify((d.entries[id] || []).map(key).sort());
}
