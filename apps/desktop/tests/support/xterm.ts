// @xterm/xterm for component tests: the real parser, buffer and input events
// from @xterm/headless, plus no-op stand-ins for the browser-only parts.
import { Terminal as Headless } from '@xterm/headless';

export class Terminal extends Headless {
  static instances: Terminal[] = [];
  element: HTMLElement | undefined;
  focusCount = 0;
  constructor(options: ConstructorParameters<typeof Headless>[0] = {}) {
    super({ ...options, allowProposedApi: true });
    Terminal.instances.push(this);
  }
  open(parent: HTMLElement) {
    this.element = document.createElement('div');
    this.element.className = 'xterm';
    parent.append(this.element);
  }
  focus() {
    this.focusCount++;
  }
  blur() {}
  attachCustomKeyEventHandler(_handler: (event: KeyboardEvent) => boolean) {}
  registerLinkProvider(_provider: unknown) {
    return { dispose() {} };
  }
  hasSelection() {
    return false;
  }
  getSelection() {
    return '';
  }
  selectAll() {}
  select(_column: number, _row: number, _length: number) {}
  /** Same transformation as xterm's paste(): CR line breaks, bracketed if asked. */
  paste(data: string) {
    data = data.replace(/\r?\n/g, '\r');
    if (this.modes.bracketedPasteMode) data = `\x1b[200~${data}\x1b[201~`;
    this.input(data, true);
  }
  /** Visible text, trailing blank lines removed. */
  text() {
    const buffer = this.buffer.active;
    return Array.from({ length: buffer.length }, (_, i) =>
      buffer.getLine(i)!.translateToString(true),
    )
      .join('\n')
      .trimEnd();
  }
  /** Resolves once everything written so far is parsed. */
  flush() {
    return new Promise<void>((resolve) => this.write('', resolve));
  }
}
