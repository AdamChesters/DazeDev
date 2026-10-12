import { describe, expect, it } from 'vitest';
import { repositoryGroups, githubOwner, needsChangelog } from '../src/lib/repositories';
import type { Repo } from '../src/lib/types';
const repo = (name: string, remote = ''): Repo => ({
  name,
  remote,
  path: `/dev/${name}`,
  branch: 'main',
  dirty: false,
  worktrees: '',
});
describe('repository picker grouping', () => {
  it('orders owners and repositories alphabetically with local-only repositories last', () => {
    const groups = repositoryGroups([
      repo('Zulu'),
      repo('Zulu', 'git@github.com:Bob/z.git'),
      repo('Beta', 'https://github.com:evil/bad'),
      repo('apple', 'ssh://git@github.com/Alice/apple.git'),
      repo('alpha', 'https://github.com/Bob/a.git'),
      repo('Alpha'),
    ]);
    expect(groups.map((g) => g.label)).toEqual([
      'GitHub / Alice',
      'GitHub / Bob',
      'Other remotes',
      'Local only',
    ]);
    expect(groups[1].repos.map((r) => r.name)).toEqual(['alpha', 'Zulu']);
    expect(groups[3].repos.map((r) => r.name)).toEqual(['Alpha', 'Zulu']);
  });
  it('matches owner searches and rejects lookalike GitHub hosts', () => {
    expect(
      repositoryGroups([repo('tool', 'https://github.com/Alice/tool.git')], 'alice'),
    ).toHaveLength(1);
    expect(githubOwner('https://github.com.evil/Alice/tool')).toBeNull();
    expect(githubOwner('ssh://git@github.com:22/Alice/tool.git')).toBe('Alice');
  });
});
describe('post-update changelog', () => {
  it('shows once for a changed installed version, including legacy installs', () => {
    expect(needsChangelog('0.2.0', '0.2.0-alpha.18')).toBe(true);
    expect(needsChangelog('0.2.0', '0.2.0')).toBe(false);
    expect(needsChangelog('0.2.0', undefined, true)).toBe(true);
    expect(needsChangelog('0.2.0', undefined, false)).toBe(false);
  });
});
