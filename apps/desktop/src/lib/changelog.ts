import { releaseVersion } from './release-version';
import history from '../../../../shared/releases/changelog.json';

export type ReleasePlatform = 'desktop' | 'mobile';
export type ReleaseEntry = {
  platform: ReleasePlatform;
  version: string;
  date: string;
  notes: string;
  channel?: string;
};
export const changelog = history as ReleaseEntry[];

export function mergeChangelog(
  remote: unknown,
  bundled: ReleaseEntry[] = changelog,
): ReleaseEntry[] {
  if (!Array.isArray(remote) || remote.length > 500) return bundled;
  const entries = new Map(bundled.map((entry) => [entry.platform + ':' + entry.version, entry]));
  for (const entry of remote) {
    if (
      !entry ||
      !['desktop', 'mobile'].includes(entry.platform) ||
      typeof entry.version !== 'string' ||
      entry.version.length > 80 ||
      typeof entry.date !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}$/.test(entry.date) ||
      Number.isNaN(Date.parse(entry.date)) ||
      typeof entry.notes !== 'string' ||
      entry.notes.length > 50000
    )
      continue;
    entries.set(entry.platform + ':' + entry.version, entry);
  }
  return [...entries.values()].sort((a, b) => b.date.localeCompare(a.date));
}

/** Format recorded publication dates in UTC so headings do not shift by timezone. */
export function releaseHeading(version: string, date?: string): string {
  const published = date ? new Date(`${date}T00:00:00Z`) : null;
  const label =
    published && !Number.isNaN(published.getTime())
      ? new Intl.DateTimeFormat('en-GB', {
          day: 'numeric',
          month: 'long',
          year: 'numeric',
          timeZone: 'UTC',
        }).format(published)
      : 'Release date unavailable';
  return `${label} : ${releaseVersion(version)}`;
}
