// The phone keyboard types into xterm's hidden textarea. xterm 6 assumes each keyboard edit lands
// at the end of that text, so backspacing into an earlier word, moving the cursor or an
// autocorrection resent whole sentences or the wrong characters. This takes keyboard text away
// from xterm and keeps the terminal line equal to the textarea instead: each change is sent as
// backspaces to the first changed character, then the new text from there.
const BACKSPACE = "\x7f";
// Shift, Control, Alt and CapsLock change no text.
const MODIFIER_KEYS = new Set([16, 17, 18, 20]);

/** The backspaces and text that turn `before` into `after`, counted in code points. */
export function textEdit(before, after) {
  const from = [...before];
  const to = [...after];
  let same = 0;
  while (same < from.length && same < to.length && from[same] === to[same]) same++;
  return { erase: from.length - same, insert: to.slice(same).join("") };
}

/**
 * Listens on `root` in the capture phase, so xterm never sees keyboard text in `textarea`, and
 * passes terminal input to `send`. A key xterm sends itself (Enter, an arrow, a hardware or extra
 * key), a paste or leaving the terminal ends the mirrored text, because the terminal line no
 * longer matches it.
 */
export function installImeInput(root, textarea, send) {
  // The textarea text the terminal has been sent.
  let sent = "";
  const clear = () => {
    textarea.value = "";
    sent = "";
  };
  const sync = () => {
    const value = textarea.value;
    const { erase, insert } = textEdit(sent, value);
    sent = value;
    if (erase) send(BACKSPACE.repeat(erase));
    if (!insert) return;
    // A keyboard that types a line break ends the line, as Enter does.
    send(insert.replace(/\r?\n/g, "\r"));
    if (/[\r\n]/.test(insert)) clear();
  };
  const reset = () => {
    sync();
    clear();
  };
  const listeners = {
    // xterm empties the textarea itself on Enter, Ctrl+C and blur. Text removed that way was not
    // deleted by the keyboard, so it must not be erased from the terminal.
    beforeinput: () => {
      sent = textarea.value;
    },
    compositionstart: (event) => {
      event.stopPropagation();
      sent = textarea.value;
    },
    compositionupdate: (event) => event.stopPropagation(),
    compositionend: (event) => {
      event.stopPropagation();
      sync();
    },
    input: (event) => {
      event.stopPropagation();
      sync();
    },
    keydown: (event) => {
      // 229: the keyboard is editing the text, and the input event that follows carries the change.
      if (event.keyCode === 229) event.stopPropagation();
      else if (!MODIFIER_KEYS.has(event.keyCode)) reset();
    },
    paste: reset,
    blur: reset,
  };
  const handlers = Object.entries(listeners).map(([type, handler]) => {
    const listener = (event) => {
      if (event.target === textarea) handler(event);
    };
    root.addEventListener(type, listener, { capture: true });
    return [type, listener];
  });
  return {
    // Sends any pending keyboard text and starts afresh, before the app types into the terminal.
    reset,
    dispose: () => {
      for (const [type, listener] of handlers) root.removeEventListener(type, listener, { capture: true });
    },
  };
}
