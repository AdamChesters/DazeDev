import { keyboardShortcut, sameShortcut, type Shortcut } from './shortcut';

export type AppAction = 'command' | 'search' | 'fullscreen';
const key = (
  code: number,
  label: string,
  modifiers: Partial<Pick<Shortcut, 'ctrl' | 'alt' | 'shift' | 'meta'>> = {},
): Shortcut => ({
  key: code,
  ctrl: false,
  alt: false,
  shift: false,
  meta: false,
  ...modifiers,
  label,
});
export const appActions: { id: AppAction; label: string; shortcut: Shortcut }[] = [
  { id: 'command', label: 'Command palette', shortcut: key(75, 'Ctrl + K', { ctrl: true }) },
  {
    id: 'search',
    label: 'Search terminals',
    shortcut: key(70, 'Ctrl + Shift + F', { ctrl: true, shift: true }),
  },
  { id: 'fullscreen', label: 'Full screen', shortcut: key(122, 'F11') },
];
export type AppShortcuts = Partial<Record<AppAction, Shortcut>>;
export function appShortcut(saved: AppShortcuts | undefined, action: AppAction): Shortcut {
  return saved?.[action] || appActions.find((a) => a.id === action)!.shortcut;
}
// On macOS, Cmd stands in for Ctrl in a binding that uses Ctrl.
export function matchesAppShortcut(e: KeyboardEvent, binding: Shortcut, mac: boolean) {
  const pressed = keyboardShortcut(e);
  if (!pressed || pressed.key !== binding.key) return false;
  if (pressed.alt !== binding.alt || pressed.shift !== binding.shift) return false;
  if (mac && binding.ctrl && !binding.meta && !pressed.ctrl && pressed.meta) return true;
  return pressed.ctrl === binding.ctrl && pressed.meta === binding.meta;
}
export function appActionFor(
  e: KeyboardEvent,
  saved: AppShortcuts | undefined,
  mac: boolean,
): AppAction | undefined {
  return appActions.find((a) => matchesAppShortcut(e, appShortcut(saved, a.id), mac))?.id;
}
// Ctrl+letter without Shift/Alt sends a control code (Ctrl+K kills to end of
// line, nano uses many). Inside a terminal those keys belong to the terminal.
export function terminalOwnsKey(
  e: Pick<KeyboardEvent, 'ctrlKey' | 'altKey' | 'shiftKey' | 'metaKey'>,
) {
  return e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey;
}
// Returns the name of the action or voice hotkey that already uses binding.
export function shortcutConflict(
  action: AppAction,
  binding: Shortcut,
  saved: AppShortcuts | undefined,
  others: { label: string; shortcut: Shortcut }[],
): string | null {
  for (const other of appActions)
    if (other.id !== action && sameShortcut(appShortcut(saved, other.id), binding))
      return other.label;
  return others.find((other) => sameShortcut(other.shortcut, binding))?.label || null;
}
