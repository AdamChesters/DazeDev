// Browser APIs jsdom does not provide.
import { vi } from 'vitest';

export const clipboard = {
  writeText: vi.fn(async (_text: string) => {}),
  readText: vi.fn(async () => ''),
  read: vi.fn(async () => []),
};
export function installDom() {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  globalThis.IntersectionObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return [];
    }
  } as unknown as typeof IntersectionObserver;
  window.matchMedia ??= ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia;
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true });
  // jsdom has no layout, so every element would count as hidden.
  Element.prototype.getClientRects = function () {
    return (this.isConnected ? [{}] : []) as unknown as DOMRectList;
  };
}

/** A paste event carrying plain text, as the browser dispatches it. */
export function pasteEvent(text: string) {
  const event = new Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'clipboardData', {
    value: { files: [], getData: (type: string) => (type === 'text/plain' ? text : '') },
  });
  return event;
}
