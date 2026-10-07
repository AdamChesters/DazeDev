/** Display release labels without changing signed updater identities. */
export function releaseVersion(version: string): string {
  const match = /^(\d+)\.(\d+)\.(\d+)(-alpha\.0)?$/.exec(version);
  if (!match) return version;
  const [, major, minor, patch, alpha] = match;
  if (major === '0' && Number(minor) < 3) return version;
  const increment = Number(patch) ? `.${patch.padStart(2, '0')}` : '';
  return `${alpha ? 'Alpha-V' : ''}${major}.${minor}${increment}`;
}
