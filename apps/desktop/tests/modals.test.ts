// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import type { Component } from 'svelte';
import { installDom } from './support/dom';
import { focusDialog } from '../src/lib/focus';
import Worktree from '../src/components/modals/Worktree.svelte';
import CloseTerminal from '../src/components/modals/CloseTerminal.svelte';
import RenameSession from '../src/components/modals/RenameSession.svelte';
import DeleteHost from '../src/components/modals/DeleteHost.svelte';
import type { Host } from '../src/lib/types';

// App.svelte renders each modal body inside a dialog that uses focusDialog.
// Props with accessors stand in for App's bind: directives.
function dialog<P extends Record<string, unknown>>(body: Component<P>, props: P) {
  const view = render(body as Component<Record<string, unknown>>, { props });
  focusDialog(view.container as HTMLElement);
  return view;
}
const bound = <T>(value: T) => ({ value });
const task = vi.fn(async (fn: () => Promise<void>) => fn());

beforeEach(() => {
  installDom();
  task.mockClear();
});

describe('worktree script confirmation', () => {
  function worktree(question: { script: string } | null) {
    const state = bound(question);
    const props = {
      branch: 'feature',
      busy: false,
      createWorktree: vi.fn(async () => {}),
      destination: '../feature',
      sendWorktree: vi.fn(async (_runScript?: boolean) => {}),
      task,
      get worktreeQuestion() {
        return state.value;
      },
      set worktreeQuestion(value) {
        state.value = value;
      },
    };
    return { ...dialog(Worktree, props), props, state };
  }
  it('names the script and runs it only after "Run script"', async () => {
    const { props } = worktree({ script: 'scripts/new-worktree.sh' });
    expect(screen.getByText(/This repository has scripts\/new-worktree.sh/)).toBeTruthy();
    const run = screen.getByRole('button', { name: 'Run script' });
    await waitFor(() => expect(document.activeElement).toBe(run));
    await fireEvent.click(run);
    expect(props.sendWorktree).toHaveBeenCalledExactlyOnceWith(true);
  });
  it('can create the worktree without the script', async () => {
    const { props } = worktree({ script: 'scripts/new-worktree.sh' });
    await fireEvent.click(screen.getByRole('button', { name: 'Create without script' }));
    expect(props.sendWorktree).toHaveBeenCalledExactlyOnceWith(false);
  });
  it('Cancel clears the question and runs nothing', async () => {
    const { props, state } = worktree({ script: 'scripts/new-worktree.sh' });
    await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(state.value).toBeNull();
    expect(props.sendWorktree).not.toHaveBeenCalled();
    expect(task).not.toHaveBeenCalled();
  });
  it('disables every choice while busy', async () => {
    const view = worktree({ script: 's.sh' });
    await view.rerender({ busy: true });
    for (const name of ['Run script', 'Create without script', 'Cancel'])
      expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('submits the form through createWorktree, which may then ask', async () => {
    const { props } = worktree(null);
    const branch = screen.getByRole('textbox', { name: 'Branch' });
    await waitFor(() => expect(document.activeElement).toBe(branch));
    await fireEvent.submit(branch.closest('form')!);
    expect(props.createWorktree).toHaveBeenCalledOnce();
    expect(props.sendWorktree).not.toHaveBeenCalled();
  });
});

describe('close terminal confirmation', () => {
  function closing() {
    const state = bound('close-terminal');
    const props = {
      get modal() {
        return state.value;
      },
      set modal(value: string) {
        state.value = value;
      },
      confirmPendingClose: vi.fn(),
    };
    dialog(CloseTerminal, props);
    return { props, state };
  }
  it('focuses Cancel first, and Cancel closes the dialog only', async () => {
    const { props, state } = closing();
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    await waitFor(() => expect(document.activeElement).toBe(cancel));
    await fireEvent.click(cancel);
    expect(state.value).toBe('');
    expect(props.confirmPendingClose).not.toHaveBeenCalled();
  });
  it('closes the terminal on confirm', async () => {
    const { props } = closing();
    await fireEvent.click(screen.getByRole('button', { name: 'Close terminal' }));
    expect(props.confirmPendingClose).toHaveBeenCalledOnce();
  });
});

describe('rename session', () => {
  it('edits the bound name and submits it', async () => {
    const state = bound('old');
    const renameSession = vi.fn(async () => {});
    dialog(RenameSession, {
      busy: false,
      renameSession,
      task,
      get renameValue() {
        return state.value;
      },
      set renameValue(value: string) {
        state.value = value;
      },
    });
    const input = screen.getByRole('textbox', { name: 'Session name' }) as HTMLInputElement;
    await waitFor(() => expect(document.activeElement).toBe(input));
    expect(input.value).toBe('old');
    await fireEvent.input(input, { target: { value: 'new-name' } });
    expect(state.value).toBe('new-name');
    await fireEvent.submit(input.closest('form')!);
    expect(renameSession).toHaveBeenCalledOnce();
  });
});

describe('delete host', () => {
  const host = { id: 'h1', name: 'Build host' } as Host;
  it('names the host, and deletes only on confirm', async () => {
    const state = bound('delete-host');
    const deleteHost = vi.fn(async (_host: Host) => {});
    dialog(DeleteHost, {
      busy: false,
      deleteHost,
      deleteHostTarget: host,
      task,
      get modal() {
        return state.value;
      },
      set modal(value: string) {
        state.value = value;
      },
    });
    expect(screen.getByText('Build host')).toBeTruthy();
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    await waitFor(() => expect(document.activeElement).toBe(cancel));
    await fireEvent.click(cancel);
    expect(state.value).toBe('');
    expect(deleteHost).not.toHaveBeenCalled();
    await fireEvent.click(screen.getByRole('button', { name: 'Delete host' }));
    expect(deleteHost).toHaveBeenCalledExactlyOnceWith(host);
  });
});
