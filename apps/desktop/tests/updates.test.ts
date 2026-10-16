import { describe, expect, it, vi } from 'vitest';
import { releaseVersion } from '../src/lib/release-version';
import { Updates, type UpdateInfo, type UpdatesDeps } from '../src/lib/state/updates.svelte';

const update: UpdateInfo = { version: '1.2.3', notes: 'Notes', size: 1_000_000 };

function setup(overrides: Partial<UpdatesDeps> = {}) {
  let modal = '';
  const deps: UpdatesDeps = {
    desktop: true,
    supported: () => true,
    modal: () => modal,
    setModal: (value) => {
      modal = value;
    },
    canPresent: () => !modal,
    voiceBusy: () => false,
    check: vi.fn(async () => update),
    download: vi.fn(async () => undefined),
    install: vi.fn(async () => undefined),
    saveDevice: vi.fn(async () => undefined),
    onerror: vi.fn(),
    ...overrides,
  };
  return {
    updates: new Updates(deps),
    deps,
    modal: () => modal,
    setModal: (value: string) => (modal = value),
  };
}

describe('Updates', () => {
  it('labels manual-update builds', () => {
    expect(setup({ supported: () => false }).updates.label).toBe('Manual updates');
    expect(setup({ desktop: false }).updates.label).toBe('Manual updates');
    expect(setup().updates.label).toBe('Not checked');
  });

  it('queues an automatic prompt and presents it when nothing else is open', async () => {
    const { updates, setModal } = setup();
    setModal('command');
    await updates.check();
    expect(updates.state).toBe('available');
    expect(updates.label).toBe('Update available');
    expect(updates.status).toBe(`Version ${releaseVersion('1.2.3')} is available.`);
    expect(updates.automaticPending).toBe(true);
    updates.presentPending();
    expect(updates.prompt).toBe(false);
    setModal('');
    updates.presentPending();
    expect(updates.prompt).toBe(true);
    expect(updates.automaticPending).toBe(false);
  });

  it('reports up to date and clears a pending prompt', async () => {
    const { updates } = setup({ check: vi.fn(async () => null) });
    updates.automaticPending = true;
    await updates.check();
    expect(updates.state).toBe('current');
    expect(updates.label).toBe('Up to date');
    expect(updates.automaticPending).toBe(false);
  });

  it('keeps a previous result when a check fails', async () => {
    const check = vi.fn().mockResolvedValueOnce(update).mockRejectedValueOnce('offline');
    const { updates, setModal } = setup({ check });
    setModal('settings');
    await updates.check(false, true);
    expect(updates.prompt).toBe(true);
    await updates.check();
    expect(updates.state).toBe('error');
    expect(updates.label).toBe('Check failed');
    expect(updates.status).toBe(
      `Unable to refresh updates: offline. Version ${releaseVersion('1.2.3')} was previously found.`,
    );
  });

  it('opens from Settings and returns there when closed', async () => {
    const { updates, setModal, modal } = setup();
    setModal('settings');
    await updates.check(true);
    expect(updates.prompt).toBe(true);
    expect(modal()).toBe('');
    updates.close();
    expect(updates.prompt).toBe(false);
    expect(modal()).toBe('settings');
  });

  it('does not replace a dialog opened while a manual check ran', async () => {
    let resolve: ((value: UpdateInfo) => void) | undefined;
    const { updates, setModal } = setup({
      check: () => new Promise<UpdateInfo>((r) => (resolve = r)),
    });
    setModal('settings');
    const running = updates.check(true);
    setModal('command');
    await vi.waitFor(() => expect(resolve).toBeDefined());
    resolve!(update);
    await running;
    expect(updates.prompt).toBe(false);
  });

  it('shares one request between overlapping checks', async () => {
    const { updates, deps } = setup();
    await Promise.all([updates.check(), updates.check()]);
    expect(deps.check).toHaveBeenCalledTimes(1);
  });

  it('refuses to install while dictation is active', async () => {
    const { updates } = setup({ voiceBusy: () => true });
    await updates.check();
    await expect(updates.install()).rejects.toThrow('Finish dictation');
    expect(updates.state).toBe('available');
  });

  it('saves the device before installing', async () => {
    const order: string[] = [];
    const { updates } = setup({
      download: vi.fn(async () => order.push('download')),
      saveDevice: vi.fn(async () => void order.push('save')),
      install: vi.fn(async () => order.push('install')),
    });
    await updates.check();
    await updates.install();
    expect(order).toEqual(['download', 'save', 'install']);
    expect(updates.status).toBe('Restarting to install…');
    expect(updates.state).toBe('installing');
  });

  it('reports a failed install and allows a retry', async () => {
    const { updates, deps } = setup({ download: vi.fn().mockRejectedValue('bad signature') });
    await updates.check();
    await updates.install();
    expect(updates.state).toBe('error');
    expect(updates.busy).toBe(false);
    expect(deps.onerror).toHaveBeenCalledWith('bad signature');
  });
});
