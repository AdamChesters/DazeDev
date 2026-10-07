export const terminalMenuDefaults = [
  'split',
  'copy-last',
  'copy-original',
  'review-undelivered',
  'copy-undelivered',
  'copy-original-undelivered',
  'discard-undelivered',
  'transfer',
  'dictionary',
  'host',
  'upload',
  'files',
  'status',
  'copy',
  'paste',
  'select-all',
  'find',
  'export',
  'clear',
  'colors',
  'maximize',
  'rename',
  'close',
] as const;
export type TerminalMenuId = (typeof terminalMenuDefaults)[number];
export type TerminalMenuItem = {
  id: TerminalMenuId;
  label: string;
  run?: () => void;
  disabled?: boolean;
  kind?: 'split' | 'transfer';
};
export function normalizeTerminalMenuOrder(value: unknown): TerminalMenuId[] {
  const known = new Set<string>(terminalMenuDefaults);
  return [
    ...new Set([
      ...(Array.isArray(value)
        ? value.filter(
            (v): v is TerminalMenuId => typeof v === 'string' && known.has(v) && v !== 'close',
          )
        : []),
      ...terminalMenuDefaults.filter((id) => id !== 'close'),
    ]),
    'close',
  ];
}
export function orderedTerminalMenu(
  items: TerminalMenuItem[],
  preference: unknown,
): TerminalMenuItem[] {
  const available = new Map(items.map((item) => [item.id, item]));
  return normalizeTerminalMenuOrder(preference).flatMap((id) =>
    available.has(id) ? [available.get(id)!] : [],
  );
}
export function moveTerminalMenuItem(
  preference: unknown,
  source: TerminalMenuId,
  target: TerminalMenuId,
  after = false,
): TerminalMenuId[] {
  const order = normalizeTerminalMenuOrder(preference);
  if (source === target || source === 'close' || target === 'close') return order;
  const next = order.filter((id) => id !== source),
    index = next.indexOf(target);
  next.splice(index + (after ? 1 : 0), 0, source);
  return next;
}
