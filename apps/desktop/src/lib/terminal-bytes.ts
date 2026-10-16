import { Channel } from '@tauri-apps/api/core';

/** One output frame: an 8-byte big-endian sequence number, then raw bytes. */
export interface OutputFrame {
  sequence: number;
  data: Uint8Array;
}

export function readFrame(buffer: ArrayBuffer): OutputFrame | null {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 8) return null;
  const sequence = Number(new DataView(buffer).getBigUint64(0));
  return { sequence, data: new Uint8Array(buffer, 8) };
}

/** A channel that delivers a terminal's raw output frames in order. */
export function outputChannel(receive: (frame: OutputFrame) => void): Channel<ArrayBuffer> {
  const channel = new Channel<ArrayBuffer>();
  channel.onmessage = (buffer) => {
    // An exception here must not stop later frames.
    try {
      const frame = readFrame(buffer);
      if (frame) receive(frame);
    } catch (error) {
      console.error(error);
    }
  };
  return channel;
}

/**
 * Collects renderer acknowledgements and sends one per 64 KiB or 16 ms,
 * instead of one IPC call per output chunk.
 */
export class AckBatcher {
  private pending = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  constructor(
    private send: (bytes: number) => void,
    private limit = 65536,
    private delay = 16,
  ) {}
  add(bytes: number) {
    this.pending += bytes;
    if (this.pending >= this.limit) this.flush();
    else this.timer ??= setTimeout(() => this.flush(), this.delay);
  }
  flush() {
    clearTimeout(this.timer);
    this.timer = undefined;
    if (!this.pending) return;
    const bytes = this.pending;
    this.pending = 0;
    this.send(bytes);
  }
  /** Drop counts for a terminal that was closed or reopened. */
  reset() {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.pending = 0;
  }
}
