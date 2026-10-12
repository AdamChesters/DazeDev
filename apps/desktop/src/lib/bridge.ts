import { diagnostic } from './diagnostics';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
export const desktop = isTauri();
export async function call<T>(name: string, args: Record<string, unknown> = {}): Promise<T> {
  if (!desktop)
    throw new Error(
      'Open the desktop app to connect to real hosts. This browser view is a UI preview.',
    );
  return invoke<T>(name, args);
}
export async function on<T>(name: string, fn: (value: T) => void) {
  return desktop ? listen<T>(name, (e) => fn(e.payload)) : () => {};
}
export async function rpc<T>(
  hostId: string,
  method: string,
  args: Record<string, unknown> = {},
): Promise<T> {
  const started = performance.now();
  try {
    const result = await call<T>('host_rpc', { hostId, method, args });
    diagnostic(`rpc.${method}.ok`, hostId, Math.round(performance.now() - started));
    return result;
  } catch (e) {
    diagnostic(`rpc.${method}.failed`, hostId, Math.round(performance.now() - started));
    throw e;
  }
}

export async function openExternal(url: string): Promise<void> {
  if (desktop) await call('open_external', { url });
  else window.open(url, '_blank', 'noopener,noreferrer');
}
