import test from "node:test";
import assert from "node:assert/strict";
import { fontChoice, fontFamily } from "./fonts.js";

test("the bundled font is the default and system is the only other choice", () => {
  assert.equal(fontChoice(""), "cascadia");
  assert.equal(fontChoice("?font=system"), "system");
  assert.equal(fontChoice("?font=anything"), "cascadia");
});

test("the bundled font falls back to the phone's fixed-width font", () => {
  assert.equal(fontFamily("cascadia", "monospace"), '"Cascadia Mono", monospace');
  assert.equal(fontFamily("system", "monospace"), "monospace");
});
