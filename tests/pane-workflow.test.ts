import { expect, it } from 'vitest';
import { balanced, columns, rectangles, ids, dock, dropZone } from '../src/lib/layout';
import { keyboardShortcut, matches, defaultShortcut } from '../src/lib/shortcut';
it.each([650, 1200, 2400])('balances four panes into a 2×2 grid at width %i', (width) => {
  const panes = ['a', 'b', 'c', 'd'];
  expect(rectangles(balanced(panes, width))).toEqual({
    a: { x: 0, y: 0, width: 50, height: 50 },
    b: { x: 50, y: 0, width: 50, height: 50 },
    c: { x: 0, y: 50, width: 50, height: 50 },
    d: { x: 50, y: 50, width: 50, height: 50 },
  });
});
it('keeps Columns in one row and fits narrow Balance layouts', () => {
  const panes = ['a', 'b', 'c', 'd'];
  expect(Object.values(rectangles(columns(panes))).every((r) => r.height === 100)).toBe(true);
  expect(Object.values(rectangles(balanced(panes, 400))).every((r) => r.width === 100)).toBe(true);
});
it.each([1, 2, 3, 5, 6, 7, 9, 12, 37])(
  'tiles %i panes without gaps or lost identities',
  (count) => {
    const panes = Array.from({ length: count }, (_, i) => String(i));
    const layout = balanced(panes, 2400);
    expect(ids(layout)).toEqual(panes);
    const rects = Object.values(rectangles(layout));
    expect(rects.reduce((sum, r) => sum + r.width * r.height, 0)).toBeCloseTo(10000);
    for (let i = 0; i < rects.length; i++) {
      for (const b of rects.slice(i + 1)) {
        const a = rects[i];
        const overlap =
          Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)) *
          Math.max(0, Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y));
        expect(overlap).toBeCloseTo(0);
      }
    }
  },
);
it.each(['left', 'right', 'top', 'bottom'] as const)(
  'docks to %s without losing or duplicating pane identities',
  (edge) => {
    const panes = ['a', 'b', 'c', 'd', 'e', 'f', 'g'];
    const layout = dock(balanced(panes), 'a', 'f', edge);
    expect(ids(layout).sort()).toEqual(panes);
    const r = rectangles(layout);
    if (edge === 'left') expect(r.a.x + r.a.width).toBeCloseTo(r.f.x);
    if (edge === 'right') expect(r.f.x + r.f.width).toBeCloseTo(r.a.x);
    if (edge === 'top') expect(r.a.y + r.a.height).toBeCloseTo(r.f.y);
    if (edge === 'bottom') expect(r.f.y + r.f.height).toBeCloseTo(r.a.y);
    expect(Object.values(r).reduce((a, b) => a + b.width * b.height, 0)).toBeCloseTo(10000);
  },
);
it('uses nearest edges and reserves the centre for tiling', () => {
  expect(dropZone(0.1, 0.5)).toBe('left');
  expect(dropZone(0.9, 0.5)).toBe('right');
  expect(dropZone(0.5, 0.1)).toBe('top');
  expect(dropZone(0.5, 0.9)).toBe('bottom');
  expect(dropZone(0.5, 0.5)).toBe('center');
});
it('records device function keys and matches exact keyboard modifiers', () => {
  const event = {
    code: 'Space',
    ctrlKey: true,
    shiftKey: true,
    altKey: false,
    metaKey: false,
  } as KeyboardEvent;
  expect(matches(event, defaultShortcut)).toBe(true);
  expect(matches({ ...event, altKey: true } as KeyboardEvent, defaultShortcut)).toBe(false);
  expect(keyboardShortcut({ ...event, code: 'F24' } as KeyboardEvent)?.key).toBe(135);
  expect(keyboardShortcut({ ...event, code: 'MediaPlayPause' } as KeyboardEvent)?.key).toBe(179);
});
