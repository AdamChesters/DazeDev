/** Keep the microphone open briefly after an intentional stop, without delaying cancellation. */
export const RECORDING_TAIL_MS = 250;
export class RecordingTail {
  private pending?: Promise<boolean>;
  private timer?: ReturnType<typeof setTimeout>;
  private resolve?: (finish: boolean) => void;
  wait(): Promise<boolean> {
    if (this.pending) return this.pending;
    this.pending = new Promise((resolve) => {
      this.resolve = resolve;
      this.timer = setTimeout(() => this.complete(true), RECORDING_TAIL_MS);
    });
    return this.pending;
  }
  cancel() {
    this.complete(false);
  }
  private complete(finish: boolean) {
    clearTimeout(this.timer);
    const resolve = this.resolve;
    this.pending = undefined;
    this.resolve = undefined;
    this.timer = undefined;
    resolve?.(finish);
  }
}
