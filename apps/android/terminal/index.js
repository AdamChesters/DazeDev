import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { Unicode11Addon } from "@xterm/addon-unicode11";
import { WebLinksAddon } from "@xterm/addon-web-links";
import { WebglAddon } from "@xterm/addon-webgl";
import "@xterm/xterm/css/xterm.css";
import "./terminal.css";
import { installTouchScroll, wheelAccumulator } from "./touch-scroll.js";
const term = new Terminal({
  cursorBlink: false,
  scrollback: 5000,
  fontSize: 14,
  // Screen reader support costs CPU on every write, so Android turns it on only for TalkBack.
  screenReaderMode: location.hash === "#a11y",
  theme: {
    background: DAZEDEV_COLORS.background,
    foreground: DAZEDEV_COLORS.foreground,
    cursor: DAZEDEV_COLORS.accent,
  },
  allowProposedApi: true,
  // OSC 8 links open through the app's checked browser path.
  linkHandler: {
    activate: (_event, uri) => bridge("openUrl", uri),
    allowNonHttpProtocols: false,
  },
});
for (const [name, value] of Object.entries(DAZEDEV_COLORS))
  document.documentElement.style.setProperty(`--${name}`, value);
const fit = new FitAddon();
term.loadAddon(fit);
term.loadAddon(new Unicode11Addon());
term.unicode.activeVersion = "11";
term.open(document.getElementById("terminal"));
// WebGL is faster; the DOM renderer remains if WebGL is missing or its context is lost.
try {
  const webgl = new WebglAddon();
  webgl.onContextLoss(() => webgl.dispose());
  term.loadAddon(webgl);
} catch {
  // DOM renderer
}
// The app injects DazeBridge only into this page's own origin and main frame.
function bridge(kind, value) {
  window.DazeBridge?.postMessage(JSON.stringify({ kind, value }));
}
// Output arrives as ArrayBuffers (or base64 text on older WebViews). Each write
// is acknowledged by byte count so the app can keep several chunks in flight.
if (window.DazeBridge)
  window.DazeBridge.onmessage = (event) => {
    let data = event.data;
    if (typeof data === "string") {
      if (data[0] !== "o") return;
      data = Uint8Array.from(atob(data.slice(1)), (c) => c.charCodeAt(0));
    } else data = new Uint8Array(data);
    const size = data.length;
    term.write(data, () => bridge("ack", size));
  };
term.loadAddon(new WebLinksAddon((_event, url) => bridge("openUrl", url)));
const mods = { Control: 0, Alt: 0, Shift: 0 };
let ready = false,
  repeat = null,
  dispatching = false;
let submission = null;
const stopRepeat = () => {
  clearTimeout(repeat);
  clearInterval(repeat);
  repeat = null;
};
const clearOnce = () => {
  for (const m in mods) if (mods[m] === 1) mods[m] = 0;
  updateMods();
};
term.onData((data) => {
  if (submission !== null) {
    submission.push(data);
    return;
  }
  // Typing after a wheel scroll first returns tmux or the app to live output.
  if (!wheeling && wheelNet > 0) returnToLive();
  if (
    !dispatching &&
    [...data].length === 1 &&
    data.codePointAt(0) >= 32 &&
    (mods.Control || mods.Alt || mods.Shift)
  ) {
    key(data);
    return;
  }
  bridge("input", data);
  clearOnce();
});
term.onBinary((data) => bridge("binary", data));
term.onResize((size) => {
  if (ready) bridge("resize", size);
});
term.onSelectionChange(() => bridge("selection", term.getSelection()));
const codes = {
  "[": 219,
  "\\": 220,
  "]": 221,
  "^": 54,
  _: 189,
  "?": 191,
  Enter: 13,
  Escape: 27,
  Tab: 9,
  Backspace: 8,
  Delete: 46,
  Insert: 45,
  ArrowUp: 38,
  ArrowDown: 40,
  ArrowLeft: 37,
  ArrowRight: 39,
  Home: 36,
  End: 35,
  PageUp: 33,
  PageDown: 34,
};
for (let i = 1; i <= 12; i++) codes[`F${i}`] = 111 + i;
function key(key, extra = {}) {
  for (const m of heldModifiers) usedModifiers.add(m);
  const keyCode = codes[key] || key.toUpperCase().charCodeAt(0);
  const options = {
    key,
    keyCode,
    which: keyCode,
    bubbles: true,
    cancelable: true,
    ctrlKey: !!mods.Control,
    altKey: !!mods.Alt,
    shiftKey: !!mods.Shift,
    ...extra,
  };
  if (options.ctrlKey && (key === "^" || key === "?")) {
    options.keyCode = options.which = key === "^" ? 54 : 56;
    options.shiftKey = false;
  }
  dispatching = true;
  try {
    term.textarea.dispatchEvent(new KeyboardEvent("keydown", options));
    term.textarea.dispatchEvent(new KeyboardEvent("keyup", options));
  } finally {
    dispatching = false;
  }
}
function updateMods() {
  for (const q of document.querySelectorAll("[data-mod]")) {
    const on = !!mods[q.dataset.mod];
    q.setAttribute("aria-pressed", String(on));
  }
  for (const m in mods) {
    const b = document.getElementById(m);
    if (!b) continue;
    b.setAttribute("aria-pressed", String(!!mods[m]));
    b.setAttribute(
      "aria-label",
      `${m} ${mods[m] === 2 ? "locked" : mods[m] === 1 ? "one shot" : "off"}`,
    );
    b.textContent = `${m === "Control" ? "Ctrl" : m}${mods[m] === 1 ? " •" : mods[m] === 2 ? " lock" : ""}`;
  }
}
const drawer = document.getElementById("keys");
const quick = document.getElementById("quick");
const container = document.getElementById("terminal");
const shownArea = document.getElementById("viewport");
let nativeViewport = null;
// The size the terminal is fitted to. An on-screen keyboard only covers the bottom of the
// view, so it keeps this size (other devices do not resize) and the view scrolls instead.
let stable = null;
let cursorOffset = 0;
function layout() {
  const width = nativeViewport?.width ?? innerWidth;
  const visible = nativeViewport?.height ?? innerHeight;
  const keyboardOnly =
    nativeViewport?.keyboard && stable && stable.width === width && visible < stable.height;
  if (!keyboardOnly) stable = { width, height: visible };
  document.documentElement.style.width = `${width}px`;
  document.documentElement.style.height = `${visible}px`;
  document.body.style.width = `${width}px`;
  document.body.style.height = `${visible}px`;
  drawer.style.maxHeight = `${visible * 0.4}px`;
  const keysHeight = drawer.hidden ? 0 : Math.ceil(drawer.getBoundingClientRect().height);
  const quickHeight = quick.hidden ? 0 : Math.ceil(quick.getBoundingClientRect().height);
  container.style.height = `${Math.max(0, stable.height - keysHeight - quickHeight)}px`;
  shownArea.style.height = `${Math.max(0, visible - keysHeight - quickHeight)}px`;
  requestAnimationFrame(() => {
    fit.fit();
    followCursor();
  });
}
// Keeps the cursor row inside the part of the terminal the keyboard leaves visible.
function followCursor() {
  const shown = shownArea.clientHeight;
  const total = container.clientHeight;
  if (shown >= total || term.rows < 1) cursorOffset = 0;
  else {
    const screen = container.querySelector(".xterm-screen");
    const rowHeight = (screen?.getBoundingClientRect().height || total) / term.rows;
    const top = 4 + term.buffer.active.cursorY * rowHeight;
    const bottom = top + rowHeight + 4;
    if (bottom > cursorOffset + shown) cursorOffset = bottom - shown;
    if (top < cursorOffset) cursorOffset = top;
    cursorOffset = Math.max(0, Math.min(total - shown, cursorOffset));
  }
  container.style.transform = cursorOffset ? `translateY(${-cursorOffset}px)` : "";
}
term.onCursorMove(followCursor);
addEventListener("resize", layout);
new ResizeObserver(layout).observe(drawer);
const heldModifiers = new Set(),
  usedModifiers = new Set();
let modifierEpoch = 0;
for (const m in mods) {
  const b = document.createElement("button");
  b.id = m;
  b.textContent = m;
  b.title =
    "Tap for one key; tap again to lock; hold while pressing another key";
  let previous = 0,
    pressedAt = 0,
    epoch = 0;
  b.onpointerdown = (e) => {
    e.preventDefault();
    previous = mods[m];
    pressedAt = performance.now();
    epoch = modifierEpoch;
    heldModifiers.add(m);
    usedModifiers.delete(m);
    mods[m] = 2;
    updateMods();
  };
  b.onpointerup = () => {
    heldModifiers.delete(m);
    if (epoch !== modifierEpoch) return;
    mods[m] =
      usedModifiers.has(m) || performance.now() - pressedAt >= 300
        ? previous
        : (previous + 1) % 3;
    updateMods();
  };
  b.onpointercancel = () => {
    heldModifiers.delete(m);
    if (epoch === modifierEpoch) mods[m] = previous;
    updateMods();
  };
  b.onkeydown = (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      mods[m] = (mods[m] + 1) % 3;
      updateMods();
    }
  };
  drawer.append(b);
}
const labels = {
  Escape: "Esc",
  Backspace: "Bksp",
  Delete: "Del",
  Insert: "Ins",
  PageUp: "PgUp",
  PageDown: "PgDn",
};
const keys = [
  "Escape",
  "Tab",
  "Enter",
  "Backspace",
  "Delete",
  "Insert",
  "ArrowUp",
  "ArrowDown",
  "ArrowLeft",
  "ArrowRight",
  "Home",
  "End",
  "PageUp",
  "PageDown",
  ...Array.from({ length: 12 }, (_, i) => `F${i + 1}`),
  "c",
  "d",
  "z",
  "r",
  "a",
  "e",
  "w",
  "u",
  "k",
  "b",
  "f",
  "p",
  "n",
  "[",
  "\\",
  "]",
  "?",
];
for (const k of keys) {
  const b = document.createElement("button");
  b.textContent = labels[k] || k.replace("Arrow", "");
  b.setAttribute("aria-label", k);
  b.onpointerdown = (e) => {
    e.preventDefault();
    stopRepeat();
    key(k);
    if (k.startsWith("Arrow"))
      repeat = setTimeout(() => {
        repeat = setInterval(() => key(k), 90);
      }, 350);
  };
  b.onpointerup = stopRepeat;
  b.onpointercancel = stopRepeat;
  b.onkeydown = (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      key(k);
    }
  };
  drawer.append(b);
}
// Extra keys above the phone keyboard. Ctrl and Alt apply to the next key only.
for (const [label, k, name] of [
  ["Esc", "Escape", "Escape"],
  ["Tab", "Tab", "Tab"],
  ["Ctrl", "Control", "Control, next key only"],
  ["Alt", "Alt", "Alt, next key only"],
  ["←", "ArrowLeft", "Left"],
  ["↑", "ArrowUp", "Up"],
  ["↓", "ArrowDown", "Down"],
  ["→", "ArrowRight", "Right"],
  ["|", "|", "Pipe"],
  ["~", "~", "Tilde"],
  ["/", "/", "Slash"],
  ["-", "-", "Dash"],
]) {
  const b = document.createElement("button");
  b.textContent = label;
  b.setAttribute("aria-label", name);
  if (k === "Control" || k === "Alt") {
    b.dataset.mod = k;
    b.setAttribute("aria-pressed", "false");
    b.onpointerdown = (e) => {
      e.preventDefault();
      mods[k] = mods[k] ? 0 : 1;
      updateMods();
    };
  } else if (k.length === 1) {
    b.onpointerdown = (e) => {
      e.preventDefault();
      const ctrl = !!mods.Control,
        alt = !!mods.Alt;
      for (const m in mods) mods[m] = 0;
      updateMods();
      let data = k;
      if (ctrl && k === "-") data = "\x1f";
      if (alt) data = "\x1b" + data;
      bridge("input", data);
    };
  } else
    b.onpointerdown = (e) => {
      e.preventDefault();
      stopRepeat();
      key(k);
      clearOnce();
      if (k.startsWith("Arrow"))
        repeat = setTimeout(() => {
          repeat = setInterval(() => key(k), 90);
        }, 350);
    };
  b.onpointerup = stopRepeat;
  b.onpointercancel = stopRepeat;
  quick.append(b);
}
new ResizeObserver(layout).observe(quick);
window.addEventListener("blur", () => window.daze.clear());
window.daze = {
  keys: (visible) => {
    drawer.hidden = !visible;
    layout();
  },
  clear: () => {
    stopRepeat();
    modifierEpoch++;
    heldModifiers.clear();
    usedModifiers.clear();
    for (const m in mods) mods[m] = 0;
    updateMods();
    term.blur();
  },
  paste: (text) => {
    returnToLive();
    term.paste(text);
  },
  submit: (text) => {
    if (!ready || typeof text !== "string" || !text.trim()) return false;
    returnToLive();
    submission = [];
    let payload;
    try {
      // xterm supplies the current bracketed-paste mode and newline conversion.
      term.paste(text);
      payload = submission.join("") + "\r";
    } finally {
      submission = null;
    }
    // The app sends the returned payload to the terminal.
    clearOnce();
    return payload;
  },
  key,
  selectAll: () => term.selectAll(),
  copy: () => bridge("copy", term.getSelection()),
  focus: () => term.focus(),
  font: (scale) => {
    term.options.fontSize = Math.min(28, Math.max(14, 14 * scale));
    fit.fit();
  },
  viewport: (width, height, keyboard) => {
    // Android may lay out the WebView after its first document measurement.
    nativeViewport = {
      width: width / devicePixelRatio,
      height: height / devicePixelRatio,
      keyboard: !!keyboard,
    };
    layout();
  },
  dispose: () => {
    disposeTouchScroll();
    stopRepeat();
    term.dispose();
  },
};
new ResizeObserver(() => fit.fit()).observe(
  document.getElementById("terminal"),
);
requestAnimationFrame(() => {
  layout();
  fit.fit();
  ready = true;
  bridge("ready", { cols: term.cols, rows: term.rows });
});

const screen = document.querySelector(".xterm-screen");
let selectionStart = null,
  selectionEnd = null;
const handles = ["start", "end"].map((name) => {
  const button = document.createElement("button");
  button.className = "selection-handle";
  button.hidden = true;
  button.setAttribute("aria-label", `${name} of terminal selection`);
  button.textContent = name === "start" ? "Start" : "End";
  document.body.append(button);
  return button;
});
function cell(event) {
  const box = screen.getBoundingClientRect();
  return (
    Math.max(
      0,
      term.buffer.active.viewportY +
        Math.min(
          term.rows - 1,
          Math.max(
            0,
            Math.floor((event.clientY - box.top) / (box.height / term.rows)),
          ),
        ),
    ) *
      term.cols +
    Math.min(
      term.cols - 1,
      Math.max(
        0,
        Math.floor((event.clientX - box.left) / (box.width / term.cols)),
      ),
    )
  );
}
function selectRange() {
  if (selectionStart === null || selectionEnd === null) return;
  const start = Math.min(selectionStart, selectionEnd),
    end = Math.max(selectionStart, selectionEnd) + 1;
  term.select(start % term.cols, Math.floor(start / term.cols), end - start);
  const box = screen.getBoundingClientRect();
  [selectionStart, selectionEnd].forEach((point, i) => {
    const handle = handles[i];
    handle.hidden = false;
    handle.style.left = `${Math.max(0, Math.min(innerWidth - 48, box.left + ((point % term.cols) * box.width) / term.cols))}px`;
    handle.style.top = `${Math.max(0, Math.min(box.bottom - 48, box.top + ((Math.floor(point / term.cols) - term.buffer.active.viewportY) * box.height) / term.rows))}px`;
  });
}
for (const [i, handle] of handles.entries()) {
  handle.onpointerdown = (e) => {
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);
  };
  handle.onpointermove = (e) => {
    if (!handle.hasPointerCapture(e.pointerId)) return;
    e.preventDefault();
    if (i === 0) selectionStart = cell(e);
    else selectionEnd = cell(e);
    selectRange();
  };
  handle.onkeydown = (e) => {
    if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key))
      return;
    e.preventDefault();
    const delta = {
      ArrowLeft: -1,
      ArrowRight: 1,
      ArrowUp: -term.cols,
      ArrowDown: term.cols,
    }[e.key];
    if (i === 0) selectionStart = Math.max(0, selectionStart + delta);
    else selectionEnd = Math.max(0, selectionEnd + delta);
    selectRange();
  };
}
// Touch scrolling never puts the shared tmux pane into copy mode from here.
// With mouse tracking on (tmux mouse on), a drag becomes wheel events, as on
// desktop: tmux passes them to apps that use the mouse and scrolls otherwise.
// Wheel events are never sent with mouse tracking off, because xterm would turn
// them into arrow keys.
const wheelRows = wheelAccumulator(3);
let wheelNet = 0,
  wheeling = false,
  hintAt = 0,
  scrolledShown = false;
function reportScrolled() {
  const buffer = term.buffer.active;
  const scrolled = wheelNet > 0 || buffer.viewportY < buffer.baseY;
  if (scrolled !== scrolledShown) {
    scrolledShown = scrolled;
    bridge("scrolled", scrolled);
  }
}
function wheel(direction, x, y, count) {
  if (term.modes.mouseTrackingMode === "none") return;
  wheeling = true;
  try {
    for (let i = 0; i < count; i++)
      screen.dispatchEvent(
        new WheelEvent("wheel", {
          deltaY: direction,
          deltaMode: WheelEvent.DOM_DELTA_LINE,
          clientX: x,
          clientY: y,
          bubbles: true,
          cancelable: true,
        }),
      );
  } finally {
    wheeling = false;
  }
  wheelNet = Math.max(0, wheelNet - direction * count);
  reportScrolled();
}
function returnToLive() {
  if (wheelNet > 0 && term.modes.mouseTrackingMode !== "none") {
    const box = screen.getBoundingClientRect();
    wheel(1, box.left + box.width / 2, box.top + box.height / 2, Math.min(wheelNet, 500));
  }
  wheelNet = 0;
  wheelRows.reset();
  reportScrolled();
}
term.onScroll(reportScrolled);
const disposeTouchScroll = installTouchScroll(screen, {
  lineHeight: () => screen.getBoundingClientRect().height / term.rows,
  scroll: (lines, event) => {
    window.daze.clearSelection();
    if (term.modes.mouseTrackingMode !== "none") {
      const events = wheelRows.add(lines);
      if (events) {
        wheel(Math.sign(events), event.clientX, event.clientY, Math.abs(events));
      }
    } else if (term.buffer.active.type === "alternate") {
      const now = Date.now();
      if (now - hintAt > 15000) {
        hintAt = now;
        bridge("hint", "To scroll this terminal, turn on tmux mouse in Settings.");
      }
    } else {
      term.scrollLines(lines);
      reportScrolled();
    }
  },
  select: (event) => {
    selectionStart = selectionEnd = cell(event);
    selectRange();
  },
});
screen.addEventListener("contextmenu", (event) => {
  event.preventDefault();
});
term.onSelectionChange(() => {
  if (!term.hasSelection()) handles.forEach((handle) => (handle.hidden = true));
});

screen.addEventListener(
  "mousedown",
  (event) => {
    if (handles.some((handle) => !handle.hidden)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  },
  true,
);
window.daze.clearSelection = () => {
  selectionStart = selectionEnd = null;
  term.clearSelection();
  handles.forEach((handle) => (handle.hidden = true));
};
window.daze.bottom = () => {
  returnToLive();
  term.scrollToBottom();
  // Also leaves tmux copy mode if another client or tmux itself entered it.
  bridge("scrollBottom", null);
};
