import { describe, expect, it } from 'vitest';
import { MoteField, moteAttempts, moteCapacity, moteLifeMs } from '../src/lib/voice-motes';

/** A repeatable stand-in for Math.random. */
const seeded = (seed = 1) => {
  let state = seed;
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296;
    return state / 4294967296;
  };
};
const flat = (height: number, count = 400) => new Float32Array(count).fill(height);
/** Runs a field for `ms` in 16 ms frames. */
const run = (field: MoteField, heights: ArrayLike<number>, level: number, ms: number) => {
  for (let t = 0; t < ms; t += 16) field.step(heights, level, 16, 1200);
};

describe('voice motes', () => {
  it('makes none in silence', () => {
    const field = new MoteField(200, seeded());
    run(field, flat(0), 0.8, 1000);
    expect(field.live).toBe(0);
    run(field, flat(0.9), 0, 1000);
    expect(field.live).toBe(0);
    run(field, [], 0.8, 1000);
    expect(field.live).toBe(0);
  });

  it('makes more the taller the wave is', () => {
    const quiet = new MoteField(900, seeded());
    const loud = new MoteField(900, seeded());
    run(quiet, flat(0.1), 0.4, 2000);
    run(loud, flat(0.6), 0.9, 2000);
    expect(quiet.live).toBeGreaterThan(0);
    expect(loud.live).toBeGreaterThan(quiet.live * 3);
    // About attempts x height x life are alive at once on this 1200px border.
    const life = (moteLifeMs[0] + moteLifeMs[1]) / 2000;
    const expected = ((moteAttempts * 1200) / 1000) * 0.6 * life;
    expect(loud.live).toBeGreaterThan(expected * 0.7);
    expect(loud.live).toBeLessThan(expected * 1.3);
  });

  it('gathers them where the wave is tall', () => {
    const heights = new Float32Array(400);
    heights.fill(0.8, 100, 200);
    const field = new MoteField(600, seeded());
    run(field, heights, 0.8, 1500);
    expect(field.live).toBeGreaterThan(20);
    for (let i = 0; i < field.capacity; i++) {
      if (field.age[i] >= 1) continue;
      expect(field.point[i]).toBeGreaterThanOrEqual(100);
      expect(field.point[i]).toBeLessThan(200);
    }
  });

  it('keeps every mote within the reach and no brighter than its glow', () => {
    const field = new MoteField(600, seeded(7));
    let outside = 0;
    for (let t = 0; t < 3000; t += 16) {
      field.step(flat(1), 1, 16, 1200);
      for (let i = 0; i < field.capacity; i++) {
        const depth = field.depth(i);
        const opacity = field.opacity(i);
        if (!(depth >= 0 && depth <= 1)) outside++;
        if (!(opacity >= 0 && opacity <= 0.9)) outside++;
        if (!(Math.abs(field.drift[i]) <= 6)) outside++;
      }
    }
    expect(outside).toBe(0);
  });

  it('travels further for a taller wave', () => {
    const furthest = (height: number) => {
      const field = new MoteField(600, seeded(3));
      run(field, flat(height), 0.8, 2000);
      return Math.max(...field.travel);
    };
    expect(furthest(0.2)).toBeLessThanOrEqual(0.2);
    expect(furthest(0.9)).toBeGreaterThan(0.6);
  });

  it('fades every mote out once the voice stops and reuses its place', () => {
    const field = new MoteField(64, seeded());
    run(field, flat(1), 1, 1000);
    expect(field.live).toBe(64);
    run(field, flat(0), 0, moteLifeMs[1] + 32);
    expect(field.live).toBe(0);
    for (let i = 0; i < field.capacity; i++) expect(field.opacity(i)).toBe(0);
    run(field, flat(1), 1, 1000);
    expect(field.live).toBe(64);
  });

  it('never holds more than its capacity', () => {
    const field = new MoteField(48, seeded());
    for (let t = 0; t < 2000; t += 16) {
      field.step(flat(1), 1, 16, 9000);
      expect(field.live).toBeLessThanOrEqual(48);
    }
  });

  it('starts dark, brightens quickly and is gone at the end of its life', () => {
    const field = new MoteField(1, seeded());
    while (!field.live) field.step(flat(1), 1, 1, 1200);
    expect(field.opacity(0)).toBeLessThan(0.05);
    let brightest = 0;
    let steps = 0;
    while (field.live && steps++ < 2000) {
      // No voice, so nothing new is born while this one ages.
      field.step(flat(0), 0, 4, 1200);
      brightest = Math.max(brightest, field.opacity(0));
    }
    expect(brightest).toBeGreaterThan(0.2);
    expect(field.opacity(0)).toBe(0);
    expect(steps * 4).toBeLessThanOrEqual(moteLifeMs[1] + 8);
  });

  it('clears at once', () => {
    const field = new MoteField(100, seeded());
    run(field, flat(1), 1, 500);
    expect(field.live).toBeGreaterThan(0);
    field.clear();
    expect(field.live).toBe(0);
  });

  it('sizes itself to the border', () => {
    expect(moteCapacity(100)).toBe(48);
    expect(moteCapacity(3000)).toBe(900);
    expect(moteCapacity(40000)).toBe(1400);
  });
});
