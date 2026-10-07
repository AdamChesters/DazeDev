import { expect, it, vi } from 'vitest';
import { VoiceCaptureKeys } from '../src/lib/voice-capture-keys';
const key = (name: string, changes = {}) => ({
  key: name,
  repeat: false,
  isComposing: false,
  keyCode: 0,
  ctrlKey: false,
  altKey: false,
  shiftKey: false,
  metaKey: false,
  ...changes,
});
it.each(['dictation', 'command'])(
  'Enter finishes %s capture exactly once without leaking a repeat after finalization',
  () => {
    const keys = new VoiceCaptureKeys();
    expect(keys.down(key('Enter'), 'listening', true)).toEqual({ consume: true, action: 'finish' });
    expect(keys.down(key('Enter', { repeat: true }), 'transcribing', true)).toEqual({
      consume: true,
    });
    expect(keys.down(key('Enter', { repeat: true }), 'idle', true)).toEqual({ consume: true });
    expect(keys.up('Enter')).toBe(true);
    expect(keys.down(key('Enter'), 'idle', true)).toEqual({ consume: false });
  },
);
it.each(['loading', 'listening'])('Escape abandons %s capture and owns release', (status) => {
  const keys = new VoiceCaptureKeys();
  expect(keys.down(key('Escape'), status, true)).toEqual({ consume: true, action: 'cancel' });
  expect(keys.down(key('Escape', { repeat: true }), 'idle', true)).toEqual({ consume: true });
  expect(keys.up('Escape')).toBe(true);
  expect(keys.up('Escape')).toBe(false);
});
it('does not interpret modified or composing keys as capture controls', () => {
  const keys = new VoiceCaptureKeys();
  for (const modifiers of [
    { isComposing: true },
    { keyCode: 229 },
    { shiftKey: true },
    { ctrlKey: true },
    { altKey: true },
    { metaKey: true },
  ]) {
    expect(keys.down(key('Enter', modifiers), 'listening', true)).toEqual({ consume: false });
    expect(keys.down(key('Escape', modifiers), 'listening', true)).toEqual({ consume: false });
  }
});
it('does not act outside focused capture or on an already repeating initial press', () => {
  const keys = new VoiceCaptureKeys();
  expect(keys.down(key('Enter'), 'listening', false)).toEqual({ consume: false });
  expect(keys.down(key('Enter'), 'loading', true)).toEqual({ consume: false });
  expect(keys.down(key('Escape'), 'transcribing', true)).toEqual({ consume: false });
  expect(keys.down(key('Enter', { repeat: true }), 'listening', true)).toEqual({
    consume: true,
    action: undefined,
  });
  keys.reset();
  expect(keys.up('Enter')).toBe(false);
});
it('capture interception prevents downstream terminal and modal handlers', () => {
  const keys = new VoiceCaptureKeys();
  const terminal = vi.fn();
  const interpret = vi.fn();
  const actions: string[] = [];
  for (const status of ['listening', 'transcribing', 'idle']) {
    const result = keys.down(key('Enter', { repeat: status !== 'listening' }), status, true);
    if (result.consume) {
      if (result.action) actions.push(result.action);
      continue;
    }
    terminal();
    interpret();
  }
  expect(actions).toEqual(['finish']);
  expect(terminal).not.toHaveBeenCalled();
  expect(interpret).not.toHaveBeenCalled();
});
