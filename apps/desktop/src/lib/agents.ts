import type { Agent, Inventory } from './types';

/** Built-in agents in the order the host helper lists them. */
export const BUILT_IN_AGENTS: { id: string; name: string }[] = [
  { id: 'codex', name: 'Codex' },
  { id: 'claude', name: 'Claude Code' },
  { id: 'gemini', name: 'Gemini CLI' },
  { id: 'opencode', name: 'OpenCode' },
  { id: 'copilot', name: 'GitHub Copilot CLI' },
  { id: 'cursor-agent', name: 'Cursor CLI' },
  { id: 'qwen', name: 'Qwen Code' },
  { id: 'droid', name: 'Factory Droid' },
  { id: 'goose', name: 'goose' },
  { id: 'amp', name: 'Amp' },
  { id: 'crush', name: 'Crush' },
  { id: 'aider', name: 'Aider' },
  { id: 'kimi', name: 'Kimi Code' },
];

/** A custom agent as the host stores it in agents.json. */
export interface CustomAgent {
  id: string;
  name: string;
  command: string;
  install: string;
  version: string;
  binary: string;
  [extra: string]: unknown;
}

export const AGENT_ID = /^[a-z][a-z0-9-]{0,31}$/;
const BINARY = /^(?:\/[^\x00-\x20\x7f]*\/)?[A-Za-z0-9_+][A-Za-z0-9._+-]{0,63}$/;
const CONTROL = /[\x00-\x1f\x7f]/;
// Same list as the host: panes are detected by this name.
const GENERIC = new Set(
  'sh bash zsh fish dash ksh mksh tcsh csh nu pwsh env sudo doas exec nohup tmux screen ssh mosh node nodejs npx npm bun bunx deno python python3 uv uvx pipx java ruby perl'.split(
    ' ',
  ),
);

export const agentLabel = (agent: Pick<Agent, 'id'> & { name?: string }) =>
  agent.name || BUILT_IN_AGENTS.find((a) => a.id === agent.id)?.name || agent.id;

/** Any agent ID the host can report for a pane, built-in or custom. */
export const isAgentId = (value: string | null | undefined): value is string =>
  !!value && value !== 'shell' && AGENT_ID.test(value);

/** Whether a new session with this agent can carry a starting prompt. Hosts before 0.3.9 omit the flag. */
export const takesPrompt = (agent: Agent | undefined) => agent?.prompt !== false;

/** Agents a session can start now: installed, or installable on request. */
export const startable = (agent: Agent, install: boolean) =>
  !!agent.path || (install && agent.installable !== false);

/** IDs derived from a display name: "My Agent 2" becomes "my-agent-2". */
export function agentIdFrom(name: string): string {
  const id = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^[^a-z]+/, '')
    .replace(/-+$/, '')
    .slice(0, 32)
    .replace(/-+$/, '');
  return id;
}

/** The program a command starts, when its first word is a plain name or path. */
export function binaryFrom(command: string): string {
  const first = (command.trim().split(/\s+/)[0] || '').replace(/^(['"])(.+)\1$/, '$2');
  return BINARY.test(first) ? first : '';
}

function text(value: string, label: string, limit: number, required = false): string {
  const trimmed = value.trim();
  if (required && !trimmed) return `${label} is required.`;
  if (trimmed.length > limit) return `${label} must be at most ${limit} characters.`;
  if (CONTROL.test(trimmed)) return `${label} must be a single line.`;
  return '';
}

/** First problem with a draft, matching the host's checks; '' when valid. */
export function customAgentError(
  draft: Pick<CustomAgent, 'id' | 'name' | 'command' | 'install' | 'version' | 'binary'>,
  others: { id: string }[],
): string {
  if (!AGENT_ID.test(draft.id))
    return 'Agent IDs use 1 to 32 lowercase letters, numbers and hyphens, starting with a letter.';
  if (draft.id === 'shell' || BUILT_IN_AGENTS.some((a) => a.id === draft.id))
    return `${draft.id} is a built-in agent; choose another ID.`;
  if (others.some((a) => a.id === draft.id)) return 'Each custom agent needs its own ID.';
  const problem =
    text(draft.name, 'Display name', 60, true) ||
    text(draft.command, 'Launch command', 2000, true) ||
    text(draft.install, 'Install command', 2000) ||
    text(draft.version, 'Version command', 500) ||
    text(draft.binary, 'Detection binary', 256);
  if (problem) return problem;
  const binary = draft.binary.trim() || binaryFrom(draft.command);
  if (!binary || !BINARY.test(binary) || /\/\.{1,2}\//.test(binary + '/'))
    return 'Add a detection binary: the program name or absolute path the launch command starts.';
  const base = binary.split('/').pop() || '';
  if (GENERIC.has(base)) return `Name the agent's own program as detection binary, not ${base}.`;
  return '';
}

/** Replaces or adds one agent, keeping the others and their extra fields. */
export function upsertCustomAgent(
  agents: CustomAgent[],
  draft: CustomAgent,
  previousId?: string,
): CustomAgent[] {
  const record = {
    ...agents.find((a) => a.id === (previousId ?? draft.id)),
    ...draft,
    name: draft.name.trim(),
    command: draft.command.trim(),
    install: draft.install.trim(),
    version: draft.version.trim(),
    binary: draft.binary.trim(),
  };
  const index = agents.findIndex((a) => a.id === (previousId ?? draft.id));
  return index < 0 ? [...agents, record] : agents.map((a, i) => (i === index ? record : a));
}

const COMMAND_WORDS = new Set(
  'please new create start open a the with using in on under group workspace tab terminal terminals window windows agent agents session sessions pane panes tmux and show repo repository picker effort model'.split(
    ' ',
  ),
);

/** Words the command palette recognises as agents, each mapped to its ID. */
export function agentTerms(inventory?: Inventory): { term: string; id: string }[] {
  const agents = [...BUILT_IN_AGENTS, ...(inventory?.agents || [])];
  const terms = new Map<string, string>();
  for (const agent of agents) {
    for (const term of [agent.id, agent.name?.toLowerCase()]) {
      if (!term || COMMAND_WORDS.has(term) || terms.has(term)) continue;
      terms.set(term, agent.id);
    }
  }
  // Longer terms first, so "claude code" wins over "claude".
  return [...terms]
    .map(([term, id]) => ({ term, id }))
    .sort((a, b) => b.term.length - a.term.length);
}
