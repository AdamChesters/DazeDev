// Key buttons in scrolling rows and panels. A key is pressed only by a tap that ends where it
// started, so a drag scrolls the row instead of pressing the key the finger landed on.
// Holding still repeats a key that asks for it (the arrows), as a held key would.
export const TAP_SLOP = 8;

export function installTapKey(element, { press, repeat = false, delay = 350, interval = 90 }) {
  let gesture = null;
  const stop = () => {
    if (!gesture) return;
    clearTimeout(gesture.timer);
    clearInterval(gesture.timer);
    gesture = null;
  };
  const down = (event) => {
    // Keeps focus, and the phone keyboard, on the terminal. Scrolling still works because
    // touch-action on the button allows it, and the browser then sends pointercancel.
    event.preventDefault();
    stop();
    gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, repeating: false, timer: null };
    if (!repeat) return;
    const held = gesture;
    held.timer = setTimeout(() => {
      if (gesture !== held) return;
      held.repeating = true;
      press();
      held.timer = setInterval(press, interval);
    }, delay);
  };
  const move = (event) => {
    if (!gesture || event.pointerId !== gesture.id) return;
    if (Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) > TAP_SLOP) stop();
  };
  const up = (event) => {
    if (!gesture || event.pointerId !== gesture.id) return;
    const tap = !gesture.repeating;
    stop();
    if (tap) press();
  };
  element.addEventListener("pointerdown", down);
  element.addEventListener("pointermove", move);
  element.addEventListener("pointerup", up);
  element.addEventListener("pointercancel", stop);
  return {
    // Ends a held repeat, for example when the app leaves the screen mid-press.
    cancel: stop,
    dispose: () => {
      stop();
      element.removeEventListener("pointerdown", down);
      element.removeEventListener("pointermove", move);
      element.removeEventListener("pointerup", up);
      element.removeEventListener("pointercancel", stop);
    },
  };
}
