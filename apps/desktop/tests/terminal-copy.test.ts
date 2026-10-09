import { expect, it, vi } from 'vitest';
import { copySelectedTerminalText } from '../src/lib/terminal-copy';

const key = (extra = {}) => ({
  type: 'keydown',
  code: 'KeyC',
  ctrlKey: true,
  metaKey: false,
  altKey: false,
  shiftKey: false,
  repeat: false,
  preventDefault: vi.fn(),
  stopPropagation: vi.fn(),
  ...extra,
});

it('copies selected text without allowing the terminal interrupt handler to run', async () => {
  const event = key(),
    copy = vi.fn().mockResolvedValue(undefined),
    interrupt = vi.fn();
  if (!copySelectedTerminalText(event, 'selected output', copy, vi.fn())) interrupt();
  await Promise.resolve();
  expect(copy).toHaveBeenCalledWith('selected output');
  expect(event.preventDefault).toHaveBeenCalled();
  expect(event.stopPropagation).toHaveBeenCalled();
  expect(interrupt).not.toHaveBeenCalled();
});

it('consumes copying even when clipboard permission fails', async () => {
  const report = vi.fn(),
    interrupt = vi.fn();
  if (!copySelectedTerminalText(key(), 'text', () => Promise.reject(Error('denied')), report))
    interrupt();
  await vi.waitFor(() => expect(report).toHaveBeenCalledOnce());
  expect(interrupt).not.toHaveBeenCalled();
});

it('preserves plain Ctrl+C without selection and ignores Alt combinations', () => {
  expect(copySelectedTerminalText(key(), '', vi.fn(), vi.fn())).toBe(false);
  expect(copySelectedTerminalText(key({ altKey: true }), 'text', vi.fn(), vi.fn())).toBe(false);
});

it('copies whitespace on Cmd+C and consumes repeats without duplicate copies', async () => {
  const copy = vi.fn().mockResolvedValue(undefined);
  expect(
    copySelectedTerminalText(key({ ctrlKey: false, metaKey: true }), '  ', copy, vi.fn()),
  ).toBe(true);
  expect(copySelectedTerminalText(key({ repeat: true }), '  ', copy, vi.fn())).toBe(true);
  await Promise.resolve();
  expect(copy).toHaveBeenCalledTimes(1);
  expect(copy).toHaveBeenCalledWith('  ');
});
