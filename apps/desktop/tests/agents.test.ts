// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { installDom } from './support/dom';
import {
  agentIdFrom,
  agentLabel,
  agentTerms,
  binaryFrom,
  customAgentError,
  isAgentId,
  startable,
  takesPrompt,
  upsertCustomAgent,
  type CustomAgent,
} from '../src/lib/agents';
import { parseCommand } from '../src/lib/commands';
import { terminalPresentation } from '../src/lib/pane-status';
import type { Agent, Device, Host, Inventory } from '../src/lib/types';

const io = vi.hoisted(() => ({ rpc: vi.fn() }));
vi.mock('../src/lib/bridge', () => ({ desktop: true, rpc: io.rpc, call: vi.fn() }));
import Agents from '../src/components/modals/Agents.svelte';

const agent = (id: string, extra: Partial<Agent> = {}): Agent => ({
  id,
  path: `/usr/bin/${id}`,
  version: '',
  models: [],
  modelFlag: false,
  effortFlag: false,
  install: '',
  ...extra,
});
const inventory = (agents: Agent[]): Inventory => ({
  hostname: 'dev',
  user: 'me',
  machine: 'm',
  home: '/home/me',
  tmux: '/usr/bin/tmux',
  agents,
  identities: [],
  repos: [],
  sessions: [],
});
const draft = (extra: Partial<CustomAgent> = {}): CustomAgent => ({
  id: 'mine',
  name: 'Mine',
  command: 'mine --fast',
  install: '',
  version: '',
  binary: '',
  ...extra,
});

describe('custom agent validation', () => {
  it('accepts a plain record and derives IDs and binaries like the host', () => {
    expect(customAgentError(draft(), [])).toBe('');
    expect(agentIdFrom('My Agent 2!')).toBe('my-agent-2');
    expect(agentIdFrom('42 Tools')).toBe('tools');
    expect(agentIdFrom('x'.repeat(40))).toHaveLength(32);
    expect(binaryFrom('mine --fast')).toBe('mine');
    expect(binaryFrom('"/opt/my tools/bin/mine" --x')).toBe('');
    expect(binaryFrom("'/opt/tools/mine' --x")).toBe('/opt/tools/mine');
    expect(binaryFrom('FOO=1 mine')).toBe('');
  });
  it.each([
    [{ id: 'Mine' }, 'lowercase'],
    [{ id: '-mine' }, 'lowercase'],
    [{ id: 'mine;touch' }, 'lowercase'],
    [{ id: '$(id)' }, 'lowercase'],
    [{ id: 'codex' }, 'built-in'],
    [{ id: 'cursor-agent' }, 'built-in'],
    [{ id: 'shell' }, 'built-in'],
    [{ name: ' ' }, 'Display name is required'],
    [{ name: 'x'.repeat(61) }, 'at most 60'],
    [{ command: '' }, 'Launch command is required'],
    [{ command: 'mine\ntouch /tmp/x' }, 'single line'],
    [{ command: 'mine \u001b[2J' }, 'single line'],
    [{ install: 'a\rb' }, 'single line'],
    [{ command: 'FOO=1 mine' }, 'detection binary'],
    [{ binary: '-rf' }, 'detection binary'],
    [{ binary: 'relative/path' }, 'detection binary'],
    [{ binary: '/opt/../etc/x' }, 'detection binary'],
    [{ binary: 'bash' }, 'not bash'],
    [{ command: 'npx some-agent' }, 'not npx'],
  ])('rejects %o', (change, message) => {
    expect(customAgentError(draft(change), [])).toContain(message);
  });
  it('rejects a duplicate ID', () => {
    expect(customAgentError(draft(), [{ id: 'mine' }])).toContain('own ID');
  });
  it('edits in place, keeps unknown fields and renames by previous ID', () => {
    const saved = [draft({ futureField: 1 }), draft({ id: 'other', name: 'Other' })];
    const renamed = upsertCustomAgent(saved, draft({ id: 'mine-2', name: ' Mine 2 ' }), 'mine');
    expect(renamed.map((a) => a.id)).toEqual(['mine-2', 'other']);
    expect(renamed[0]).toMatchObject({ name: 'Mine 2', futureField: 1 });
    expect(upsertCustomAgent(saved, draft({ id: 'new' })).map((a) => a.id)).toEqual([
      'mine',
      'other',
      'new',
    ]);
  });
});

describe('agent capabilities', () => {
  it('labels, prompts and install state follow the host report', () => {
    expect(agentLabel({ id: 'copilot' })).toBe('GitHub Copilot CLI');
    expect(agentLabel({ id: 'mine', name: 'Mine' })).toBe('Mine');
    expect(agentLabel({ id: 'unknown' })).toBe('unknown');
    expect(takesPrompt(agent('codex'))).toBe(true);
    expect(takesPrompt(agent('mine', { prompt: false }))).toBe(false);
    expect(takesPrompt(undefined)).toBe(true);
    expect(startable(agent('mine', { path: null, installable: false }), true)).toBe(false);
    expect(startable(agent('mine', { path: null, installable: true }), true)).toBe(true);
    expect(startable(agent('mine', { path: null }), false)).toBe(false);
  });
  it('labels panes running any reported agent, built-in or custom', () => {
    const pane = { name: 'Terminal', agent: 'shell', model: '', shell: true };
    const status = {
      attachment: 'tmux' as const,
      nestedSsh: false,
      hostname: 'dev',
      sessionName: 'work',
    };
    expect(terminalPresentation(pane, { ...status, agent: 'my-agent' }).type).toBe('my-agent');
    expect(terminalPresentation(pane, { ...status, agent: 'copilot' }).type).toBe('copilot');
    expect(terminalPresentation(pane, { ...status, agent: 'shell' }).type).toBe('tmux');
    expect(terminalPresentation(pane, { ...status, agent: '<b>x</b>' }).type).toBe('tmux');
    expect(isAgentId('Bad Id')).toBe(false);
  });
});

describe('command palette agents', () => {
  const local = inventory([agent('codex'), agent('my-agent', { name: 'House Bot', custom: true })]);
  const parse = (text: string) => parseCommand(text, [], 'dev', local, { dev: local });
  it('starts new built-in agents by ID or name', () => {
    expect(parse('open three new terminals with copilot')).toMatchObject({
      count: 3,
      agent: 'copilot',
    });
    expect(parse('new group with two Claude Code terminals')).toMatchObject({
      count: 2,
      agent: 'claude',
    });
    expect(parse('open a terminal with cursor-agent')).toMatchObject({ agent: 'cursor-agent' });
  });
  it('starts custom agents the host reports', () => {
    expect(parse('open two my-agent terminals')).toMatchObject({ count: 2, agent: 'my-agent' });
    expect(parse('open a terminal with house bot')).toMatchObject({ agent: 'my-agent' });
    expect(() => parseCommand('open a terminal with house bot', [], 'dev')).toThrow(
      'not understood',
    );
  });
  it('does not read agent names inside other words or model names', () => {
    expect(parse('open a terminal with codex model gpt-5-codex')).toMatchObject({
      agent: 'codex',
      model: 'gpt-5-codex',
    });
    expect(() => parse('open a terminal with codex and amp')).toThrow();
    expect(() => parse('open a terminal with codex amp')).toThrow('one agent');
  });
  it('never treats command words as agent names', () => {
    const odd = inventory([agent('tab', { name: 'session', custom: true })]);
    expect(agentTerms(odd).map((t) => t.term)).not.toContain('session');
    expect(parseCommand('open a new session', [], 'dev', odd)).toMatchObject({ agent: 'shell' });
  });
});

describe('agents dialog', () => {
  const host = { id: 'dev', name: 'Dev box', kind: 'ssh' } as Host;
  const task = vi.fn(async (fn: () => Promise<void>) => fn());
  function show(agents: Agent[], kind: Host['kind'] = 'ssh') {
    const props = {
      busy: false,
      device: { activeHost: 'dev' } as Device,
      host: { ...host, kind },
      inventories: { dev: inventory(agents) },
      refresh: vi.fn(async () => {}),
      startAgentFromPicker: vi.fn(async (_agent: string) => {}),
      task,
    };
    render(Agents, { props });
    return props;
  }
  beforeEach(() => {
    installDom();
    io.rpc.mockReset();
    task.mockClear();
  });

  it('lists custom agents and only installs those with an install command', async () => {
    const props = show([
      agent('codex', { name: 'Codex' }),
      agent('bare', { name: 'Bare', path: null, custom: true, installable: false }),
      agent('kit', {
        name: 'Kit',
        path: null,
        custom: true,
        installable: true,
        install: 'kit-setup',
      }),
    ]);
    expect(screen.getByText('Bare')).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: 'Not installed' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    await fireEvent.click(screen.getByRole('button', { name: /Run install command and start/ }));
    expect(props.startAgentFromPicker).toHaveBeenCalledExactlyOnceWith('kit');
  });

  it('adds an agent with the host revision and refreshes discovery', async () => {
    io.rpc.mockImplementation(async (_host: string, method: string, args: { agents: unknown }) =>
      method === 'agents_read'
        ? { agents: [draft({ id: 'old', name: 'Old', futureField: true })], revision: 'r1' }
        : { agents: args.agents, revision: 'r2' },
    );
    const props = show([agent('codex')]);
    await fireEvent.click(screen.getByRole('button', { name: /Add your own agent/ }));
    await fireEvent.input(await screen.findByLabelText('Display name'), {
      target: { value: 'House Bot' },
    });
    expect((screen.getByLabelText('ID') as HTMLInputElement).value).toBe('house-bot');
    await fireEvent.input(screen.getByLabelText('Launch command'), {
      target: { value: 'house-bot --chat' },
    });
    await fireEvent.click(screen.getByRole('button', { name: 'Save agent' }));
    await waitFor(() => expect(props.refresh).toHaveBeenCalledWith('dev'));
    expect(io.rpc).toHaveBeenLastCalledWith('dev', 'agents_save', {
      revision: 'r1',
      agents: [
        draft({ id: 'old', name: 'Old', futureField: true }),
        draft({
          id: 'house-bot',
          name: 'House Bot',
          command: 'house-bot --chat',
          binary: 'house-bot',
        }),
      ],
    });
  });

  it('shows validation errors before saving anything', async () => {
    io.rpc.mockResolvedValue({ agents: [], revision: '' });
    show([]);
    await fireEvent.click(screen.getByRole('button', { name: /Add your own agent/ }));
    await fireEvent.input(await screen.findByLabelText('Display name'), {
      target: { value: 'Codex' },
    });
    await fireEvent.input(screen.getByLabelText('Launch command'), { target: { value: 'codex' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save agent' }));
    expect(screen.getByRole('alert').textContent).toContain('built-in');
    expect(io.rpc).not.toHaveBeenCalledWith('dev', 'agents_save', expect.anything());
  });

  it('removes a custom agent only after confirmation', async () => {
    io.rpc.mockImplementation(async (_host: string, method: string, args: { agents: unknown }) =>
      method === 'agents_read'
        ? { agents: [draft()], revision: 'r1' }
        : { agents: args.agents, revision: 'r2' },
    );
    show([agent('mine', { name: 'Mine', custom: true })]);
    await fireEvent.click(screen.getByRole('button', { name: 'Remove Mine' }));
    expect(io.rpc).not.toHaveBeenCalled();
    await fireEvent.click(screen.getByRole('button', { name: 'Confirm remove' }));
    await waitFor(() =>
      expect(io.rpc).toHaveBeenLastCalledWith('dev', 'agents_save', { agents: [], revision: 'r1' }),
    );
  });

  it('does not offer custom agents on native PowerShell hosts', () => {
    show([agent('codex')], 'powershell');
    expect(screen.queryByRole('button', { name: /Add your own agent/ })).toBeNull();
    expect(screen.getByText(/Native PowerShell hosts list the built-in agents only/)).toBeTruthy();
  });
});
