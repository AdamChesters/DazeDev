/** Own a capture-control key until keyup, even if ASR completes before it is released. */
export class VoiceCaptureKeys {
  private held = new Set<string>();
  down(
    e: Pick<
      KeyboardEvent,
      'key' | 'repeat' | 'isComposing' | 'keyCode' | 'ctrlKey' | 'altKey' | 'shiftKey' | 'metaKey'
    >,
    status: string,
    focused: boolean,
  ): { consume: boolean; action?: 'finish' | 'cancel' } {
    if (!focused) return { consume: false };
    if (this.held.has(e.key)) return { consume: true };
    if (e.isComposing || e.keyCode === 229 || e.ctrlKey || e.altKey || e.shiftKey || e.metaKey)
      return { consume: false };
    if (e.key === 'Enter' && status === 'finishing-recording') {
      this.held.add(e.key);
      return { consume: true };
    }
    const action =
      e.key === 'Enter' && status === 'listening'
        ? 'finish'
        : e.key === 'Escape' &&
            (status === 'loading' || status === 'listening' || status === 'finishing-recording')
          ? 'cancel'
          : undefined;
    if (!action) return { consume: false };
    this.held.add(e.key);
    return { consume: true, action: e.repeat ? undefined : action };
  }
  up(key: string): boolean {
    return this.held.delete(key);
  }
  reset() {
    this.held.clear();
  }
}
