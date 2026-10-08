import type { Host, Inventory } from './types';
export type Action =
  | { kind: 'split'; axis: 'row' | 'column' }
  | { kind: 'sessions' }
  | {
      kind: 'new';
      destination: 'workspace' | 'current';
      chooseRepo: boolean;
      count: number;
      agent: string;
      repo: string;
      identity: string;
      hostId: string;
      name: string;
      model: string;
      effort: string;
      exactName: boolean;
    }
  | { kind: 'switch'; name: string }
  | { kind: 'rename'; name: string }
  | { kind: 'destroy' }
  | { kind: 'worktree'; branch: string };
const numbers: Record<string, number> = {
  one: 1,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
  seven: 7,
  eight: 8,
  nine: 9,
  ten: 10,
  eleven: 11,
  twelve: 12,
};
export function parseCommand(
  text: string,
  hosts: Host[],
  hostId: string,
  inventory?: Inventory,
  inventories?: Record<string, Inventory>,
): Action {
  const s = text
    .trim()
    .replace(/[.!?]+$/, '')
    .replace(/\bcodecs\b/gi, 'Codex');
  const l = s.toLowerCase();
  if (/\b(?:don't|don’t|do not|never|not)\b/.test(l))
    throw new Error('Negated commands are not supported. Describe only the action you want.');
  if (
    /^(split|open (?:a )?new (?:pane|window))(?: (?:to (?:the )?)?(right|left|below|down|above))?$/.test(
      l,
    )
  )
    return { kind: 'split', axis: /below|down|above/.test(l) ? 'column' : 'row' };
  if (/^(show|list|refresh)(?: (?:tmux|open))? sessions$/.test(l)) return { kind: 'sessions' };
  let m = s.match(/^(?:switch|go) to (?:workspace |group |tab )?(.+)$/i);
  if (m) return { kind: 'switch', name: m[1] };
  m = s.match(/^rename (?:this )?(?:session|pane) (?:to )?(.+)$/i);
  if (m) return { kind: 'rename', name: m[1] };
  if (/^(destroy|kill) (?:this )?session$/i.test(s)) return { kind: 'destroy' };
  m = s.match(/^(?:create|new) (?:a )?(?:worktree|branch) (?:called |named )?(.+)$/i);
  if (m) return { kind: 'worktree', branch: m[1] };
  if (
    /^(?:please\s+)?(?:new|create|start|open)\b/.test(l) &&
    /\b(group|workspace|tab|terminals?|windows?|agents?|sessions?|panes?)\b/.test(l)
  ) {
    const cm = l.match(
      /\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(?:new\s+)?(?:tmux\s+)?(?:terminal|window|agent|session|codex|claude|gemini|opencode)/,
    );
    const count = cm ? numbers[cm[1]] || Number(cm[1]) : 1;
    if (!Number.isSafeInteger(count) || count < 1)
      throw new Error('Choose a positive whole number of terminals.');
    const agents = ['codex', 'claude', 'gemini', 'opencode'].filter((x) =>
      new RegExp('\\b' + x + '\\b').test(l),
    );
    if (agents.length > 1) throw new Error('Choose one agent for this command.');
    const agent = agents[0] || 'shell';
    const chooseRepo = /\b(?:show|open) (?:the )?(?:repo|repository) picker\b/.test(l);
    if (
      /\b(?:and|then)\b/.test(l) &&
      !/\s+and\s+(?:show|open) (?:the )?(?:repo|repository) picker$/.test(l)
    )
      throw new Error('Run one command at a time, or add “and show repo picker”.');
    const host = hosts.filter((h) => l.includes(h.name.toLowerCase()));
    if (host.length > 1)
      throw new Error('More than one host matched. Choose a host in the launch form.');
    const targetHost = host[0]?.id || hostId;
    const targetInventory =
      inventories?.[targetHost] || (targetHost === hostId ? inventory : undefined);
    const repos = (targetInventory?.repos || []).filter((r) =>
      new RegExp('\\b' + r.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\b', 'i').test(s),
    );
    if (repos.length > 1)
      throw new Error('More than one repository matched. Choose one in the launch form.');
    const identity =
      targetInventory?.identities.find((i) => l.includes(i.login.toLowerCase()))?.login || '';
    const knownModels = targetInventory?.agents.find((a) => a.id === agent)?.models || [];
    const knownModel = knownModels.find(
      (m) => l.includes(m.id.toLowerCase()) || l.includes(m.name.toLowerCase()),
    );
    const model = s.match(/\bmodel\s+([\w./:-]+)/i)?.[1] || knownModel?.id || '';
    const effort =
      l.match(/\beffort\s+(minimal|low|medium|high|xhigh)\b/)?.[1] ||
      l.match(/\b(minimal|low|medium|high|xhigh)\s+effort\b/)?.[1] ||
      '';
    const named = s.match(
      /(?:called|named)\s+(?:"([^"]+)"|'([^']+)'|(.+?))(?=\s+(?:using|with|on|under)\b|$)/i,
    );
    const name = named?.[1] || named?.[2] || named?.[3] || 'New workspace';
    // Never silently discard an unknown count, destination or secondary action.
    let remaining = l
      .replace(/(?:called|named)\s+(?:"[^"]+"|'[^']+'|.+?)(?=\s+(?:using|with|on|under)\b|$)/g, ' ')
      .replace(/\bmodel\s+[\w./:-]+/g, ' ');
    const entities = [
      ...host.map((h) => h.name),
      ...repos.map((r) => r.name),
      identity,
      knownModel?.id || '',
      knownModel?.name || '',
    ].filter(Boolean);
    for (const entity of entities)
      remaining = remaining.replace(
        new RegExp(
          '\\b' + entity.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\b',
          'g',
        ),
        ' ',
      );
    remaining = remaining
      .replace(
        /\b(?:please|new|create|start|open|a|the|with|using|in|on|under|group|workspace|tab|terminals?|windows?|agents?|sessions?|panes?|tmux|codex|claude|gemini|opencode|and|show|repo|repository|picker|effort|minimal|low|medium|high|xhigh|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\d+)\b/g,
        ' ',
      )
      .trim();
    if (remaining)
      throw new Error(
        'Some words were not understood. Choose a command library example and edit it.',
      );
    const quantities =
      l.match(/\b(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b/g) || [];
    if (quantities.length > 1) throw new Error('Choose one terminal count per command.');

    return {
      kind: 'new',
      destination: /\b(?:workspace|group|tab)\b/.test(l) ? 'workspace' : 'current',
      chooseRepo,
      exactName: /\bsession\b/.test(l) && count === 1 && !!named,
      model,
      effort,
      count,
      agent,
      repo: repos[0]?.path || '',
      identity,
      hostId: targetHost,
      name,
    };
  }
  throw new Error(
    'Try “split right”, “show sessions”, or “new group with four Codex terminals on core”.',
  );
}

// Catalogue entries are also the spoken examples; extend this alongside the typed parser.
export const commandCatalogue = [
  {
    example: 'Open a new workspace',
    description: 'Review a new workspace with one plain terminal.',
  },
  {
    example: 'Open four new terminals',
    description: 'Add plain terminals to the current workspace.',
  },
  {
    example: 'Open three new terminals with Codex',
    description: 'Add three separate Codex tmux sessions.',
  },
  {
    example: 'Open a terminal in Codex and show repo picker',
    description: 'Choose a repository before reviewing and starting Codex.',
  },
  { example: 'Split right', description: 'Add one plain terminal beside this terminal.' },
  { example: 'Split below', description: 'Add one plain terminal below this terminal.' },
  { example: 'Show sessions', description: 'Browse existing tmux sessions.' },
  {
    example: 'Switch to workspace Planning',
    description: 'Switch to an existing workspace by name.',
  },
  {
    example: 'Rename this session to review',
    description: 'Review a new name for the selected tmux session.',
  },
  {
    example: 'Create worktree named feature/review',
    description: 'Open the worktree form with a branch name.',
  },
  {
    example: 'Destroy this session',
    description: 'Open the confirmation for ending the selected tmux session.',
  },
] as const;
