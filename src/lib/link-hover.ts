import type { Terminal } from '@xterm/xterm';

// xterm 6 clears the hovered link on mouseleave but keeps its last cell. It
// then skips link detection when a modal closes over that same cell. There is
// no public cache-reset API; keep this version-specific access in one place.
export function restoreLinkHoverOnReentry(terminal: Terminal): () => void {
  const screen = terminal.element?.querySelector('.xterm-screen');
  const linkifier = (terminal as unknown as {
    _core?: { linkifier?: { currentLink?: unknown; _lastBufferCell?: unknown; _handleMouseMove?: (event: MouseEvent) => void } };
  })._core?.linkifier;
  if (!screen || !linkifier) return () => {};
  const root = terminal.element!.parentElement!;
  const reset = () => { linkifier._lastBufferCell = undefined; };
  // Overlay/render changes can clear the link without a boundary event.
  // Run before the app's capture-phase click guards, so their hover is ready.
  const move = (event: Event) => {
    if (screen.contains(event.target as Node) && !linkifier.currentLink) reset();
  };
  const down = (event: Event) => {
    const mouse = event as MouseEvent;
    if (mouse.button === 0 && screen.contains(mouse.target as Node) && !linkifier.currentLink) {
      reset();
      linkifier._handleMouseMove?.(mouse);
    }
  };
  root.addEventListener('mousemove', move, true);
  root.addEventListener('mousedown', down, true);
  screen.addEventListener('mouseleave', reset);
  const enter = (event: Event) => {
    reset();
    // Reevaluate immediately, even if the user clicks without moving first.
    // Call only link detection; a synthetic DOM move could reach mouse-tracking TUIs.
    linkifier._handleMouseMove?.(event as MouseEvent);
  };
  screen.addEventListener('mouseenter', enter);
  return () => {
    root.removeEventListener('mousemove', move, true);
    root.removeEventListener('mousedown', down, true);
    screen.removeEventListener('mouseleave', reset);
    screen.removeEventListener('mouseenter', enter);
  };
}
