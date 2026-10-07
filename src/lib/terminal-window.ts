import type { Host, Pane } from './types';
import type { TerminalSettings } from './terminals';

export interface TerminalSnapshot {
  sequence: number;
  generation: number;
  data: string;
  cols: number;
  rows: number;
  context: string;
  ready: boolean;
}
export interface WindowTerminalState {
  pane: Pane;
  host?: Host;
  workspaceName: string;
  workspaceColors?: import('./colors').TabColors;
  theme: string;
  settings: TerminalSettings;
  inputShortcut: import('./shortcut').Shortcut;
  commandShortcut: import('./shortcut').Shortcut;
  voiceStatus: string;
  voiceMode: string;
  voiceTarget: string;
  dictation: string;
  status: string;
  ready: boolean;
}
export interface TerminalOutput {
  sequence: number;
  generation: number;
  data: string;
}

export class TerminalStream {
  sequence = 0;
  private initialized = false;
  private pending = new Map<number, TerminalOutput>();
  get hasGap() {
    return this.initialized && this.pending.size > 0 && !this.pending.has(this.sequence + 1);
  }
  receive(output: TerminalOutput): TerminalOutput[] {
    if (output.sequence <= this.sequence) return [];
    this.pending.set(output.sequence, output);
    return this.drain();
  }
  restore(snapshot: TerminalSnapshot): TerminalOutput[] {
    this.sequence = snapshot.sequence;
    this.initialized = true;
    for (const sequence of this.pending.keys())
      if (sequence <= this.sequence) this.pending.delete(sequence);
    return this.drain();
  }
  private drain(): TerminalOutput[] {
    const result: TerminalOutput[] = [];
    if (!this.initialized) return result;
    while (this.pending.has(this.sequence + 1)) {
      result.push(this.pending.get(++this.sequence)!);
      this.pending.delete(this.sequence);
    }
    return result;
  }
}
