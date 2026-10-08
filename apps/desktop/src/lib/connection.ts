export function authenticationState(output: string, closed = false) {
  if (/REMOTE HOST IDENTIFICATION HAS CHANGED|host key verification failed/i.test(output))
    return 'host-error';
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
