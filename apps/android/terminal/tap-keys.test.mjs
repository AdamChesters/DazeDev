import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("./tap-keys.js", import.meta.url), "utf8");
const { installTapKey, TAP_SLOP } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function setup(options = {}) {
  const element = new EventTarget();
  let presses = 0;
  const handle = installTapKey(element, { press: () => presses++, delay: 30, interval: 10, ...options });
  const send = (type, x = 10, y = 10, pointerId = 1) => {
    const event = new Event(type, { cancelable: true });
    Object.assign(event, { clientX: x, clientY: y, pointerId });
    element.dispatchEvent(event);
    return event;
  };
  return { send, handle, presses: () => presses };
}

test("a tap presses the key once, on release", () => {
  const { send, presses } = setup();
  assert.equal(send("pointerdown").defaultPrevented, true, "keeps focus on the terminal");
  assert.equal(presses(), 0, "nothing fires on touch down");
  send("pointerup");
  assert.equal(presses(), 1);
});

test("a small wobble is still a tap", () => {
  const { send, presses } = setup();
  send("pointerdown", 10, 10);
  send("pointermove", 10 + TAP_SLOP - 1, 10);
  send("pointerup", 10 + TAP_SLOP - 1, 10);
  assert.equal(presses(), 1);
});

test("a sideways drag scrolls and presses nothing", () => {
  const { send, presses } = setup();
  send("pointerdown", 100, 10);
  send("pointermove", 80, 10);
  send("pointermove", 20, 10);
  send("pointerup", 20, 10);
  assert.equal(presses(), 0);
});

test("the browser taking over the gesture presses nothing", () => {
  const { send, presses } = setup();
  send("pointerdown");
  send("pointercancel");
  send("pointerup");
  assert.equal(presses(), 0);
});

test("another finger's release does not press", () => {
  const { send, presses } = setup();
  send("pointerdown", 10, 10, 1);
  send("pointerup", 10, 10, 2);
  assert.equal(presses(), 0);
  send("pointerup", 10, 10, 1);
  assert.equal(presses(), 1);
});

test("holding still repeats a repeating key, and release adds no extra press", async () => {
  const { send, presses } = setup({ repeat: true });
  send("pointerdown");
  await sleep(80);
  const held = presses();
  assert.ok(held >= 2, `expected repeats while held, got ${held}`);
  send("pointerup");
  await sleep(30);
  assert.equal(presses(), held);
});

test("a drag during a hold stops the repeat", async () => {
  const { send, presses } = setup({ repeat: true });
  send("pointerdown", 10, 10);
  await sleep(50);
  send("pointermove", 60, 10);
  const held = presses();
  await sleep(40);
  assert.equal(presses(), held);
});

test("a key without repeat never fires while held", async () => {
  const { send, presses } = setup();
  send("pointerdown");
  await sleep(60);
  assert.equal(presses(), 0);
  send("pointerup");
  assert.equal(presses(), 1);
});

test("cancel ends a held repeat and dispose removes the handlers", async () => {
  const { send, handle, presses } = setup({ repeat: true });
  send("pointerdown");
  await sleep(50);
  handle.cancel();
  const held = presses();
  await sleep(30);
  assert.equal(presses(), held);
  handle.dispose();
  send("pointerdown");
  send("pointerup");
  assert.equal(presses(), held);
});
