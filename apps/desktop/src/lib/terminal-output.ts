import { on } from './bridge';
type Output = {id: string; data: string; generation: number};
type Subscriber = {id: () => string; receive: (value: Output) => void};
const subscribers = new Set<Subscriber>();
let listening: Promise<() => void> | undefined;
export async function onTerminalOutput(id: () => string, receive: (value: Output) => void) {
  const subscriber = {id, receive};
  subscribers.add(subscriber);
  listening ||= on<Output>('terminal-output', value => {
    for (const subscriber of subscribers) {
      if (subscriber.id() === value.id) subscriber.receive(value);
    }
  }).catch(error => {listening = undefined;throw error;});
  try {await listening;} catch (error) {subscribers.delete(subscriber);throw error;}
  return () => {subscribers.delete(subscriber);};
}
