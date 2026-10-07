export type PinStatus = {
  enabled: boolean;
  locked: boolean;
  unavailable: boolean;
  retryAfter: number;
};
export function parsePinStatus(value: unknown): PinStatus {
  const v = value as PinStatus;
  if (
    !v ||
    typeof v.enabled !== 'boolean' ||
    typeof v.locked !== 'boolean' ||
    typeof v.unavailable !== 'boolean' ||
    !Number.isSafeInteger(v.retryAfter) ||
    v.retryAfter < 0 ||
    (v.unavailable && !v.locked)
  )
    throw Error('Startup protection could not be checked.');
  return v;
}
export function validPin(value: string): boolean {
  return value.length >= 6 && value.length <= 12 && !/[^0-9]/.test(value);
}
