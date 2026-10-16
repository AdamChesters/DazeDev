import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("./touch-scroll.js", import.meta.url), "utf8");
const { installTouchScroll, wheelAccumulator } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
function setup() {
  const element = new EventTarget();
  const lines = [];
  let selections = 0;
  const dispose = installTouchScroll(element, {
    lineHeight: () => 20, scroll: (n) => lines.push(n), select: () => selections++,
  });
  const send = (type, x, y, pointerType = "touch", pointerId = 1) => {
    const event = new Event(type, { cancelable: true });
    Object.assign(event, { clientX: x, clientY: y, pointerType, pointerId });
    element.dispatchEvent(event);
    return event;
  };
  return { lines, send, dispose, selections: () => selections };
}
test("drag down scrolls into history; reverse drag returns toward live output", () => {
  const f = setup();
  f.send("pointerdown", 50, 100);
  assert.equal(f.send("pointermove", 50, 200).defaultPrevented, true);
  f.send("pointermove", 50, 140);
  f.send("pointerup", 50, 140);
  assert.deepEqual(f.lines, [-5, 3]);
  f.dispose();
});
test("small moves accumulate into whole lines", () => {
  const f = setup();
  f.send("pointerdown", 50, 100);
  for (const y of [109, 115, 122, 133, 140]) f.send("pointermove", 50, y);
  assert.deepEqual(f.lines, [-1, -1]);
  f.dispose();
});
test("mouse, horizontal drag and cancelled gestures do not scroll", () => {
  const f = setup();
  f.send("pointerdown", 50, 100, "mouse");
  f.send("pointermove", 50, 200, "mouse");
  f.send("pointerdown", 50, 100);
  f.send("pointermove", 100, 105);
  f.send("pointerdown", 50, 100);
  f.send("pointercancel", 50, 100);
  f.send("pointermove", 50, 200);
  assert.deepEqual(f.lines, []);
  f.dispose();
});
test("long press still selects text and does not scroll", async () => {
  const f = setup();
  f.send("pointerdown", 50, 100);
  await new Promise((resolve) => setTimeout(resolve, 520));
  f.send("pointermove", 50, 200);
  assert.equal(f.selections(), 1);
  assert.deepEqual(f.lines, []);
  f.dispose();
});
test("wheel steps send one event per three dragged rows and keep the remainder", () => {
  const wheel = wheelAccumulator(3);
  assert.deepEqual([-1, -1, -1, -1, -1].map((n) => wheel.add(n)), [0, 0, -1, 0, 0]);
  assert.equal(wheel.add(-7), -3);
  assert.equal(wheel.add(4), 1);
  wheel.reset();
  assert.equal(wheel.add(2), 0);
  assert.equal(wheel.add(1), 1);
});
test("scroll callback receives the touch event for wheel coordinates", () => {
  const element = new EventTarget();
  const seen = [];
  installTouchScroll(element, { lineHeight: () => 10, scroll: (n, e) => seen.push([n, e.clientX, e.clientY]), select: () => {} });
  const send = (type, x, y) => {
    const event = new Event(type, { cancelable: true });
    Object.assign(event, { clientX: x, clientY: y, pointerType: "touch", pointerId: 1 });
    element.dispatchEvent(event);
  };
  send("pointerdown", 20, 100);
  send("pointermove", 20, 140);
  assert.deepEqual(seen, [[-4, 20, 140]]);
});
