export const UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000;
/** Manual, settings and timer callers share the same request while it is in flight. */
export class UpdateCheck<T> {
  private pending: Promise<T> | null = null;
  run(check: () => Promise<T>): Promise<T> {
    if (this.pending) return this.pending;
    this.pending = Promise.resolve()
      .then(check)
      .finally(() => {
        this.pending = null;
      });
    return this.pending;
  }
  get running() {
    return this.pending !== null;
  }
}
export function pollUpdates(check: () => Promise<unknown>, enabled: () => boolean) {
  const timer = setInterval(() => {
    if (enabled()) void check().catch(() => {});
  }, UPDATE_CHECK_INTERVAL_MS);
  return () => clearInterval(timer);
}

/** Never present an automatic modal over another interaction. */
export function canPresentUpdate(modal: string, voiceStatus: string, dragging: boolean) {
  return !modal && voiceStatus === 'idle' && !dragging;
}
