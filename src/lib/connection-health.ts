export const HOST_HEALTH_TIMEOUT = 12000;
export const TERMINAL_READY_TIMEOUT = 60000;

/** Bound renderer waits too: native process teardown can outlive an RPC deadline. */
export function connectionDeadline<T>(promise: Promise<T>, milliseconds: number, message: string) {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), milliseconds);
    promise.then(resolve, reject).finally(() => clearTimeout(timer));
  });
}

/** Each new attempt owns its timer; a prior attempt can never expire its replacement. */
export class ReadinessDeadline {
  private timer: ReturnType<typeof setTimeout> | undefined;
  arm(expired: () => void, milliseconds = TERMINAL_READY_TIMEOUT) {
    this.clear();
    this.timer = setTimeout(() => {
      this.timer = undefined;
      expired();
    }, milliseconds);
  }
  clear() {
    clearTimeout(this.timer);
    this.timer = undefined;
  }
}

/** Preserve native lifecycle order even when spawn/teardown replies are delayed. */
export class ConnectionOperations {
  private pending = new Map<string, Promise<unknown>>();
  run<T>(id: string, action: () => Promise<T>): Promise<T> {
    const previous = this.pending.get(id);
    const result = (previous || Promise.resolve()).catch(() => {}).then(action);
    this.pending.set(id, result);
    const clean = () => {
      if (this.pending.get(id) === result) this.pending.delete(id);
    };
    void result.then(clean, clean);
    return result;
  }
}
