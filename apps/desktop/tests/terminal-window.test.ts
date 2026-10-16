import { describe, expect, it } from 'vitest';
import { TerminalStream, type TerminalSnapshot } from '../src/lib/terminal-window';
const snapshot = (sequence: number): TerminalSnapshot => ({
  sequence,
  generation: 1,
  data: '',
  cols: 80,
  rows: 24,
  ready: true,
  context: 'context',
});
const output = (sequence: number) => ({ sequence, data: new Uint8Array([sequence]) });

describe('detached terminal output', () => {
  it('buffers output arriving before the snapshot and does not replay included bytes', () => {
    const stream = new TerminalStream();
    expect(stream.receive(output(2))).toEqual([]);
    expect(stream.receive(output(3))).toEqual([]);
    expect(stream.restore(snapshot(2))).toEqual([output(3)]);
    expect(stream.receive(output(2))).toEqual([]);
  });
  it('orders IPC deliveries and ignores duplicates without changing the live process', () => {
    const stream = new TerminalStream();
    stream.restore(snapshot(0));
    expect(stream.receive(output(2))).toEqual([]);
    expect(stream.receive(output(1))).toEqual([output(1), output(2)]);
    expect(stream.receive(output(2))).toEqual([]);
  });
  it('resynchronizes after a lost event without duplicating already captured output', () => {
    const stream = new TerminalStream();
    stream.restore(snapshot(0));
    stream.receive(output(2));
    expect(stream.hasGap).toBe(true);
    expect(stream.restore(snapshot(2))).toEqual([]);
    expect(stream.hasGap).toBe(false);
    expect(stream.receive(output(3))).toEqual([output(3)]);
  });
});
