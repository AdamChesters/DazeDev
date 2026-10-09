import type { PaneStatus } from './pane-status';
import type { TerminalControls } from './terminals';
export type BroadcastInput = {
  id: string;
  hostId: string;
  sourceHost: string;
  label: string;
  connected: boolean;
};
export type BroadcastRecipient = BroadcastInput & {
  identity: string;
  context: string;
  terminal: TerminalControls;
};
export type BroadcastSnapshot = {
  recipients: BroadcastRecipient[];
  skipped: string[];
  mirrors: number;
};
export async function snapshotBroadcast(
  inputs: BroadcastInput[],
  terminals: Map<string, TerminalControls>,
  status: (input: BroadcastInput, terminal: TerminalControls) => Promise<PaneStatus>,
): Promise<BroadcastSnapshot> {
  const result: BroadcastSnapshot = { recipients: [], skipped: [], mirrors: 0 };
  const seen = new Set<string>();
  // Probe hidden workspaces too: visible-pane metadata can be stale after manual tmux attach.
  const checked = await Promise.all(
    inputs.map(async (input) => {
      const terminal = terminals.get(input.id),
        context = terminal?.context();
      if (!input.connected || !terminal || !context) return { input };
      let timer: ReturnType<typeof setTimeout> | undefined;
      let actual: PaneStatus = { attachment: 'unknown', nestedSsh: false, hostname: null };
      try {
        actual = await Promise.race([
          status(input, terminal),
          new Promise<PaneStatus>((resolve) => {
            timer = setTimeout(() => resolve(actual), 1500);
          }),
        ]);
      } catch {
        /* Connected but unverified panes remain individual recipients. */
      } finally {
        clearTimeout(timer);
      }
      if (terminal.context() !== context || terminals.get(input.id) !== terminal) return { input };
      const identity =
        (actual.attachment === 'tmux' || actual.attachment === 'native') &&
        !actual.nestedSsh &&
        actual.sessionId &&
        actual.stamp
          ? JSON.stringify([input.sourceHost, actual.attachment, actual.sessionId, actual.stamp])
          : JSON.stringify([input.sourceHost, 'pane', input.id]);
      return { input, terminal, context, identity };
    }),
  );
  for (const item of checked) {
    if (!item.terminal || !item.context || !item.identity) {
      result.skipped.push(item.input.label);
      continue;
    }
    if (seen.has(item.identity)) {
      result.mirrors++;
      continue;
    }
    seen.add(item.identity);
    result.recipients.push({
      ...item.input,
      terminal: item.terminal,
      context: item.context,
      identity: item.identity,
    });
  }
  return result;
}
export function broadcastRevision(snapshot: BroadcastSnapshot): string {
  return JSON.stringify(
    snapshot.recipients.map(({ id, context, identity }) => [id, context, identity]).sort(),
  );
}
export function createBroadcastDelivery(snapshot: BroadcastSnapshot, text: string) {
  let sent = false;
  return async (current: Map<string, TerminalControls>) => {
    if (sent) throw Error('This broadcast has already been submitted.');
    if (!text.trim() || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(text))
      throw Error('Enter text without terminal control characters.');
    sent = true;
    const delivered: string[] = [],
      failed: string[] = [];
    await Promise.all(
      snapshot.recipients.map(async (recipient) => {
        try {
          if (
            current.get(recipient.id) !== recipient.terminal ||
            recipient.terminal.context() !== recipient.context
          )
            throw Error('Terminal changed or disconnected.');
          await recipient.terminal.pasteBroadcast!(text, recipient.context);
          delivered.push(recipient.label);
        } catch (error) {
          failed.push(`${recipient.label}: ${String(error)}`);
        }
      }),
    );
    return { delivered, failed };
  };
}
