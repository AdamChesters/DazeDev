import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { Unicode11Addon } from "@xterm/addon-unicode11";
import "@xterm/xterm/css/xterm.css";
import "./terminal.css";
const term = new Terminal({
  cursorBlink: false,
  scrollback: 5000,
  fontSize: 14,
  screenReaderMode: true,
  theme: {
    background: DAZEDEV_COLORS.background,
    foreground: DAZEDEV_COLORS.foreground,
    cursor: DAZEDEV_COLORS.accent,
  },
  allowProposedApi: true,
});
for (const [name, value] of Object.entries(DAZEDEV_COLORS))
  document.documentElement.style.setProperty(`--${name}`, value);
const fit = new FitAddon();
term.loadAddon(fit);
term.loadAddon(new Unicode11Addon());
term.unicode.activeVersion = "11";
term.open(document.getElementById("terminal"));
const bridge = (kind, value) =>
  window.DazeBridge.event(JSON.stringify({ kind, value }));
const mods = { Control: 0, Alt: 0, Shift: 0 };
let ready = false,
  repeat = null,
  dispatching = false;
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
const container = document.getElementById("terminal");
let nativeViewport = null;
function layout() {
  const width = nativeViewport?.width ?? innerWidth;
  const height = nativeViewport?.height ?? innerHeight;
  document.documentElement.style.width = `${width}px`;
  document.documentElement.style.height = `${height}px`;
  document.body.style.width = `${width}px`;
  document.body.style.height = `${height}px`;
  drawer.style.maxHeight = `${height * 0.4}px`;
  const keysHeight = drawer.hidden ? 0 : Math.ceil(drawer.getBoundingClientRect().height);
  container.style.height = `${Math.max(0, height - keysHeight)}px`;
  requestAnimationFrame(() => fit.fit());
}
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
window.addEventListener("blur", () => window.daze.clear());
window.daze = {
  write: (base64, id) => {
    const data = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    term.write(data, () => bridge("ack", id));
  },
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
  paste: (text) => term.paste(text),
  key,
  selectAll: () => term.selectAll(),
  copy: () => bridge("copy", term.getSelection()),
  focus: () => term.focus(),
  font: (scale) => {
    term.options.fontSize = Math.min(28, Math.max(14, 14 * scale));
    fit.fit();
  },
  viewport: (width, height) => {
    // Android may lay out the WebView after its first document measurement.
    nativeViewport = {width: width / devicePixelRatio, height: height / devicePixelRatio};
    layout();
  },
  dispose: () => {
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
let selectionTimer,
  selectionStart = null,
  selectionEnd = null,
  touchStart = null;
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
screen.addEventListener("pointerdown", (event) => {
  if (event.pointerType !== "touch") return;
  touchStart = { x: event.clientX, y: event.clientY };
  selectionTimer = setTimeout(() => {
    selectionStart = selectionEnd = cell(event);
    selectRange();
  }, 500);
});
screen.addEventListener("pointermove", (event) => {
  if (
    touchStart &&
    Math.hypot(event.clientX - touchStart.x, event.clientY - touchStart.y) > 8
  )
    clearTimeout(selectionTimer);
});
for (const name of ["pointerup", "pointercancel"])
  screen.addEventListener(name, () => {
    clearTimeout(selectionTimer);
    touchStart = null;
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
window.daze.bottom = () => term.scrollToBottom();
