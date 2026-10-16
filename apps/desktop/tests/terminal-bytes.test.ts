import { afterEach, describe, expect, it, vi } from 'vitest';
import { AckBatcher, readFrame } from '../src/lib/terminal-bytes';

function frame(sequence: number, bytes: number[]) {
  const buffer = new ArrayBuffer(8 + bytes.length);
  new DataView(buffer).setBigUint64(0, BigInt(sequence));
  new Uint8Array(buffer, 8).set(bytes);
  return buffer;
}

describe('output frames', () => {
  it('reads the sequence and keeps the bytes without copying text', () => {
    const read = readFrame(frame(2 ** 40 + 3, [27, 91, 0, 255]))!;
    expect(read.sequence).toBe(2 ** 40 + 3);
    expect([...read.data]).toEqual([27, 91, 0, 255]);
  });
  it('ignores frames without a full header', () => {
    expect(readFrame(new ArrayBuffer(7))).toBeNull();
    expect(readFrame(frame(1, []))!.data.length).toBe(0);
  });
});

describe('acknowledgements', () => {
  afterEach(() => vi.useRealTimers());
  it('sends one call per 64 KiB or after a short pause', () => {
    vi.useFakeTimers();
    const sent: number[] = [];
    const acks = new AckBatcher((bytes) => sent.push(bytes));
    for (let i = 0; i < 16; i++) acks.add(4096);
    expect(sent).toEqual([65536]);
    acks.add(100);
    acks.add(200);
    expect(sent).toEqual([65536]);
    vi.advanceTimersByTime(16);
    expect(sent).toEqual([65536, 300]);
  });
  it('drops counts after a reset', () => {
    vi.useFakeTimers();
    const sent: number[] = [];
    const acks = new AckBatcher((bytes) => sent.push(bytes));
    acks.add(10);
    acks.reset();
    vi.advanceTimersByTime(50);
    expect(sent).toEqual([]);
  });
});
