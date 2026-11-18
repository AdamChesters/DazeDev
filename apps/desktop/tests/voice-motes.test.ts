import { describe, expect, it } from 'vitest';
import {
  maxSlide,
  MoteField,
  moteAttempts,
  moteBurst,
  moteCapacity,
  moteLifeMs,
  surge,
} from '../src/lib/voice-motes';

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

  it('drives births with the square of the level', () => {
    expect(surge(0)).toBe(0);
    expect(surge(1)).toBe(3);
    expect(surge(0.58)).toBeCloseTo(1, 1);
    expect(surge(-1)).toBe(0);
    expect(surge(4)).toBe(3);
  });

  it('holds about as many as the level, the wave and their lives allow', () => {
    const field = new MoteField(4000, seeded());
    run(field, flat(0.6), 0.9, 2000);
    const life = (moteLifeMs[0] + moteLifeMs[1]) / 2000;
    const expected = ((moteAttempts * surge(0.9) * 1200) / 1000) * 0.6 * life;
    expect(field.live).toBeGreaterThan(expected * 0.7);
    expect(field.live).toBeLessThan(expected * 1.3);
  });

  it('makes far more for a loud voice than a quiet one with the same wave', () => {
    const quiet = new MoteField(4000, seeded());
    const loud = new MoteField(4000, seeded());
    run(quiet, flat(0.5), 0.3, 2000);
    run(loud, flat(0.5), 0.9, 2000);
    expect(quiet.live).toBeGreaterThan(0);
    // Three times the level gives about nine times the motes.
    expect(loud.live).toBeGreaterThan(quiet.live * 6);
  });

  it('makes more the taller the wave is', () => {
    const low = new MoteField(4000, seeded());
    const tall = new MoteField(4000, seeded());
    run(low, flat(0.15), 0.7, 2000);
    run(tall, flat(0.6), 0.7, 2000);
    expect(low.live).toBeGreaterThan(0);
    expect(tall.live).toBeGreaterThan(low.live * 3);
  });

  it('sets off a burst when the voice rises', () => {
    const field = new MoteField(1500, seeded());
    field.step(flat(1), 0.9, 1, 1200);
    const burst = field.live;
    expect(burst).toBeGreaterThan(((moteBurst * 0.9 * 1200) / 1000) * 0.9);
    // Holding the level adds only the steady trickle.
    field.step(flat(1), 0.9, 1, 1200);
    expect(field.live - burst).toBeLessThan(15);
    // A fall sets off nothing.
    const falling = new MoteField(1500, seeded());
    falling.step(flat(0), 0.9, 1, 1200);
    falling.step(flat(1), 0.1, 1, 1200);
    expect(falling.live).toBeLessThan(3);
  });

  it('thins out quickly once the voice drops', () => {
    const held = new MoteField(4000, seeded(5));
    const dropped = new MoteField(4000, seeded(5));
    run(held, flat(0.8), 0.9, 1000);
    run(dropped, flat(0.8), 0.9, 1000);
    expect(dropped.live).toBe(held.live);
    // No wave, so nothing new is born in either: only the ageing differs.
    run(held, flat(0), 0.9, 130);
    run(dropped, flat(0), 0.05, 130);
    expect(held.live).toBeGreaterThan(100);
    expect(dropped.live).toBeLessThan(held.live * 0.35);
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
        if (!(opacity >= 0 && opacity <= 0.95)) outside++;
        if (!(Math.abs(field.drift[i]) <= maxSlide)) outside++;
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
    expect(furthest(0.04)).toBeLessThan(0.35);
    expect(furthest(0.9)).toBeGreaterThan(0.8);
  });

  it('makes most motes hop out and back, and the rest shoot in and stay', () => {
    const field = new MoteField(1500, seeded(11));
    field.step(flat(1), 0.9, 1, 1200);
    const furthest = new Float32Array(field.capacity);
    const last = new Float32Array(field.capacity);
    let hopped = 0;
    let stayed = 0;
    for (let steps = 0; field.live && steps < 2000; steps++) {
      // The voice holds with no wave: nothing is born and nothing lifts the motes.
      const before = Array.from(field.age);
      field.step(flat(0), 0.9, 2, 1200);
      for (let i = 0; i < field.capacity; i++) {
        if (before[i] >= 1) continue;
        if (field.age[i] < 1) {
          last[i] = field.depth(i);
          furthest[i] = Math.max(furthest[i], last[i]);
        } else if (last[i] < furthest[i] * 0.3) hopped++;
        else stayed++;
      }
    }
    expect(hopped + stayed).toBeGreaterThan(800);
    expect(hopped / (hopped + stayed)).toBeGreaterThan(0.5);
    expect(hopped / (hopped + stayed)).toBeLessThan(0.7);
  });

  it('lifts the motes while the wave under them is tall', () => {
    const depthOfFirst = (wave: number) => {
      const field = new MoteField(1500, seeded(13));
      field.step(flat(1), 0.9, 1, 1200);
      const born = field.live;
      field.step(flat(wave), 0.9, 60, 1200);
      let sum = 0;
      for (let i = 0; i < born; i++) sum += field.depth(i);
      return sum / born;
    };
    expect(depthOfFirst(1)).toBeGreaterThan(depthOfFirst(0) * 1.4);
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

  it('starts dark, flickers and is gone at the end of its life', () => {
    const field = new MoteField(1, seeded());
    while (!field.live) field.step(flat(1), 1, 1, 1200);
    expect(field.opacity(0)).toBeLessThan(0.08);
    let brightest = 0;
    let turns = 0;
    let rising = true;
    let before = field.opacity(0);
    let steps = 0;
    while (field.live && steps++ < 2000) {
      // The voice holds but there is no wave, so nothing new is born while this one ages.
      field.step(flat(0), 1, 2, 1200);
      const now = field.opacity(0);
      brightest = Math.max(brightest, now);
      if (now > before !== rising) {
        rising = !rising;
        turns++;
      }
      before = now;
    }
    expect(brightest).toBeGreaterThan(0.2);
    // Brighter and dimmer several times over, not one smooth rise and fall.
    expect(turns).toBeGreaterThanOrEqual(4);
    expect(field.opacity(0)).toBe(0);
    expect(steps * 2).toBeLessThanOrEqual(moteLifeMs[1] + 8);
  });

  it('clears at once', () => {
    const field = new MoteField(100, seeded());
    run(field, flat(1), 1, 500);
    expect(field.live).toBeGreaterThan(0);
    field.clear();
    expect(field.live).toBe(0);
  });

  it('sizes itself to the border', () => {
    expect(moteCapacity(50)).toBe(64);
    expect(moteCapacity(3000)).toBe(2700);
    expect(moteCapacity(40000)).toBe(3200);
  });
});
