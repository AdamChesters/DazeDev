// Stand-in for the Tauri JavaScript API in component tests. Mock the real
// modules with this file: vi.mock('@tauri-apps/api/core', () => import('./support/tauri')).
import { vi } from 'vitest';

type Handler = (args: Record<string, unknown>) => unknown;
/** Per-command replies for invoke(); unknown commands resolve to undefined. */
export const handlers = new Map<string, Handler>();
export const invoke = vi.fn(async (command: string, args: Record<string, unknown> = {}) => {
  const handler = handlers.get(command);
  return handler ? handler(args) : undefined;
});
export const isTauri = () => true;
export function calls(command: string): Record<string, unknown>[] {
  return invoke.mock.calls
    .filter(([name]) => name === command)
    .map(([, args]) => (args || {}) as Record<string, unknown>);
}

export class Channel<T = unknown> {
  onmessage: (message: T) => void = () => {};
}

type Listener = (event: { event: string; id: number; payload: unknown }) => void;
const listeners = new Map<string, Set<Listener>>();
export async function listen(name: string, listener: Listener) {
  if (!listeners.has(name)) listeners.set(name, new Set());
  listeners.get(name)!.add(listener);
  return () => listeners.get(name)?.delete(listener);
}
export function emit(name: string, payload: unknown) {
  for (const listener of listeners.get(name) || []) listener({ event: name, id: 0, payload });
}

const windowStub = {
  label: 'main',
  show: vi.fn(async () => {}),
  unminimize: vi.fn(async () => {}),
  setFocus: vi.fn(async () => {}),
  setTitle: vi.fn(async () => {}),
  isMinimized: vi.fn(async () => false),
  isVisible: vi.fn(async () => true),
  isFocused: vi.fn(async () => true),
  onFocusChanged: vi.fn(async () => () => {}),
  onCloseRequested: vi.fn(async () => () => {}),
  onResized: vi.fn(async () => () => {}),
  onMoved: vi.fn(async () => () => {}),
};
export const getCurrentWindow = () => windowStub;

/** One raw output frame: 8-byte big-endian sequence number, then UTF-8 bytes. */
export function frame(sequence: number, text: string): ArrayBuffer {
  const data = new TextEncoder().encode(text);
  const buffer = new ArrayBuffer(8 + data.length);
  new DataView(buffer).setBigUint64(0, BigInt(sequence));
  new Uint8Array(buffer, 8).set(data);
  return buffer;
}

export function resetTauri() {
  handlers.clear();
  listeners.clear();
  invoke.mockClear();
}
