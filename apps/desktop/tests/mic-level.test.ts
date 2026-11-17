import { describe, expect, it } from 'vitest';
import {
  levelAdvice,
  measure,
  Meter,
  toDb,
  toUnit,
  triggerOffset,
  waveShape,
} from '../src/lib/mic-level';

const sine = (amplitude: number, length = 1024, cycles = 8) =>
  Float32Array.from(
    { length },
    (_, i) => amplitude * Math.sin((2 * Math.PI * cycles * i) / length),
  );

describe('microphone level', () => {
  it('measures RMS and peak', () => {
    const level = measure(sine(0.5));
    expect(level.peak).toBeCloseTo(0.5, 2);
    expect(level.rms).toBeCloseTo(0.5 / Math.SQRT2, 3);
    expect(measure(new Float32Array(0))).toEqual({ rms: 0, peak: 0 });
  });

  it('maps amplitude to a decibel scale between the floor and full scale', () => {
    expect(toDb(1)).toBe(0);
    expect(toDb(0)).toBe(-Infinity);
    expect(toUnit(0)).toBe(0);
    expect(toUnit(0.0005)).toBe(0);
    expect(toUnit(0.001)).toBeCloseTo(0, 5);
    expect(toUnit(0.0316)).toBeCloseTo(0.5, 2);
    expect(toUnit(1)).toBe(1);
    expect(toUnit(4)).toBe(1);
  });

  it('rises at once, falls steadily and returns to zero in silence', () => {
    const meter = new Meter();
    meter.update({ rms: 0.1, peak: 0.2 }, 1000);
    const loud = meter.level;
    expect(loud).toBeCloseTo(toUnit(0.1), 5);
    meter.update({ rms: 0, peak: 0 }, 1100);
    expect(meter.level).toBeLessThan(loud);
    expect(meter.level).toBeGreaterThan(0);
    meter.update({ rms: 0.3, peak: 0.4 }, 1116);
    expect(meter.level).toBeCloseTo(toUnit(0.3), 5);
    meter.update(null, 5000);
    meter.update(null, 5200);
    meter.update(null, 5400);
    meter.update(null, 5600);
    expect(meter.level).toBe(0);
  });

  it('holds the peak marker, then lets it fall', () => {
    const meter = new Meter();
    meter.update({ rms: 0.1, peak: 0.5 }, 1000);
    const held = meter.peak;
    meter.update({ rms: 0.01, peak: 0.02 }, 1500);
    expect(meter.peak).toBe(held);
    meter.update({ rms: 0.01, peak: 0.02 }, 2000);
    meter.update({ rms: 0.01, peak: 0.02 }, 2200);
    expect(meter.peak).toBeLessThan(held);
    expect(meter.peak).toBeGreaterThanOrEqual(toUnit(0.02));
  });

  it('flags clipping and clears the flag after a hold', () => {
    const meter = new Meter();
    expect(meter.update({ rms: 0.2, peak: 0.5 }, 1000).clipped).toBe(false);
    expect(meter.update({ rms: 0.7, peak: 1 }, 1016).clipped).toBe(true);
    expect(meter.update({ rms: 0.2, peak: 0.5 }, 1500).clipped).toBe(true);
    expect(meter.update({ rms: 0.2, peak: 0.5 }, 2400).clipped).toBe(false);
    meter.update({ rms: 0.7, peak: 1 }, 2500);
    meter.reset();
    expect(meter.clipped).toBe(false);
    expect(meter.level).toBe(0);
  });

  it('describes the level in plain words', () => {
    expect(levelAdvice(-Infinity, false)).toMatch(/No sound/);
    expect(levelAdvice(-90, false)).toMatch(/No sound/);
    expect(levelAdvice(-50, false)).toMatch(/Very quiet/);
    expect(levelAdvice(-35, false)).toMatch(/usable/);
    expect(levelAdvice(-20, false)).toBe('Good level.');
    expect(levelAdvice(-3, true)).toMatch(/clipping/);
  });
});

describe('waveform shape', () => {
  it('is flat in silence or at zero level', () => {
    const out = new Float32Array(64).fill(9);
    expect([...waveShape(new Float32Array(1024), 0.8, out)].every((v) => v === 0)).toBe(true);
    expect([...waveShape(sine(0.3), 0, out.fill(9))].every((v) => v === 0)).toBe(true);
    expect([...waveShape([], 0.8, out.fill(9))].every((v) => v === 0)).toBe(true);
  });

  it('follows the level, not the raw volume, so quiet speech is still visible', () => {
    const quiet = Math.max(...waveShape(sine(0.01), 0.6, new Float32Array(512)));
    const loud = Math.max(...waveShape(sine(0.5), 0.6, new Float32Array(512)));
    expect(quiet).toBeCloseTo(loud, 2);
    expect(quiet).toBeGreaterThan(0.2);
    const taller = Math.max(...waveShape(sine(0.01), 0.9, new Float32Array(512)));
    expect(taller).toBeGreaterThan(quiet);
  });

  it('stays within bounds and keeps the wave', () => {
    const spiky = sine(0.05);
    spiky[500] = 1;
    const out = waveShape(spiky, 1, new Float32Array(300), 3.4);
    expect(Math.max(...out)).toBeLessThanOrEqual(1);
    expect(Math.min(...out)).toBeGreaterThanOrEqual(-1);
    let crossings = 0;
    for (let i = 1; i < 300; i++) if (out[i - 1] < 0 !== out[i] < 0) crossings++;
    expect(crossings).toBeGreaterThanOrEqual(12);
  });

  it('joins into a loop without a step, whatever the loop size', () => {
    const ramp = Float32Array.from({ length: 1536 }, (_, i) => (i / 1535) * 0.2 - 0.05);
    for (const count of [90, 400, 1400, 3000]) {
      const out = waveShape(ramp, 0.8, new Float32Array(count), 2.3);
      let step = Math.abs(out[0] - out[count - 1]);
      for (let i = 1; i < count; i++) step = Math.max(step, Math.abs(out[i] - out[i - 1]));
      expect(step).toBeLessThan(0.05);
    }
  });

  it('keeps the same wave length on a larger loop', () => {
    const crossings = (count: number) => {
      const out = waveShape(sine(0.3, 1536, 12), 0.8, new Float32Array(count), 2.3);
      let n = 0;
      for (let i = 1; i < count; i++) if (out[i - 1] < 0 !== out[i] < 0) n++;
      return n / count;
    };
    expect(crossings(2800)).toBeCloseTo(crossings(700), 2);
  });

  it('does not magnify noise below the meter floor', () => {
    const out = waveShape(sine(0.00001), 0.5, new Float32Array(128));
    expect(Math.max(...out)).toBeLessThan(0.01);
  });

  it('starts on an upward zero crossing so a steady tone does not slide', () => {
    const tone = sine(0.3, 2048, 16);
    const a = triggerOffset(tone.subarray(37), 512);
    const b = triggerOffset(tone.subarray(90), 512);
    expect(a).toBeGreaterThan(0);
    const first = waveShape(tone.subarray(37 + a, 37 + a + 1536), 0.7, new Float32Array(200));
    const second = waveShape(tone.subarray(90 + b, 90 + b + 1536), 0.7, new Float32Array(200));
    for (let i = 0; i < 200; i++) expect(first[i]).toBeCloseTo(second[i], 1);
    expect(triggerOffset(new Float32Array(64), 16)).toBe(0);
    expect(triggerOffset([], 16)).toBe(0);
  });
});
