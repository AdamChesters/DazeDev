// Keep keyboard navigation inside the current modal and restore its trigger.
// When `key` changes (one dialog element showing a different modal), focus
// moves to the first control of the new content.
export function focusDialog(
  node: HTMLElement,
  options: { restore?: () => boolean; key?: string } = {},
) {
  const previous = document.activeElement as HTMLElement | null;
  const elements = () =>
    Array.from(
      node.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, a[href], [tabindex="0"]',
      ),
    ).filter((e) => {
      if (!e.getClientRects().length) return false;
      for (let parent = e.parentElement; parent; parent = parent.parentElement) {
        if (
          parent instanceof HTMLDetailsElement &&
          !parent.open &&
          !parent.querySelector('summary')?.contains(e)
        )
          return false;
      }
      return true;
    });
  const focusFirst = () => {
    const first = elements()[0];
    if (first) first.focus();
    else node.focus();
  };
  queueMicrotask(focusFirst);
  let currentKey = options.key;
  const key = (e: KeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const all = elements(),
      first = all[0],
      last = all.at(-1);
    if (!first) {
      e.preventDefault();
      node.focus();
      return;
    }
    if (
      e.shiftKey &&
      (document.activeElement === first || !node.contains(document.activeElement))
    ) {
      e.preventDefault();
      last?.focus();
    } else if (
      !e.shiftKey &&
      (document.activeElement === last || !node.contains(document.activeElement))
    ) {
      e.preventDefault();
      first.focus();
    }
  };
  node.addEventListener('keydown', key);
  return {
    update(next: { restore?: () => boolean; key?: string } = {}) {
      options = next;
      if (next.key === currentKey) return;
      currentKey = next.key;
      // Wait for the new content to render. Focus left with the old content,
      // unless the new content already focused its own control.
      requestAnimationFrame(() => {
        const active = document.activeElement;
        if (!active || active === node || !node.contains(active)) focusFirst();
      });
    },
    destroy() {
      node.removeEventListener('keydown', key);
      if (!options.restore?.() && previous?.isConnected) previous.focus();
    },
  };
}
