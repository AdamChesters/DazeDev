import { describe, expect, it } from 'vitest';
import { tabColors, contrast, palettes } from '../src/lib/colors';
describe('workspace and terminal colors', () => {
  it('inherits dynamically unless a terminal overrides, and resets to inheritance', () => {
    const workspace = { background: '#335a57', foreground: '#ffffff' };
    const custom = { background: '#654554', foreground: '#ffffff' };
    expect(tabColors(undefined, workspace)).toEqual(workspace);
    expect(tabColors(custom, workspace)).toEqual(custom);
    expect(tabColors(undefined, { ...workspace, background: '#1456a0' })?.background).toBe(
      '#1456a0',
    );
  });
  it('keeps default muted and vivid presets readable behind white text', () => {
    for (const p of palettes)
      for (const background of [p.muted, p.vivid])
        expect(contrast({ background, foreground: '#ffffff' })).toBeGreaterThanOrEqual(4.5);
    expect(tabColors({ background: 'invalid', foreground: '#ffffff' })).toBeUndefined();
  });
});

import { retainedPaneColors } from '../src/lib/colors';
describe('host inheritance and transferred colours', () => {
  const host = { background: '#1456a0', foreground: '#ffffff' };
  const workspace = { background: '#335a57', foreground: '#ffffff' };
  const pane = { background: '#654554', foreground: '#ffffff' };
  const theme = { background: '#242822', foreground: '#edede8' };
  it('uses pane, workspace, host and then theme, ignoring malformed saved values', () => {
    expect(tabColors(pane, workspace, host)).toEqual(pane);
    expect(tabColors(undefined, workspace, host)).toEqual(workspace);
    expect(tabColors(undefined, undefined, host)).toEqual(host);
    expect(tabColors({ ...pane, background: 'bad' }, workspace, host)).toEqual(workspace);
    expect(retainedPaneColors(undefined, undefined, undefined, theme)).toEqual(theme);
  });
  it('keeps the source colour independently after transfer, with reset restoring inheritance', () => {
    const retained = retainedPaneColors(undefined, workspace, host, theme);
    expect(tabColors(retained, pane, host)).toEqual(workspace);
    workspace.background = '#335a58';
    expect(retained.background).toBe('#335a57');
    expect(tabColors(undefined, pane, host)).toEqual(pane);
  });
});
