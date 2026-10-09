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
  if (
    /are you sure you want to continue connecting|\(yes\/no(?:\/\[fingerprint\])?\)/i.test(output)
  )
    return 'trust';
  if (/password:|passphrase.*:|verification code:|authentication code:|otp:/i.test(output))
    return 'credentials';
  return 'connecting';
}
export function verificationUrl(output: string) {
  return output.match(/https:\/\/[^\s<>\x1b]+/)?.[0]?.replace(/[).,]+$/, '') ?? '';
}
