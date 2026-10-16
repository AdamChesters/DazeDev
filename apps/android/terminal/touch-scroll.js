export function installTouchScroll(element, { lineHeight, scroll, select }) {
  let gesture = null;
  let timer = null;
  const stop = () => {
    clearTimeout(timer);
    timer = null;
    gesture = null;
  };
  const down = (event) => {
    if (event.pointerType !== "touch") return;
    if (gesture) {
      stop();
      return;
    }
    gesture = { id: event.pointerId, x: event.clientX, y: event.clientY,
      lastY: event.clientY, remainder: 0, scrolling: false, selecting: false };
    timer = setTimeout(() => {
      if (!gesture) return;
      gesture.selecting = true;
      select(event);
    }, 500);
  };
  const move = (event) => {
    if (!gesture || event.pointerId !== gesture.id || gesture.selecting) return;
    if (!gesture.scrolling) {
      if (Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) <= 8) return;
      clearTimeout(timer);
      if (Math.abs(event.clientY - gesture.y) <= Math.abs(event.clientX - gesture.x)) {
        stop();
        return;
      }
      gesture.scrolling = true;
    }
    event.preventDefault();
    gesture.remainder += gesture.lastY - event.clientY;
    gesture.lastY = event.clientY;
    const height = Math.max(1, lineHeight());
    const lines = Math.trunc(gesture.remainder / height);
    if (lines) {
      gesture.remainder -= lines * height;
      scroll(lines, event);
    }
  };
  const up = (event) => {
    if (gesture?.id === event.pointerId) stop();
  };
  element.addEventListener("pointerdown", down);
  element.addEventListener("pointermove", move, { passive: false });
  element.addEventListener("pointerup", up);
  element.addEventListener("pointercancel", up);
  return () => {
    stop();
    element.removeEventListener("pointerdown", down);
    element.removeEventListener("pointermove", move);
    element.removeEventListener("pointerup", up);
    element.removeEventListener("pointercancel", up);
  };
}

// Turns dragged rows into whole wheel steps and keeps the remainder.
// Negative rows and steps point into history (wheel up).
export function wheelAccumulator(rowsPerWheel = 3) {
  let rows = 0;
  return {
    add(lines) {
      rows += lines;
      const steps = Math.trunc(rows / rowsPerWheel);
      rows -= steps * rowsPerWheel;
      return steps || 0;
    },
    reset() {
      rows = 0;
    },
  };
}
