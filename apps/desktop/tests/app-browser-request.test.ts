// @vitest-environment jsdom
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import type { Component } from 'svelte';
import { calls, emit, handlers, resetTauri } from './support/tauri';
import { installDom } from './support/dom';

vi.mock('@tauri-apps/api/core', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/event', () => import('./support/tauri'));
vi.mock('@tauri-apps/api/window', () => import('./support/tauri'));
vi.mock('@lucide/svelte', () => import('./support/icons'));
vi.mock('@xterm/xterm', () => import('./support/xterm'));
vi.mock('@xterm/addon-fit', () => import('./support/addons'));
vi.mock('@xterm/addon-webgl', () => import('./support/addons'));
vi.mock('@xterm/addon-image', () => import('./support/addons'));
vi.mock('@xterm/addon-ligatures', () => import('./support/addons'));

const host = {
  id: 'h1',
  name: 'Build host',
  kind: 'ssh',
  address: 'build.invalid',
  user: 'dev',
  key: '',
  jump: '',
  distro: '',
  socket: '',
};
let App: Component;
beforeAll(async () => {
  // App.svelte and its components take a few seconds to compile.
  App = (await import('../src/App.svelte')).default;
}, 60000);
beforeEach(() => {
  installDom();
  resetTauri();
  handlers.set('device_load', () => ({
    hosts: [host],
    voiceSetupDismissed: true,
    lastChangelogVersion: '0.3.9',
  }));
  handlers.set('client_info', () => ({
    version: '0.3.9',
    dataPath: '',
    migration: '',
    updateSupported: false,
  }));
  handlers.set('ssh_aliases', () => []);
  handlers.set('model_list', () => []);
});

let next = 0;
async function started() {
  render(App);
  await waitFor(() => expect(calls('client_info')).toHaveLength(1));
  const ask = (url = 'https://example.com/docs') => {
    const request = { requestId: `r${++next}`, hostId: 'h1', url };
    emit('browser-open-request', request);
    return request;
  };
  return ask;
}
const decisions = () => calls('browser_open_decision');

describe('browser-open request from a host', () => {
  it('names the host and URL and focuses the first control', async () => {
    const ask = await started();
    ask('https://example.com/login');
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByRole('heading').textContent).toContain('Build host wants to open');
    expect(dialog.textContent).toContain('https://example.com/login');
    const trust = within(dialog).getByRole('checkbox');
    await waitFor(() => expect(document.activeElement).toBe(trust));
    expect(decisions()).toEqual([]);
  });
  it('opens once without trusting the host by default', async () => {
    const ask = await started();
    const request = ask();
    await fireEvent.click(await screen.findByRole('button', { name: 'Open' }));
    await waitFor(() =>
      expect(decisions()).toEqual([
        { requestId: request.requestId, allow: true, trustHost: false },
      ]),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
  it('trusts the host only with Open and the checkbox ticked', async () => {
    const ask = await started();
    const first = ask();
    const second = ask();
    expect((await screen.findByRole('alertdialog')).textContent).toContain('1 more request');
    await fireEvent.click(screen.getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: "Don't open" }));
    await waitFor(() => expect(decisions()).toHaveLength(1));
    // The tick does not carry over to the next request.
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
    await fireEvent.click(screen.getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    await waitFor(() =>
      expect(decisions()).toEqual([
        { requestId: first.requestId, allow: false, trustHost: false },
        { requestId: second.requestId, allow: true, trustHost: true },
      ]),
    );
  });
  it('declines with Escape', async () => {
    const ask = await started();
    const request = ask();
    await screen.findByRole('alertdialog');
    await fireEvent.click(screen.getByRole('checkbox'));
    await fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' });
    await waitFor(() =>
      expect(decisions()).toEqual([
        { requestId: request.requestId, allow: false, trustHost: false },
      ]),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
  it('ignores malformed requests', async () => {
    await started();
    emit('browser-open-request', { requestId: 'bad', hostId: 'h1' });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
});
