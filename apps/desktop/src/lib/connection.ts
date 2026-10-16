export function authenticationState(output: string, closed = false) {
  if (/TAILSCALE_WINDOWS_TARGET/.test(output)) return 'unsupported-target';
  if (/REMOTE HOST IDENTIFICATION HAS CHANGED|offending .* key|host key has changed/i.test(output))
    return 'host-error';
  if (/No .*host key is known|requested strict checking|host key verification failed/i.test(output))
    return 'host-unknown';
  if (closed) return 'closed';
  if (
    /https:\/\/[^\s<>]+/.test(output) &&
    /tailscale|authenticat|browser|check|login/i.test(output)
  )
    return 'browser';
  const prompt = promptLine(output);
  if (trustPrompt.some((shape) => shape.test(prompt))) return 'trust';
  if (credentialPrompt(prompt)) return 'credentials';
  return 'connecting';
}
// A prompt is the last line while it is still unfinished (no newline after it).
// Banner text that happens to contain "Password:" is a finished line.
export function promptLine(output: string): string {
  const line = output.slice(output.lastIndexOf('\n') + 1);
  return line
    .slice(line.lastIndexOf('\r') + 1)
    .replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '')
    .trimStart();
}
const trustPrompt = [
  /^are you sure you want to continue connecting \(yes\/no(?:\/\[fingerprint\])?\)\? ?$/i,
  /^please type 'yes', 'no' or the fingerprint: ?$/i,
];
// OpenSSH and PAM prompt shapes, optionally prefixed with "(user@host) ".
const prefix = String.raw`(?:\([^()\s]+\) )?`;
const passwordPrompt = [
  new RegExp(String.raw`^${prefix}(?:[^\s']+'s )?password(?: for [^\s:]+)?: ?$`, 'i'),
  new RegExp(String.raw`^${prefix}enter passphrase for (?:key )?'[^']*': ?$`, 'i'),
  /^enter passphrase for key [^:]+: ?$/i,
];
const codePrompt = [
  new RegExp(
    String.raw`^${prefix}(?:verification code|authentication code|one-time password[^:]*|otp|enter passcode|passcode|token code)(?: for [^:]+)?: ?$`,
    'i',
  ),
];
function credentialPrompt(prompt: string): 'password' | 'passphrase' | 'code' | null {
  if (!prompt) return null;
  if (/passphrase/i.test(prompt) && passwordPrompt.some((shape) => shape.test(prompt)))
    return 'passphrase';
  if (passwordPrompt.some((shape) => shape.test(prompt))) return 'password';
  if (codePrompt.some((shape) => shape.test(prompt))) return 'code';
  return null;
}
export function credentialLabel(output: string) {
  const kind = credentialPrompt(promptLine(output));
  return kind === 'passphrase'
    ? 'Key passphrase'
    : kind === 'code'
      ? 'Verification code'
      : 'Password';
}
export function verificationUrl(output: string) {
  return output.match(/https:\/\/[^\s<>\x1b]+/)?.[0]?.replace(/[).,]+$/, '') ?? '';
}
