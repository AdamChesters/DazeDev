import { on } from './bridge';
type Output = {id: string; data: string; generation: number};
const subscribers = new Map<string, Set<(value: Output) => void>>();
let listening: Promise<() => void> | undefined;
export async function onTerminalOutput(id: string, receive: (value: Output) => void) {
  let callbacks = subscribers.get(id);
  if (!callbacks) subscribers.set(id, callbacks = new Set());
  callbacks.add(receive);
  listening ||= on<Output>('terminal-output', value => {
    for (const callback of subscribers.get(value.id) || []) callback(value);
  }).catch(error => {listening = undefined;throw error;});
  try {await listening;} catch (error) {callbacks.delete(receive);throw error;}
  return () => {callbacks!.delete(receive);if (!callbacks!.size) subscribers.delete(id);};
}
