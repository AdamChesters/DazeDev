import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = join(__dirname, '..');
const schema = JSON.parse(readFileSync(join(root, '../../shared/protocol/host-v2.json'), 'utf8'));
// Handled by the desktop app itself, not the host helper.
const desktopOnly = new Set(['native_close']);

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|svelte)$/.test(name) ? [path] : [];
  });
}

describe('host protocol schema', () => {
  it('lists every host method the frontend calls', () => {
    const called = new Set<string>();
    for (const file of sources(join(root, 'src'))) {
      for (const match of readFileSync(file, 'utf8').matchAll(/rpc(?:<[^>]*>)?\([^,()]+,\s*'([a-z_]+)'/g))
        called.add(match[1]);
    }
    expect(called.size).toBeGreaterThan(10);
    const unknown = [...called].filter((name) => !schema.methods[name] && !desktopOnly.has(name));
    expect(unknown).toEqual([]);
  });
});
