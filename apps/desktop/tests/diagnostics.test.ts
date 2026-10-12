import { describe, it, expect } from 'vitest';
import { diagnostic, diagnosticSnapshot } from '../src/lib/diagnostics';
describe('diagnostic retention', () => {
  it('bounds entries and returns detached snapshots', () => {
    for (let i = 0; i < 250; i++) diagnostic('rpc.ping.ok', String(i), i);
    const entries = diagnosticSnapshot();
    expect(entries).toHaveLength(200);
    expect(entries[0].target).toBe('50');
    entries[0].event = 'changed';
    expect(diagnosticSnapshot()[0].event).toBe('rpc.ping.ok');
    expect(Object.keys(entries[1]).sort()).toEqual(['at', 'duration', 'event', 'target']);
  });
});

import { terminalSlot } from '../src/lib/terminals';
describe('terminal startup scheduling', () => {
  it('stages large workspaces without dropping queued terminals', async () => {
    const held = await Promise.all(Array.from({ length: 4 }, () => terminalSlot()));
    let acquired = 0;
    const waiting = Array.from({ length: 28 }, async () => {
      const release = await terminalSlot();
      acquired++;
      release();
      release();
    });
    await Promise.resolve();
    expect(acquired).toBe(0);
    held.forEach((release) => release());
    await Promise.all(waiting);
    expect(acquired).toBe(28);
    const next = await terminalSlot();
    next();
  });
});
