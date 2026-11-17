import { afterEach, describe, expect, it, vi } from 'vitest';
import { measure, waveDensity, waveShape } from '../src/lib/mic-level';
import {
  WaveEncoder,
  WaveFeed,
  streamWave,
  waveFactor,
  waveStaleMs,
  waveTarget,
  type WaveMessage,
} from '../src/lib/voice-wave';

// A 150 Hz voice-like tone over the 2048 samples the microphone tap holds at 16 kHz.
const tone = (amplitude: number, hz = 150, length = 2048) =>
  Float32Array.from({ length }, (_, i) => amplitude * Math.sin((2 * Math.PI * hz * i) / 16000));

describe('packed waveform', () => {
  it('round-trips the wave in a small payload with the full-rate level', () => {
    const samples = tone(0.05);
    const message = new WaveEncoder().encode(samples)!;
    expect(message.kind).toBe('voice-wave');
    // 512 bytes of base64, not 2048 numbers.
    expect(message.data).toHaveLength(684);
    expect(JSON.stringify(message).length).toBeLessThan(800);
    const level = measure(samples);
    expect(message.rms).toBe(level.rms);
    expect(message.peak).toBe(level.peak);
    const feed = new WaveFeed(() => 0);
    expect(feed.receive(message)).toBe(true);
    const decoded = feed.read()!;
    expect(decoded).toHaveLength(2048 / waveFactor);
    for (let i = 0; i < decoded.length; i++) {
      let sum = 0;
      for (let j = 0; j < waveFactor; j++) sum += samples[i * waveFactor + j];
      expect(Math.abs(decoded[i] - sum / waveFactor)).toBeLessThanOrEqual(message.scale / 254);
    }
    expect(feed.level()).toEqual(level);
  });

  it('keeps the shape of quiet speech', () => {
    const quiet = new WaveEncoder().encode(tone(0.002))!;
    const feed = new WaveFeed(() => 0);
    feed.receive(quiet);
    expect(Math.max(...feed.read()!)).toBeCloseTo(0.002, 4);
  });

  it('draws the same wave around a window as the full-rate samples do', () => {
    const samples = tone(0.05);
    const feed = new WaveFeed(() => 0);
    feed.receive(new WaveEncoder().encode(samples)!);
    const count = 1100;
    const full = waveShape(samples, 0.8, new Float32Array(count), waveDensity);
    const packed = waveShape(feed.read()!, 0.8, new Float32Array(count), waveDensity / waveFactor);
    const crossings = (out: Float32Array) => {
      let n = 0;
      for (let i = 1; i < out.length; i++) if (out[i - 1] < 0 !== out[i] < 0) n++;
      return n;
    };
    // Same wave length and height. Averaging shifts it by under a tenth of a millisecond.
    expect(Math.abs(crossings(full) - crossings(packed))).toBeLessThanOrEqual(1);
    const height = (out: Float32Array) => Math.max(...out.map(Math.abs));
    expect(height(packed)).toBeCloseTo(height(full), 2);
    let difference = 0;
    for (let i = 0; i < count; i++) difference += Math.abs(full[i] - packed[i]) / count;
    expect(difference).toBeLessThan(0.03);
  });

  it('sends silence as a flat wave without dividing by zero', () => {
    const message = new WaveEncoder().encode(new Float32Array(2048))!;
    expect(message.scale).toBe(0);
    const feed = new WaveFeed(() => 0);
    feed.receive(message);
    expect([...feed.read()!].every((value) => value === 0)).toBe(true);
  });

  it('reuses its buffers between frames', () => {
    const feed = new WaveFeed(() => 0);
    const encoder = new WaveEncoder();
    feed.receive(encoder.encode(tone(0.05))!);
    const first = feed.read();
    feed.receive(encoder.encode(tone(0.1))!);
    expect(feed.read()).toBe(first);
  });

  it('ignores malformed frames', () => {
    const feed = new WaveFeed(() => 0);
    const good = new WaveEncoder().encode(tone(0.05))!;
    expect(new WaveEncoder().encode(new Float32Array(4))).toBeNull();
    for (const bad of [
      null,
      'voice-wave',
      { ...good, data: 42 },
      { ...good, rms: Number.NaN },
      { ...good, scale: Infinity },
      { ...good, peak: '0.1' },
      { ...good, data: '***' },
      { ...good, data: 'AA==' },
      { ...good, data: 'A'.repeat(5000) },
    ])
      expect(feed.receive(bad)).toBe(false);
    expect(feed.read()).toBeNull();
    expect(feed.level()).toBeNull();
  });
});

describe('waveform staleness', () => {
  it('falls flat when frames stop arriving, instead of freezing', () => {
    let now = 1000;
    const feed = new WaveFeed(() => now);
    expect(feed.read()).toBeNull();
    feed.receive(new WaveEncoder().encode(tone(0.05))!);
    now += waveStaleMs;
    expect(feed.read()).not.toBeNull();
    expect(feed.level()).not.toBeNull();
    now += 1;
    expect(feed.read()).toBeNull();
    expect(feed.level()).toBeNull();
    feed.receive(new WaveEncoder().encode(tone(0.05))!);
    expect(feed.read()).not.toBeNull();
    feed.clear();
    expect(feed.read()).toBeNull();
  });
});

describe('waveform stream', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('streams only while dictation listens into a detached terminal', () => {
    const detached = (id: string) => id === 'a';
    expect(waveTarget(true, 'a', detached)).toBe('a');
    expect(waveTarget(true, 'b', detached)).toBe('');
    expect(waveTarget(false, 'a', detached)).toBe('');
    expect(waveTarget(true, '', () => true)).toBe('');
  });

  it('sends about 30 frames a second and stops when told', async () => {
    vi.useFakeTimers();
    const sent: WaveMessage[] = [];
    const read = vi.fn(() => tone(0.05));
    const stop = streamWave(read, async (message) => {
      sent.push(message);
    });
    await vi.advanceTimersByTimeAsync(1000);
    expect(sent.length).toBeGreaterThanOrEqual(29);
    expect(sent.length).toBeLessThanOrEqual(31);
    stop();
    const count = sent.length;
    const reads = read.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1000);
    expect(sent).toHaveLength(count);
    expect(read).toHaveBeenCalledTimes(reads);
  });

  it('sends nothing while the microphone is not open', async () => {
    vi.useFakeTimers();
    const send = vi.fn(async () => {});
    const stop = streamWave(() => null, send);
    await vi.advanceTimersByTimeAsync(500);
    expect(send).not.toHaveBeenCalled();
    stop();
  });

  it('skips frames while one is still on its way, and survives a failed send', async () => {
    vi.useFakeTimers();
    let deliver!: () => void;
    const send = vi.fn(
      () =>
        new Promise<void>((resolve, reject) => {
          deliver = send.mock.calls.length === 1 ? () => reject(new Error('lost')) : resolve;
        }),
    );
    const stop = streamWave(() => tone(0.05), send);
    await vi.advanceTimersByTimeAsync(500);
    expect(send).toHaveBeenCalledTimes(1);
    deliver();
    await vi.advanceTimersByTimeAsync(40);
    expect(send).toHaveBeenCalledTimes(2);
    stop();
    deliver();
    await vi.advanceTimersByTimeAsync(500);
    expect(send).toHaveBeenCalledTimes(2);
  });
});
