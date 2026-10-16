import { describe, expect, it } from 'vitest';
import { authenticationState, credentialLabel, verificationUrl } from '../src/lib/connection';
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
  it('ignores prompt words in finished banner lines', () => {
    const banner = 'Welcome. Password: rotate it every 90 days.\nLast login: Monday\n';
    expect(authenticationState(banner)).toBe('connecting');
    expect(authenticationState('Note: enter Password: below\n')).toBe('connecting');
    expect(authenticationState(banner + "user@example's password: ")).toBe('credentials');
  });
  it('accepts OpenSSH and PAM prompt shapes only on the unfinished last line', () => {
    for (const prompt of [
      "user@example's password: ",
      'Password: ',
      '(user@example) Password: ',
      'Password for user@example: ',
      "Enter passphrase for key '/home/user/.ssh/id_ed25519': ",
      '(user@example) Verification code: ',
      'One-time password (OATH) for `user`: ',
    ])
      expect(authenticationState('banner\r\n' + prompt), prompt).toBe('credentials');
    expect(authenticationState('Password: yes I have one')).toBe('connecting');
    expect(authenticationState('Are you sure you want to continue connecting (yes/no)?\n')).toBe(
      'connecting',
    );
    expect(authenticationState("Please type 'yes', 'no' or the fingerprint: ")).toBe('trust');
  });
  it('labels the credential from the prompt, not from earlier output', () => {
    expect(credentialLabel("Key passphrase help\nuser@example's password: ")).toBe('Password');
    expect(credentialLabel("Enter passphrase for key '/k': ")).toBe('Key passphrase');
    expect(credentialLabel('(user@example) Verification code: ')).toBe('Verification code');
  });
});
