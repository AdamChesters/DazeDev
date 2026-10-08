import { describe, expect, it } from 'vitest';
import { balanced, dock, ids, insertAt, remove, resize, tile } from '../src/lib/layout';
import { reconcileViewerLayout, terminalProjection } from '../src/lib/viewer-layout';
import type { Layout } from '../src/lib/types';
const leaf = (id: string): Layout => ({ kind: 'pane', id });
const split = (id: string, first: Layout, second: Layout, ratio = 0.4): Layout => ({
  kind: 'split',
  id,
  axis: 'row',
  ratio,
  first,
  second,
});
function expectProjection(combined: Layout | null, canonical: Layout | null) {
  expect(terminalProjection(combined, canonical ? ids(canonical) : [])).toEqual(canonical);
}

describe('session-only viewer layout', () => {
  it('projects terminal split IDs, axes and ratios exactly and never mutates the combined tree', () => {
    const terminal = split('terminal-divider', leaf('a'), leaf('b'), 0.63);
    const combined = insertAt(terminal, 'a', 'viewer', 'bottom');
    const before = structuredClone(combined);
    expectProjection(combined, terminal);
    expect(combined).toEqual(before);
    expect(ids(terminalProjection(combined, ['a', 'b'])!)).toEqual(['a', 'b']);
  });
  it('preserves the exact tree and divider ratios after its terminal projection is saved', () => {
    const combined = resize(insertAt(tile(['a', 'b']), 'a', 'viewer', 'right'), 'unused', 0.8);
    const canonical = terminalProjection(combined, ['a', 'b']);
    expect(reconcileViewerLayout(combined, structuredClone(canonical), ['viewer'])).toBe(combined);
  });
  it('keeps a viewer with its terminal when a terminal is added elsewhere', () => {
    const canonical = split('ab', leaf('a'), leaf('b'));
    const combined = insertAt(canonical, 'a', 'viewer', 'left');
    const next = insertAt(canonical, 'b', 'c', 'bottom');
    const reconciled = reconcileViewerLayout(combined, next, ['viewer'])!;
    expectProjection(reconciled, next);
    expect(reconciled.kind === 'split' && reconciled.first).toEqual(
      combined.kind === 'split' && combined.first,
    );
    expect(ids(reconciled)).toEqual(['viewer', 'a', 'b', 'c']);
  });
  it('retains viewers when their anchor terminal is removed or moved out', () => {
    const combined = split('root', split('anchor', leaf('a'), leaf('viewer')), leaf('b'));
    const next = leaf('b');
    const result = reconcileViewerLayout(combined, next, ['viewer'])!;
    expectProjection(result, next);
    expect(ids(result)).toEqual(['viewer', 'b']);
    expect(result.kind === 'split' && result.id).toBe('root');
  });
  it('keeps nested viewer-only groups and their split ratios across terminal reloads', () => {
    const viewers = split('viewer-divider', leaf('v1'), leaf('v2'), 0.71);
    const combined = split('outer', split('inner', leaf('a'), viewers, 0.22), leaf('v3'), 0.83);
    const next = split('new-terminal-divider', leaf('a'), leaf('b'), 0.31);
    const result = reconcileViewerLayout(combined, next, ['v1', 'v2', 'v3'])!;
    expectProjection(result, next);
    expect(ids(result)).toEqual(['a', 'v1', 'v2', 'v3', 'b']);
    expect(JSON.stringify(result)).toContain('"ratio":0.71');
    expect(JSON.stringify(result)).toContain('"ratio":0.83');
  });
  it('uses the surviving group anchor after terminal order and structure change', () => {
    const oldTerminals = split('ab', leaf('a'), leaf('b'));
    const combined = split('group-viewer', oldTerminals, leaf('viewer'), 0.75);
    const next = split('new-root', leaf('b'), split('ac', leaf('a'), leaf('c')), 0.6);
    const result = reconcileViewerLayout(combined, next, ['viewer'])!;
    expectProjection(result, next);
    expect(result.kind === 'split' && result.id).toBe('group-viewer');
  });
  it('closing a viewer promotes its sibling and removing all viewers restores terminals', () => {
    const terminal = split('ab', leaf('a'), leaf('b'));
    const combined = insertAt(insertAt(terminal, 'a', 'v1', 'right'), 'v1', 'v2', 'bottom');
    expect(reconcileViewerLayout(combined, terminal, ['v2'])).toEqual(remove(combined, 'v1'));
    expect(reconcileViewerLayout(combined, terminal, [])).toEqual(terminal);
  });
  it('supports viewer-only workspaces, the empty terminal sentinel and the first new terminal', () => {
    const viewers = split('viewers', leaf('v1'), leaf('v2'), 0.67);
    expect(reconcileViewerLayout(viewers, null, ['v1', 'v2'])).toBe(viewers);
    expect(reconcileViewerLayout(viewers, leaf(''), ['v1', 'v2'])).toBe(viewers);
    expect(terminalProjection(viewers, [])).toBeNull();
    const next = reconcileViewerLayout(viewers, leaf('a'), ['v1', 'v2'])!;
    expectProjection(next, leaf('a'));
    expect(next.kind === 'split' && next.second).toBe(viewers);
    expect(reconcileViewerLayout(null, null, [])).toBeNull();
  });
  it('appends missing viewers on the right without duplicate leaves', () => {
    const result = reconcileViewerLayout(null, leaf('a'), ['v1', 'v1', 'v2'])!;
    expect(ids(result)).toEqual(['a', 'v1', 'v2']);
    expectProjection(result, leaf('a'));
  });
  it('keeps an explicitly docked or balanced combined tree when its projection is authoritative', () => {
    for (const result of [
      dock(tile(['a', 'b', 'v1', 'v2']), 'v1', 'b', 'top'),
      balanced(['a', 'v1', 'b', 'v2']),
    ]) {
      const canonical = terminalProjection(result, ['a', 'b']);
      expect(reconcileViewerLayout(result, canonical, ['v1', 'v2'])).toBe(result);
    }
  });
  it('preserves the authoritative terminal projection and every live viewer across layout changes', () => {
    for (let count = 1; count <= 12; count++) {
      const terminals = Array.from({ length: count }, (_, i) => `t${i}`);
      let previous = balanced(terminals);
      const viewers = ['v1', 'v2', 'v3'];
      previous = insertAt(previous, terminals[0], 'v1', 'left');
      previous = insertAt(previous, terminals.at(-1)!, 'v2', 'bottom');
      previous = insertAt(previous, 'v1', 'v3', 'top');
      for (const nextIds of [
        terminals.slice().reverse(),
        terminals.slice(1),
        [...terminals, 'new'],
      ]) {
        const next = nextIds.length ? balanced(nextIds) : null;
        const result = reconcileViewerLayout(previous, next, viewers)!;
        expectProjection(result, next);
        expect(ids(result).slice().sort()).toEqual([...nextIds, ...viewers].sort());
        expect(reconcileViewerLayout(result, structuredClone(next), viewers)).toBe(result);
      }
    }
  });
});
