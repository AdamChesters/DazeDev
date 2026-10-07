export interface Shortcut {
  key: number;
  ctrl: boolean;
  alt: boolean;
  shift: boolean;
  meta: boolean;
  label: string;
}
export const defaultShortcut: Shortcut = {
  key: 32,
  ctrl: true,
  shift: true,
  alt: false,
  meta: false,
  label: 'Ctrl + Shift + Space',
};
export function keyboardShortcut(e: KeyboardEvent): Shortcut | null {
  const codes: Record<string, number> = {
    Space: 32,
    Enter: 13,
    Tab: 9,
    Backspace: 8,
    Escape: 27,
    ArrowLeft: 37,
    ArrowUp: 38,
    ArrowRight: 39,
    ArrowDown: 40,
    Insert: 45,
    Delete: 46,
    Home: 36,
    End: 35,
    PageUp: 33,
    PageDown: 34,
    MediaPlayPause: 179,
    MediaTrackNext: 176,
    MediaTrackPrevious: 177,
    MediaStop: 178,
    AudioVolumeMute: 173,
    AudioVolumeDown: 174,
    AudioVolumeUp: 175,
  };
  const key =
    codes[e.code] ||
    (/^Key[A-Z]$/.test(e.code)
      ? e.code.charCodeAt(3)
      : /^Digit[0-9]$/.test(e.code)
        ? e.code.charCodeAt(5)
        : /^F([1-9]|1[0-9]|2[0-4])$/.test(e.code)
          ? 111 + Number(e.code.slice(1))
          : 0);
  if (!key) return null;
  return {
    key,
    ctrl: e.ctrlKey,
    alt: e.altKey,
    shift: e.shiftKey,
    meta: e.metaKey,
    label: [
      e.ctrlKey ? 'Ctrl' : '',
      e.altKey ? 'Alt' : '',
      e.shiftKey ? 'Shift' : '',
      e.metaKey ? 'Win' : '',
      e.code.replace(/^Key|^Digit/, ''),
    ]
      .filter(Boolean)
      .join(' + '),
  };
}
export function matches(e: KeyboardEvent, shortcut: Shortcut) {
  const key = keyboardShortcut(e);
  return (
    !!key &&
    key.key === shortcut.key &&
    key.ctrl === shortcut.ctrl &&
    key.alt === shortcut.alt &&
    key.shift === shortcut.shift &&
    key.meta === shortcut.meta
  );
}

export const defaultCommandShortcut: Shortcut = {
  ...defaultShortcut,
  alt: true,
  label: 'Ctrl + Alt + Shift + Space',
};
export function sameShortcut(a: Shortcut, b: Shortcut) {
  return (
    a.key === b.key &&
    a.ctrl === b.ctrl &&
    a.alt === b.alt &&
    a.shift === b.shift &&
    a.meta === b.meta
  );
}
export function mouseMatches(e: MouseEvent, binding: Shortcut) {
  return (
    [1, 4, 2, 5, 6][e.button] === binding.key &&
    e.ctrlKey === binding.ctrl &&
    e.altKey === binding.alt &&
    e.shiftKey === binding.shift &&
    e.metaKey === binding.meta
  );
}
