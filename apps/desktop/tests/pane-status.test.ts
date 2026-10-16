import { expect, it } from 'vitest';
import { sameStatus, terminalPresentation, unknownStatus, type PaneStatus } from '../src/lib/pane-status';

const shell = { name: 'Terminal', agent: 'shell', model: '', shell: true };
const tmux: PaneStatus = { attachment: 'tmux', nestedSsh: false, hostname: 'host', sessionId: '$3', stamp: '1:2:3', sessionName: 'work', agent: 'shell' };

it('updates a plain shell header after manually entering tmux without changing its launch target', () => {
  const pane = { ...shell, sessionId: '', stamp: undefined };
  expect(terminalPresentation(pane, tmux)).toEqual({ name: 'work', type: 'tmux', model: '' });
  expect(pane).toEqual({ ...shell, sessionId: '', stamp: undefined });
});
it('tracks tmux session switches, renames and a running agent', () => {
  expect(terminalPresentation(shell, { ...tmux, sessionName: 'renamed', agent: 'codex' }))
    .toEqual({ name: 'renamed', type: 'codex', model: '' });
});
it('returns to the saved shell label after detaching from tmux', () => {
  expect(terminalPresentation(shell, { ...tmux, attachment: 'plain', sessionName: null, agent: null }))
    .toEqual({ name: 'Terminal', type: 'shell', model: '' });
});
it('preserves saved managed-pane names and uncertain nested SSH destinations', () => {
  const saved = { name: 'My named pane', agent: 'claude', model: 'saved-model' };
  expect(terminalPresentation(saved, tmux)).toEqual({ name: 'My named pane', type: 'claude', model: 'saved-model' });
  expect(terminalPresentation(shell, unknownStatus)).toEqual({ name: 'Terminal', type: 'shell', model: '' });
  expect(terminalPresentation(shell, { ...tmux, nestedSsh: true })).toEqual({ name: 'Terminal', type: 'shell', model: '' });
});

it('treats an identical status poll as unchanged so views do not update', () => {
  expect(sameStatus(tmux, { ...tmux })).toBe(true);
  expect(sameStatus(tmux, { ...tmux, sessionName: 'other' })).toBe(false);
  expect(sameStatus(tmux, { ...tmux, paneDead: undefined })).toBe(true);
  expect(sameStatus(unknownStatus, { ...unknownStatus, paneDead: false })).toBe(false);
  expect(sameStatus(undefined, unknownStatus)).toBe(false);
});
