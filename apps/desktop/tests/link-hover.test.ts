import { expect, it, vi } from 'vitest';
import type { Terminal } from '@xterm/xterm';
import { privateLinkifier, restoreLinkHoverOnReentry } from '../src/lib/link-hover';

const target = () => ({ addEventListener: vi.fn(), removeEventListener: vi.fn(), contains: () => true });
function terminal(linkifier: unknown) {
  const screen = target();
  const root = target();
  return {
    term: {
      _core: { linkifier },
      element: { querySelector: () => screen, parentElement: root },
    } as unknown as Terminal,
    screen,
  };
}

it('uses the private linkifier only when it has the expected shape', () => {
  class Linkifier {
    get currentLink() {
      return undefined;
    }
    _handleMouseMove() {}
  }
  expect(privateLinkifier(terminal(new Linkifier()).term)).toBeDefined();
  expect(privateLinkifier(terminal({ currentLink: undefined }).term)).toBeUndefined();
  expect(privateLinkifier(terminal(undefined).term)).toBeUndefined();
  expect(privateLinkifier({} as Terminal)).toBeUndefined();
});
it('does nothing on an unknown xterm version', () => {
  const { term, screen } = terminal({ renamed: true });
  const stop = restoreLinkHoverOnReentry(term);
  expect(screen.addEventListener).not.toHaveBeenCalled();
  expect(stop).not.toThrow();
});
it('does not throw if the private method fails', () => {
  const failing = {
    currentLink: undefined,
    _handleMouseMove: () => {
      throw new Error('changed');
    },
  };
  const { term, screen } = terminal(failing);
  restoreLinkHoverOnReentry(term);
  const enter = screen.addEventListener.mock.calls.find(([name]) => name === 'mouseenter')![1];
  expect(() => enter({})).not.toThrow();
});
