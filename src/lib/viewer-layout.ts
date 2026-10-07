import { ids, insertAt } from './layout';
import { uid, type Layout } from './types';

function retain(node: Layout | null, allowed: ReadonlySet<string>): Layout | null {
  if (!node) return null;
  if (node.kind === 'pane') return allowed.has(node.id) ? node : null;
  const first = retain(node.first, allowed),
    second = retain(node.second, allowed);
  if (!first || !second) return first || second;
  return first === node.first && second === node.second ? node : { ...node, first, second };
}

/** Persist only terminal leaves; removing a viewer promotes its sibling. */
export function terminalProjection(
  combined: Layout | null,
  terminalIds: Iterable<string>,
): Layout | null {
  return retain(combined, new Set([...terminalIds].filter(Boolean)));
}

function equal(a: Layout | null, b: Layout | null): boolean {
  if (a === b) return true;
  if (!a || !b || a.kind !== b.kind || a.id !== b.id) return false;
  if (a.kind === 'pane' || b.kind === 'pane') return true;
  return (
    a.axis === b.axis && a.ratio === b.ratio && equal(a.first, b.first) && equal(a.second, b.second)
  );
}

type Wrapper = {
  split: Extract<Layout, { kind: 'split' }>;
  viewers: Layout;
  viewerFirst: boolean;
  anchors: string[];
};

function collect(node: Layout, terminals: ReadonlySet<string>, wrappers: Wrapper[]): string[] {
  if (node.kind === 'pane') return terminals.has(node.id) ? [node.id] : [];
  const first = collect(node.first, terminals, wrappers),
    second = collect(node.second, terminals, wrappers);
  if (first.length && !second.length)
    wrappers.push({ split: node, viewers: node.second, viewerFirst: false, anchors: first });
  else if (second.length && !first.length)
    wrappers.push({ split: node, viewers: node.first, viewerFirst: true, anchors: second });
  return [...first, ...second];
}

function anchor(node: Layout, anchors: string[]): string {
  if (node.kind === 'split' && anchors.length) {
    const first = new Set(ids(node.first)),
      second = new Set(ids(node.second));
    if (anchors.every((id) => first.has(id))) return anchor(node.first, anchors);
    if (anchors.every((id) => second.has(id))) return anchor(node.second, anchors);
  }
  return node.id;
}

function wrapAt(
  node: Layout,
  target: string,
  terminals: ReadonlySet<string>,
  wrapper: Wrapper,
): Layout {
  // Include wrappers already placed around this anchor, preserving their nesting order.
  if (terminalProjection(node, terminals)?.id === target)
    return {
      ...wrapper.split,
      first: wrapper.viewerFirst ? wrapper.viewers : node,
      second: wrapper.viewerFirst ? node : wrapper.viewers,
    };
  if (node.kind === 'pane') return node;
  return {
    ...node,
    first: wrapAt(node.first, target, terminals, wrapper),
    second: wrapAt(node.second, target, terminals, wrapper),
  };
}

/** Reconcile session-only viewers with the saved terminal tree without persisting viewer IDs. */
export function reconcileViewerLayout(
  previous: Layout | null,
  terminalLayout: Layout | null,
  viewerIds: readonly string[],
): Layout | null {
  const terminals = new Set(terminalLayout ? ids(terminalLayout).filter(Boolean) : []);
  const canonical = retain(terminalLayout, terminals);
  const viewers = [...new Set(viewerIds.filter((id) => id && !terminals.has(id)))];
  const prior = retain(previous, new Set([...terminals, ...viewers]));
  let result: Layout | null;
  if (equal(terminalProjection(prior, terminals), canonical)) {
    result = prior;
  } else if (!prior) {
    result = canonical;
  } else if (!canonical) {
    result = retain(prior, new Set(viewers));
  } else {
    const wrappers: Wrapper[] = [];
    const anchors = collect(prior, terminals, wrappers);
    result = canonical;
    if (!anchors.length) {
      result = {
        kind: 'split',
        id: uid(),
        axis: 'row',
        ratio: 0.5,
        first: canonical,
        second: prior,
      };
    } else {
      for (const wrapper of wrappers)
        result = wrapAt(result, anchor(canonical, wrapper.anchors), terminals, wrapper);
    }
  }
  const present = new Set(result ? ids(result) : []);
  for (const id of viewers) {
    if (present.has(id)) continue;
    result = result ? insertAt(result, ids(result).at(-1)!, id, 'right') : { kind: 'pane', id };
    present.add(id);
  }
  return result;
}
