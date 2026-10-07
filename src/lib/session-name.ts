export function sessionNameError(name: string): string {
  return /^[A-Za-z0-9_-]+$/.test(name)
    ? ''
    : 'Use only letters, numbers, underscores and hyphens. Spaces, dots and colons are not allowed.';
}
