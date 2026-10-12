/** Delay a review tap so the second tap can submit without starting capture. */
export class ReviewHotkey {
  private pending: ReturnType<typeof setTimeout> | undefined;
  tap(start: () => void, submit: () => void) {
    if (this.pending !== undefined) {
      this.cancel();
      submit();
    } else
      this.pending = setTimeout(() => {
        this.pending = undefined;
        start();
      }, 350);
  }
  cancel() {
    clearTimeout(this.pending);
    this.pending = undefined;
  }
}
