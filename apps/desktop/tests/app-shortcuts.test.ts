import { expect, it } from 'vitest';
import {
  appActionFor,
  appShortcut,
  shortcutConflict,
  terminalOwnsKey,
} from '../src/lib/app-shortcuts';
import { defaultShortcut } from '../src/lib/shortcut';

const key = (code: string, mods: Partial<KeyboardEvent> = {}) =>
  ({
    code,
    ctrlKey: false,
    altKey: false,
    shiftKey: false,
    metaKey: false,
    ...mods,
  }) as KeyboardEvent;

it('uses saved bindings and falls back to defaults', () => {
  expect(appActionFor(key('KeyK', { ctrlKey: true }), undefined, false)).toBe('command');
  expect(appActionFor(key('KeyF', { ctrlKey: true, shiftKey: true }), undefined, false)).toBe(
    'search',
  );
  expect(appActionFor(key('F11'), undefined, false)).toBe('fullscreen');
  const saved = { command: { ...appShortcut(undefined, 'command'), key: 80, label: 'Ctrl + P' } };
  expect(appActionFor(key('KeyK', { ctrlKey: true }), saved, false)).toBeUndefined();
  expect(appActionFor(key('KeyP', { ctrlKey: true }), saved, false)).toBe('command');
});
it('accepts Cmd for Ctrl only on macOS', () => {
  expect(appActionFor(key('KeyK', { metaKey: true }), undefined, true)).toBe('command');
  expect(appActionFor(key('KeyK', { metaKey: true }), undefined, false)).toBeUndefined();
});
it('leaves plain Ctrl+letter to the terminal', () => {
  expect(terminalOwnsKey(key('KeyK', { ctrlKey: true }))).toBe(true);
  expect(terminalOwnsKey(key('KeyF', { ctrlKey: true, shiftKey: true }))).toBe(false);
  expect(terminalOwnsKey(key('KeyK', { metaKey: true }))).toBe(false);
});
it('names the action or voice hotkey a new binding would clash with', () => {
  const search = appShortcut(undefined, 'search');
  expect(shortcutConflict('command', search, undefined, [])).toBe('Search terminals');
  expect(shortcutConflict('search', search, undefined, [])).toBeNull();
  expect(
    shortcutConflict('command', defaultShortcut, undefined, [
      { label: 'Terminal dictation', shortcut: defaultShortcut },
    ]),
  ).toBe('Terminal dictation');
});
