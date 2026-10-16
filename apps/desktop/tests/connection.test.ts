import { describe, expect, it } from 'vitest';
import { authenticationState, verificationUrl } from '../src/lib/connection';
describe('connection prompts', () => {
  it('does not invent a password prompt for Tailscale or startup', () => {
    expect(authenticationState('')).toBe('connecting');
    expect(authenticationState('Connecting with Tailscale')).toBe('connecting');
    expect(authenticationState('Permission denied', true)).toBe('closed');
  });
  it('separates host trust, changed keys, credentials and browser checks', () => {
    expect(
      authenticationState(
        'ED25519 key fingerprint is SHA256:abc\nAre you sure you want to continue connecting (yes/no/[fingerprint])?',
      ),
    ).toBe('trust');
    expect(authenticationState('REMOTE HOST IDENTIFICATION HAS CHANGED! yes/no')).toBe(
      'host-error',
    );
    expect(authenticationState("Enter passphrase for key 'test':")).toBe('credentials');
    const check = 'Tailscale SSH requires a check: https://login.tailscale.com/a/example';
    expect(authenticationState(check)).toBe('browser');
    expect(verificationUrl(check)).toBe('https://login.tailscale.com/a/example');
  });
});
