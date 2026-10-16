// Accept only explicit terminal notifications; silence is not evidence of completion.
export function terminalNotification(osc: number, data: string): string | null {
  if (osc === 9 && data && !/^\d+;/.test(data))
    return data.replace(/[\x00-\x1f\x7f]/g, '').slice(0, 240);
  if (osc === 777 && data.startsWith('notify;'))
    return data
      .slice(7)
      .split(';')
      .filter(Boolean)
      .join(': ')
      .replace(/[\x00-\x1f\x7f]/g, '')
      .slice(0, 240);
  return null;
}

// One throttle window per pane, so a busy pane cannot hide alerts from others.
export class AlertThrottle {
  private last = new Map<string, number>();
  constructor(private interval = 3000) {}
  allow(paneId: string, now = Date.now()): boolean {
    const previous = this.last.get(paneId);
    if (previous !== undefined && now - previous < this.interval) return false;
    this.last.set(paneId, now);
    return true;
  }
  retain(live: Set<string>) {
    for (const paneId of this.last.keys()) if (!live.has(paneId)) this.last.delete(paneId);
  }
}

export const longCommandMs = 10000;
// OSC 133 D: report a finished command only when the user is not watching the
// pane, or when it ran long enough that they may have looked away.
export function commandFinishedAlert(value: {
  startedAt: number;
  now: number;
  watched: boolean;
}): boolean {
  return !value.watched || value.now - value.startedAt >= longCommandMs;
}
