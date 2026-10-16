import { describe, expect, it } from 'vitest';
import { energyFrames, borderPoint, energyCount } from '../src/lib/energy-motion';
describe('compositor motion', () => {
  it('scales density with border distance while bounding compositor layers', () => {
    expect(energyCount(140, 22)).toBe(24);
    expect(energyCount(280, 44)).toBe(27);
    expect(energyCount(900, 500)).toBe(80);
    expect(energyCount(8000, 4000)).toBe(80);
  });
  it('uses rounded continuous paths at status, sidebar and terminal sizes', () => {
    for (const [w, h] of [
      [140, 22],
      [200, 40],
      [1200, 900],
    ]) {
      let previous = borderPoint(w, h, 6, 0);
      for (let i = 1; i <= 10000; i++) {
        const point = borderPoint(w, h, 6, i / 10000);
        expect(Math.hypot(point.x - previous.x, point.y - previous.y)).toBeLessThan(1);
        previous = point;
      }
    }
  });
  it('contains only transform/opacity animation and finite coordinates across small and large panes', () => {
    for (const [w, h] of [
      [0, 0],
      [80, 22],
      [2000, 1400],
    ]) {
      for (const kind of [0, 1, 2]) {
        const frames = energyFrames(w, h, 3, 6, kind);
        for (const f of frames) {
          expect(Object.keys(f).sort()).toEqual(['offset', 'opacity', 'transform']);
          expect(String(f.transform)).not.toMatch(/NaN|Infinity/);
        }
        expect(frames[0].opacity).toBe(frames.at(-1)?.opacity);
        expect(frames[0].transform).toBe(frames.at(-1)?.transform);
      }
    }
  });
});
