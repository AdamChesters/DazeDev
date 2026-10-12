import type { Repo } from './types';
const alphabetic = (a: string, b: string) =>
  a.localeCompare(b, undefined, { sensitivity: 'base', numeric: true });
export function githubOwner(remote: string): string | null {
  const match = remote.match(
    /^(?:https?:\/\/(?:[^/@]+@)?github\.com\/|ssh:\/\/(?:[^/@]+@)?github\.com(?::22)?\/|(?:[^/@:]+@)?github\.com:)([^/]+)\/[^/]+\/?$/i,
  );
  return match?.[1] || null;
}
export function repositoryGroups(repos: Repo[], query = '') {
  const groups = new Map<string, { label: string; rank: number; repos: Repo[] }>();
  for (const repo of repos) {
    if (
      !`${repo.name} ${repo.path} ${repo.remote} ${repo.branch}`
        .toLowerCase()
        .includes(query.toLowerCase())
    )
      continue;
    const owner = githubOwner(repo.remote);
    const label = owner ? `GitHub / ${owner}` : repo.remote ? 'Other remotes' : 'Local only';
    const key = label.toLowerCase();
    const group = groups.get(key) || { label, rank: owner ? 0 : repo.remote ? 1 : 2, repos: [] };
    group.repos.push(repo);
    groups.set(key, group);
  }
  return [...groups.values()]
    .sort((a, b) => a.rank - b.rank || alphabetic(a.label, b.label))
    .map((group) => ({
      ...group,
      repos: group.repos.sort((a, b) => alphabetic(a.name, b.name) || alphabetic(a.path, b.path)),
    }));
}
export interface WorktreeGroup {
  repositoryId: string;
  name: string;
  worktrees: {
    path: string;
    branch: string;
    modifiedAt: number | null;
    partial: boolean;
    locked: boolean;
  }[];
}
export function needsChangelog(current: string, seen?: string, existingInstall = false) {
  return !!current && current !== seen && (!!seen || existingInstall);
}
