import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("./ime-input.js", import.meta.url), "utf8");
const { installImeInput, textEdit } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);

// The textarea is its own root here: events target it, as they do in the page.
function setup() {
  const textarea = new EventTarget();
  textarea.value = "";
  let terminal = "";
  const sentData = [];
  const ime = installImeInput(textarea, textarea, (data) => {
    sentData.push(data);
    for (const c of data) terminal = c === "\x7f" ? [...terminal].slice(0, -1).join("") : terminal + c;
  });
  const fire = (type, props = {}) => {
    const event = new Event(type, { cancelable: true });
    Object.assign(event, props);
    textarea.dispatchEvent(event);
    return event;
  };
  // The keyboard changes the text, then the browser reports it.
  const type = (value) => {
    fire("beforeinput");
    textarea.value = value;
    return fire("input");
  };
  return { textarea, ime, fire, type, sent: sentData, terminal: () => terminal };
}

test("textEdit erases back to the first change, then types the rest", () => {
  assert.deepEqual(textEdit("teh", "the "), { erase: 2, insert: "he " });
  assert.deepEqual(textEdit("hello", "hello world"), { erase: 0, insert: " world" });
  assert.deepEqual(textEdit("hello world ", "hello world"), { erase: 1, insert: "" });
  assert.deepEqual(textEdit("a😀", "a"), { erase: 1, insert: "" }, "counts code points, not UTF-16 units");
});

test("typing at the end sends only the new text", () => {
  const { type, sent, terminal } = setup();
  type("h");
  type("he");
  type("hey");
  assert.deepEqual(sent, ["h", "e", "y"]);
  assert.equal(terminal(), "hey");
});

test("backspacing into an earlier word and retyping it keeps the line right", () => {
  const { type, fire, terminal } = setup();
  type("on mobile, in a workspace ");
  type("on mobile, in a workspace");
  // The keyboard reopens "workspace" for editing, then replaces it.
  fire("compositionstart");
  type("on mobile, in a works");
  type("on mobile, in a worksheet");
  fire("compositionend");
  assert.equal(terminal(), "on mobile, in a worksheet");
});

test("an edit before the end retypes the text after it, not the whole line", () => {
  const { type, sent, terminal } = setup();
  type("there's no options");
  sent.length = 0;
  type("there's no removal options");
  assert.deepEqual(sent, ["\x7f".repeat(7), "removal options"]);
  assert.equal(terminal(), "there's no removal options");
});

test("an autocorrection replaces only the corrected word", () => {
  const { type, terminal } = setup();
  type("insert teh");
  type("insert the ");
  assert.equal(terminal(), "insert the ");
});

test("xterm never sees keyboard edits", () => {
  const { fire } = setup();
  for (const type of ["compositionstart", "compositionupdate", "compositionend", "input"])
    assert.equal(fire(type).cancelBubble, true, type);
  assert.equal(fire("keydown", { keyCode: 229 }).cancelBubble, true);
  assert.equal(fire("keydown", { keyCode: 13 }).cancelBubble, false, "Enter still reaches xterm");
});

test("a key xterm sends ends the mirrored text", () => {
  const { textarea, type, fire, terminal } = setup();
  type("ls -la");
  fire("keydown", { keyCode: 13 });
  assert.equal(textarea.value, "");
  type("p");
  assert.equal(terminal(), "ls -lap", "nothing typed before Enter is erased or resent");
});

test("modifier keys keep the mirrored text", () => {
  const { textarea, type, fire } = setup();
  type("git");
  fire("keydown", { keyCode: 16 });
  assert.equal(textarea.value, "git");
});

test("text xterm removed itself is not erased from the terminal", () => {
  const { textarea, type, terminal } = setup();
  type("first line");
  textarea.value = "";
  type("x");
  assert.equal(terminal(), "first linex");
});

test("a typed line break is sent as Enter and ends the mirrored text", () => {
  const { textarea, type, sent } = setup();
  type("echo hi\n");
  assert.deepEqual(sent, ["echo hi\r"]);
  assert.equal(textarea.value, "");
});

test("reset sends pending text, then a paste or app input starts afresh", () => {
  const { textarea, ime, type, terminal } = setup();
  type("abc");
  textarea.value = "abcd";
  ime.reset();
  assert.equal(terminal(), "abcd");
  assert.equal(textarea.value, "");
  type("e");
  assert.equal(terminal(), "abcde");
});

test("dispose stops listening", () => {
  const { ime, type, sent } = setup();
  ime.dispose();
  type("x");
  assert.deepEqual(sent, []);
});
