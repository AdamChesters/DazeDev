// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { installDom } from './support/dom';

const tests = vi.hoisted(
  () => [] as { device: string; stop: ReturnType<typeof vi.fn>; ended: () => void }[],
);
const failStart = vi.hoisted(() => ({ message: '' }));
vi.mock('../src/lib/audio', () => ({
  MicrophoneTest: class {
    stop = vi.fn(async () => {});
    level = () => ({ rms: 0.1, peak: 0.2 });
    async start(device: string, ended: () => void) {
      if (failStart.message) throw new Error(failStart.message);
      tests.push({ device, stop: this.stop, ended });
    }
  },
}));

import VoiceHotkeysSection from '../src/components/modals/settings/VoiceHotkeysSection.svelte';
import type { Device } from '../src/lib/types';

function section(voiceStatus = 'idle') {
  const device = {
    inputMode: 'toggle',
    voice: { microphone: 'usb', cleanup: false, automatic: false },
  } as unknown as Device;
  const props = {
    bindingFor: () => ({ key: 1, ctrl: false, alt: false, shift: false, meta: false, label: 'F8' }),
    captureShortcut: vi.fn(),
    capturingShortcut: null,
    changeVoiceModel: vi.fn(async () => {}),
    device,
    microphoneError: '',
    microphoneLoading: false,
    microphones: [],
    refreshMicrophones: vi.fn(async () => {}),
    saveShortcut: vi.fn(async () => {}),
    shortcutStatus: '',
    switchingVoice: false,
    task: vi.fn(async (fn: () => Promise<void>) => fn()),
    voiceCacheLoading: false,
    voiceStatus,
  };
  return { ...render(VoiceHotkeysSection, { props }), props };
}

beforeEach(() => {
  installDom();
  tests.length = 0;
  failStart.message = '';
  vi.useRealTimers();
});

describe('settings microphone test', () => {
  it('opens the selected microphone, shows a meter and stops on request', async () => {
    section();
    expect(screen.queryByRole('meter')).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Test microphone' }));
    await waitFor(() => expect(tests).toHaveLength(1));
    expect(tests[0].device).toBe('usb');
    expect(screen.getByRole('meter', { name: 'Microphone level' })).toBeTruthy();
    expect(screen.getByText(/Nothing is recorded/)).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Stop test' }));
    expect(tests[0].stop).toHaveBeenCalledOnce();
    expect(screen.queryByRole('meter')).toBeNull();
    expect(screen.getByRole('button', { name: 'Test microphone' })).toBeTruthy();
  });

  it('releases the microphone when Settings closes', async () => {
    const { unmount } = section();
    await fireEvent.click(screen.getByRole('button', { name: 'Test microphone' }));
    await waitFor(() => expect(tests).toHaveLength(1));
    unmount();
    expect(tests[0].stop).toHaveBeenCalledOnce();
  });

  it('stops when dictation starts and cannot start during dictation', async () => {
    const { rerender } = section();
    await fireEvent.click(screen.getByRole('button', { name: 'Test microphone' }));
    await waitFor(() => expect(tests).toHaveLength(1));
    await rerender({ voiceStatus: 'loading' });
    await waitFor(() => expect(tests[0].stop).toHaveBeenCalledOnce());
    expect(screen.queryByRole('meter')).toBeNull();
    expect(
      (screen.getByRole('button', { name: 'Test microphone' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('stops by itself after 30 seconds', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    section();
    await fireEvent.click(screen.getByRole('button', { name: 'Test microphone' }));
    await vi.waitFor(() => expect(tests).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(29_000);
    expect(tests[0].stop).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1_500);
    expect(tests[0].stop).toHaveBeenCalledOnce();
  });

  it('reports a microphone that stops or cannot be opened', async () => {
    section();
    await fireEvent.click(screen.getByRole('button', { name: 'Test microphone' }));
    await waitFor(() => expect(tests).toHaveLength(1));
    tests[0].ended();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/stopped/));
    expect(tests[0].stop).toHaveBeenCalledOnce();
    failStart.message = 'Permission denied';
    await fireEvent.click(screen.getByRole('button', { name: 'Test microphone' }));
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe('Microphone test: Permission denied'),
    );
    expect(screen.queryByRole('meter')).toBeNull();
  });
});
