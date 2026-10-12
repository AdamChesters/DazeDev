export interface TabColors {
  background: string;
  foreground: string;
}
export const palettes = [
  { name: 'Blue', muted: '#3a5269', vivid: '#1456a0' },
  { name: 'Lavender', muted: '#564661', vivid: '#7137a2' },
  { name: 'Teal', muted: '#335a57', vivid: '#006b62' },
  { name: 'Rose', muted: '#654554', vivid: '#9b315b' },
  { name: 'Peach', muted: '#674b3c', vivid: '#9c4719' },
  { name: 'Sage', muted: '#48583f', vivid: '#376b21' },
];
export function tabColors(...values: (TabColors | undefined)[]): TabColors | undefined {
  return values.find(
    (value) =>
      value && /^#[0-9a-f]{6}$/i.test(value.background) && /^#[0-9a-f]{6}$/i.test(value.foreground),
  );
}
// Cross-workspace moves keep the visible source colour until the user resets it.
export function retainedPaneColors(
  pane: TabColors | undefined,
  workspace: TabColors | undefined,
  host: TabColors | undefined,
  theme: TabColors,
): TabColors {
  return { ...(tabColors(pane, workspace, host) || theme) };
}
export function contrast(colors: TabColors): number {
  const light = (hex: string) => {
    const c = [1, 3, 5]
      .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((n) => (n <= 0.04045 ? n / 12.92 : ((n + 0.055) / 1.055) ** 2.4));
    return c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722;
  };
  const a = light(colors.background),
    b = light(colors.foreground);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}
