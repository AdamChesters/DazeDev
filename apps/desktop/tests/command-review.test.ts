import { expect, it, vi } from 'vitest';
import { commandCatalogue, parseCommand } from '../src/lib/commands';
import { consumeReview, reviewCommand } from '../src/lib/command-review';
import {
  defaultShortcut,
  defaultCommandShortcut,
  mouseMatches,
  sameShortcut,
} from '../src/lib/shortcut';
const context = {
  hostId: 'dev',
  hostName: 'Devbox',
  workspaceId: 'work',
  workspaceName: 'Planning',
  paneId: 'pane',
  paneName: 'Terminal 1',
  paneContext: 'dev:session:stamp',
};
it.each([
  ['Open a new workspace', 1, 'shell', 'workspace', false],
  ['Open four new terminals', 4, 'shell', 'current', false],
  ['Open 3 new terminals with Codex', 3, 'codex', 'current', false],
  ['Open three new terminals with codecs', 3, 'codex', 'current', false],
  ['Open a terminal in Codex and show repo picker', 1, 'codex', 'current', true],
])(
  'interprets %s with explicit destination and count',
  (text, count, agent, destination, chooseRepo) => {
    expect(parseCommand(text as string, [], 'dev')).toMatchObject({
      count,
      agent,
      destination,
      chooseRepo,
    });
  },
);
it.each([
  'do not open 3 terminals',
  "don't open a terminal",
  'open 3 or 4 terminals',
  'open 3 terminals and destroy this session',
  'open terminals with Codex and Claude',
  'open thirteen terminals',
  'open 3 terminals on UnknownHost',
  'open bananas in terminals',
  'please maybe open terminals',
  '',
])('rejects unsupported or ambiguous text: %s', (text) => {
  expect(() => parseCommand(text, [], 'dev')).toThrow();
});
it('every discoverable catalogue example is understood', () => {
  for (const { example } of commandCatalogue)
    expect(() => parseCommand(example, [], 'dev')).not.toThrow();
});
it('interpretation and decline have no action effects; only Implement consumes once', () => {
  const effect = vi.fn();
  const text = 'Open four new terminals with Codex';
  const reviewed = reviewCommand(text, parseCommand(text, [], 'dev'), context);
  expect(reviewed.summary).toContain('Open 4 terminals in workspace “Planning” on Devbox');
  expect(reviewed.summary).toContain('Start codex in each terminal');
  expect(effect).not.toHaveBeenCalled();
  // Decline discards this object; a freshly interpreted review is required.
  const accepted = reviewCommand(text, parseCommand(text, [], 'dev'), context);
  effect(consumeReview(accepted, text, context));
  expect(effect).toHaveBeenCalledOnce();
  expect(() => consumeReview(accepted, text, context)).toThrow('already');
});
it('does not execute changed text or a moved/replaced destination', () => {
  const text = 'Open four new terminals';
  const reviewed = reviewCommand(text, parseCommand(text, [], 'dev'), context);
  expect(() => consumeReview(reviewed, 'Open five new terminals', context)).toThrow('changed');
  expect(() =>
    consumeReview(reviewed, text, { ...context, paneContext: 'another-session' }),
  ).toThrow('changed');
  expect(reviewed.consumed).toBe(false);
});
it('rejects adding terminals without a current workspace rather than silently creating one', () => {
  const text = 'Open four new terminals';
  expect(() =>
    reviewCommand(text, parseCommand(text, [], 'dev'), { ...context, workspaceId: '' }),
  ).toThrow('workspace');
});
it('uses different defaults and exact modifiers for configurable mouse shortcuts', () => {
  expect(sameShortcut(defaultShortcut, defaultCommandShortcut)).toBe(false);
  const binding = { ...defaultShortcut, key: 5, ctrl: false, shift: false };
  const back = {
    button: 3,
    ctrlKey: false,
    altKey: false,
    shiftKey: false,
    metaKey: false,
  } as MouseEvent;
  expect(mouseMatches(back, binding)).toBe(true);
  expect(mouseMatches({ ...back, button: 4 }, binding)).toBe(false);
  expect(mouseMatches({ ...back, ctrlKey: true }, binding)).toBe(false);
});
