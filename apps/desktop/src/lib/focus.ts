// Keep keyboard navigation inside the current modal and restore its trigger.
export function focusDialog(node: HTMLElement, options: { restore?: () => boolean } = {}) {
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
  queueMicrotask(() => elements()[0]?.focus());
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
    destroy() {
      node.removeEventListener('keydown', key);
      if (!options.restore?.() && previous?.isConnected) previous.focus();
    },
  };
}
