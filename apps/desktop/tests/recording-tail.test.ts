import { afterEach, expect, it, vi } from 'vitest';
import { RecordingTail, RECORDING_TAIL_MS } from '../src/lib/recording-tail';
import { VoiceCaptureKeys } from '../src/lib/voice-capture-keys';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
afterEach(() => vi.useRealTimers());
it('keeps capture open for 250ms, then drains audio before finalizing once', async () => {
  vi.useFakeTimers();
  const tail = new RecordingTail();
  const events: string[] = [];
  const finish = (async () => {
    if (await tail.wait()) {
      events.push('flush');
      await Promise.resolve();
      events.push('asr-stop');
    }
  })();
  expect(RECORDING_TAIL_MS).toBe(250);
  await vi.advanceTimersByTimeAsync(249);
  expect(events).toEqual([]);
  await vi.advanceTimersByTimeAsync(1);
  await finish;
  expect(events).toEqual(['flush', 'asr-stop']);
});
it.each(['Escape', 'blur', 'cancel'])(
  '%s immediately cancels the tail without finalizing or delivering',
  async () => {
    vi.useFakeTimers();
    const tail = new RecordingTail();
    const finalize = vi.fn();
    const waiting = tail.wait().then((finish) => {
      if (finish) finalize();
    });
    await vi.advanceTimersByTimeAsync(100);
    tail.cancel();
    await waiting;
    await vi.advanceTimersByTimeAsync(1000);
    expect(finalize).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  },
);
it('duplicate stops share one timer and cancellation does not poison the next recording', async () => {
  vi.useFakeTimers();
  const tail = new RecordingTail();
  const first = tail.wait();
  expect(tail.wait()).toBe(first);
  expect(vi.getTimerCount()).toBe(1);
  tail.cancel();
  expect(await first).toBe(false);
  const next = tail.wait();
  await vi.advanceTimersByTimeAsync(250);
  expect(await next).toBe(true);
});
it('Enter during tail is consumed without another stop; Escape still cancels', () => {
  const keys = new VoiceCaptureKeys();
  const key = (key: string) => ({
    key,
    repeat: false,
    isComposing: false,
    keyCode: 0,
    ctrlKey: false,
    altKey: false,
    shiftKey: false,
    metaKey: false,
  });
  expect(keys.down(key('Enter'), 'finishing-recording', true)).toEqual({ consume: true });
  expect(keys.up('Enter')).toBe(true);
  expect(keys.down(key('Escape'), 'finishing-recording', true)).toEqual({
    consume: true,
    action: 'cancel',
  });
});
it('worklet flush sends final partial PCM before acknowledgment and cannot emit after the flush fence', () => {
  const messages: unknown[] = [];
  let Processor: any;
  vm.runInNewContext(readFileSync(new URL('../public/capture.js', import.meta.url), 'utf8'), {
    AudioWorkletProcessor: class {
      port = { onmessage: null, postMessage: (message: unknown) => messages.push(message) };
    },
    sampleRate: 16000,
    Float32Array,
    registerProcessor: (_: string, processor: unknown) => {
      Processor = processor;
    },
  });
  const processor = new Processor();
  processor.process([[new Float32Array([0.2, 0.4, 0.6])]]);
  expect(messages).toEqual([]);
  processor.port.onmessage({ data: 'flush' });
  expect(Array.from(messages[0] as Float32Array)).toEqual(
    Array.from(new Float32Array([0.2, 0.4, 0.6])),
  );
  expect(messages[1]).toBe('flushed');
  processor.process([[new Float32Array(6400).fill(1)]]);
  expect(messages).toHaveLength(2);
});
