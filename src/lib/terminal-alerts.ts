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
