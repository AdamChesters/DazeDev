import { on, rpc } from './bridge';

// Agents such as Codex and Claude Code open the prompt in $VISUAL/$EDITOR
// for Ctrl+G and wait for the editor to exit. In sessions DazeDev starts,
// that editor is a host helper that sends the file here (event
// host-editor-open). The terminal running the agent shows it in an editable
// pane. Replies: editor_shown once the pane is open, then editor_done with
// the saved text, with nothing for cancel, or with an error so the helper
// opens a terminal editor instead and the agent never waits for nobody.

export interface EditRequest {
  hostId: string;
  id: string;
  /** The DazeDev terminal's shell process on the host. */
  pid: number;
  name: string;
  text: string;
}

export interface EditorView {
  hostId: () => string;
  /** This terminal's shell process, or 0 while it cannot take a request. */
  pid: () => number;
  /** Show the request; false when this view is already showing one. */
  open: (request: EditRequest) => boolean;
}

export type EditorResult = { text: string } | { cancelled: true } | { error: string };

export const NO_TERMINAL =
  'No DazeDev terminal on this desktop is showing this session. Using the terminal editor.';
export const ALREADY_EDITING =
  'This terminal already has an editor open in DazeDev. Using the terminal editor.';

export function parseEditRequest(value: unknown): EditRequest | null {
  if (!value || typeof value !== 'object') return null;
  const v = value as Record<string, unknown>;
  if (typeof v.hostId !== 'string' || !v.hostId) return null;
  if (typeof v.id !== 'string' || !/^[0-9a-f]{32}$/.test(v.id)) return null;
  if (typeof v.pid !== 'number' || !Number.isSafeInteger(v.pid) || v.pid <= 0) return null;
  if (typeof v.text !== 'string') return null;
  return {
    hostId: v.hostId,
    id: v.id,
    pid: v.pid,
    name: typeof v.name === 'string' && v.name ? v.name : 'Editor',
    text: v.text,
  };
}

type Reply = (hostId: string, method: string, args: Record<string, unknown>) => Promise<unknown>;
type Listen = (receive: (value: unknown) => void) => Promise<() => void>;

export class EditorRequests {
  private views = new Set<EditorView>();
  private listening: Promise<() => void> | undefined;
  constructor(
    private listen: Listen = (receive) => on<unknown>('host-editor-open', receive),
    private reply: Reply = (hostId, method, args) => rpc(hostId, method, args),
  ) {}
  /** Take requests for this view until the returned function is called. */
  register(view: EditorView): () => void {
    this.views.add(view);
    this.listening ??= this.listen((value) => this.receive(value));
    return () => {
      this.views.delete(view);
      if (this.views.size || !this.listening) return;
      const listening = this.listening;
      this.listening = undefined;
      void listening.then((stop) => stop()).catch(() => {});
    };
  }
  receive(value: unknown) {
    const request = parseEditRequest(value);
    if (!request) return;
    const view = [...this.views].find(
      (v) => v.hostId() === request.hostId && v.pid() === request.pid,
    );
    if (!view) return void this.finish(request, { error: NO_TERMINAL }).catch(() => {});
    if (!view.open(request))
      return void this.finish(request, { error: ALREADY_EDITING }).catch(() => {});
    // If this is lost, the helper stops waiting and the save reports it.
    void this.reply(request.hostId, 'editor_shown', { id: request.id }).catch(() => {});
  }
  /** Answer a request once: saved text, cancelled, or refused with an error. */
  finish(request: EditRequest, result: EditorResult) {
    const args: Record<string, unknown> = { id: request.id };
    if ('text' in result) args.text = result.text;
    if ('error' in result) args.error = result.error;
    return this.reply(request.hostId, 'editor_done', args);
  }
}

export const editorRequests = new EditorRequests();
