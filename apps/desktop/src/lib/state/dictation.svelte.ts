import { tick } from 'svelte';
import { get } from 'svelte/store';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { appActions, appShortcut } from '../app-shortcuts';
import { Capture } from '../audio';
import { call, desktop } from '../bridge';
import { diagnostic } from '../diagnostics';
import { applyFinalDictionary } from '../dictionary';
import { dictionaryState } from '../dictionary-store';
import { RecordingTail } from '../recording-tail';
import { ReviewHotkey } from '../review-hotkey';
import { defaultCommandShortcut, defaultShortcut, sameShortcut, type Shortcut } from '../shortcut';
import { deliverDictation, terminals } from '../terminals';
import { uid } from '../types';
import { voiceDestination, type CaptureMode } from '../voice-routing';
import type { App } from './app';

export type VoiceMode = 'dictation' | 'command';
export type VoiceTextEvent = {
  recording: string;
  text: string;
  raw?: string;
  final: boolean;
  error?: string;
  backend?: string;
};
export type VoicePhaseEvent = {
  recording: string;
  phase: string;
  part?: number;
  total?: number;
  words?: number;
};

const dictionary = () => get(dictionaryState).dictionary;

// Voice capture for terminal dictation, IDE commands and broadcasts: the
// recording itself, delivery to the terminal, held and recent dictation, and
// the dictation and command hotkeys.
export class Dictation {
  /** Dictated text not yet delivered. */
  text = $state('');
  raw = $state('');
  status = $state('idle');
  preparing = $state('Preparing…');
  cleanupPart = $state(0);
  cleanupTotal = $state(0);
  cleanupWords = $state(0);
  mode = $state<CaptureMode>('dictation');
  /** Pane receiving terminal dictation. */
  target = $state('');
  // Dictation that could not be tidied or delivered. It stays here until the
  // user sends, copies or discards it; new recordings are never blocked by it.
  held = $state<{ text: string; raw: string; target: string; error: string } | null>(null);
  recent = $state<{ text: string; raw: string; target: string; submitContext?: string } | null>(
    null,
  );
  capturingShortcut = $state<VoiceMode | null>(null);
  shortcutStatus = $state('');
  /** Pane to dictate into once the listen introduction closes. */
  listenIntroTarget = '';
  /** Ends the current recording without pressing Enter after delivery. */
  finishWithoutSubmit = false;
  shortcutHeld: VoiceMode | null = null;
  releasePending = false;
  readonly reviewHotkey = new ReviewHotkey();
  #capture: Capture | undefined;
  #recordingTail = new RecordingTail();
  #recordingId = '';
  #recoveryTimer: ReturnType<typeof setTimeout> | undefined;
  #app: App;

  active = $derived(this.mode === 'dictation' && this.status !== 'idle');
  listening = $derived(this.active && this.status === 'listening');
  statusText = $derived(
    this.status === 'loading'
      ? this.preparing
      : this.status === 'finishing-recording'
        ? 'Finishing recording…'
        : this.status === 'listening'
          ? 'Listening'
          : this.status === 'transcribing'
            ? 'Finishing transcription…'
            : this.status === 'cleanup-loading'
              ? 'Loading tidy-up model…'
              : this.status === 'cleanup'
                ? `Tidy-up pass · S1-mini${this.cleanupTotal > 1 ? ` · ${this.cleanupPart}/${this.cleanupTotal}` : ''}${this.cleanupWords ? ` · ${this.cleanupWords} words` : ''}`
                : 'Pasting…',
  );

  constructor(app: App) {
    this.#app = app;
    $effect(() => {
      if (
        this.active &&
        app.panes.workspaces.some((w) => w.panes.some((p) => p.id === this.target))
      ) {
        this.revealTarget(this.target);
        // Dialogs must not cover the receiving terminal during capture or final paste.
        if (app.ui.modal) app.ui.modal = '';
      }
    });
    $effect(() => {
      if (this.capturingShortcut && app.ui.modal !== 'settings') {
        this.capturingShortcut = null;
        void app.ui.task(() => this.configureShortcut());
      }
    });
    $effect(() => {
      if (
        this.mode === 'command' &&
        app.ui.modal !== 'command' &&
        (this.status === 'loading' ||
          this.status === 'listening' ||
          this.status === 'finishing-recording')
      )
        void this.cancel().catch(app.ui.fail);
    });
    $effect(() => {
      if (this.mode === 'broadcast' && this.status !== 'idle' && app.ui.modal !== 'broadcast')
        void app.ui.task(this.cancel);
    });
  }

  /** Stops timers when the app closes. */
  destroy() {
    this.reviewHotkey.cancel();
    clearTimeout(this.#recoveryTimer);
  }

  bindingFor = (mode: VoiceMode) => {
    const device = this.#app.ui.device;
    return mode === 'dictation'
      ? device.inputShortcut || defaultShortcut
      : device.commandShortcut || defaultCommandShortcut;
  };

  configureShortcut = async () => {
    // Disable the legacy OS-wide sampler. DOM events belong only to this window.
    if (desktop) await call('input_configure', { binding: null });
    this.shortcutStatus = 'Both hotkeys work only while DazeDev is focused.';
  };

  saveShortcut = async (
    binding: Shortcut,
    mode: VoiceMode = this.capturingShortcut || 'dictation',
  ) => {
    const device = this.#app.ui.device;
    if (sameShortcut(binding, this.bindingFor(mode === 'dictation' ? 'command' : 'dictation')))
      throw new Error('Choose different hotkeys for terminal dictation and IDE commands.');
    const clash = appActions.find((a) =>
      sameShortcut(appShortcut(device.appShortcuts, a.id), binding),
    );
    if (clash) throw new Error(`${binding.label} is already used by ${clash.label}.`);
    if (mode === 'dictation') device.inputShortcut = binding;
    else device.commandShortcut = binding;
    this.capturingShortcut = null;
    this.#app.deviceStore.persist();
    await this.configureShortcut();
  };

  captureShortcut = (mode: VoiceMode) => {
    this.capturingShortcut = mode;
    this.shortcutStatus = 'Press a key combination, or choose a mouse/device button.';
  };

  /** A dictation or command hotkey went down (pressed) or up. */
  inputEdge = async (pressed: boolean, mode: VoiceMode) => {
    const app = this.#app;
    const ui = app.ui;
    if (this.capturingShortcut || (pressed && !app.appHasFocus())) return;
    const destination = voiceDestination(mode, ui.modal);
    if (this.status !== 'idle' && this.mode !== destination) return;
    if (app.broadcast.busy || (app.broadcast.result && ui.modal === 'broadcast')) return;
    if (ui.device.inputMode !== 'hold') {
      if (
        pressed &&
        mode === 'dictation' &&
        destination === 'dictation' &&
        this.status === 'idle' &&
        this.recent?.submitContext &&
        !ui.modal
      ) {
        const reviewed = this.recent;
        const target = app.panes.activePane?.id;
        this.reviewHotkey.tap(
          () => {
            if (app.appHasFocus() && !ui.modal && app.panes.activePane?.id === target)
              void ui.task(() => this.record(destination));
          },
          () => {
            if (!app.appHasFocus() || ui.modal || app.panes.activePane?.id !== reviewed.target) {
              ui.notice = 'Select the terminal containing the reviewed dictation before sending.';
              return;
            }
            const expected = reviewed.submitContext!;
            reviewed.submitContext = undefined;
            void ui.task(async () => {
              const terminal = terminals.get(reviewed.target);
              if (!terminal?.submitReviewed)
                throw new Error(
                  'Terminal is unavailable. The dictation remains available to copy.',
                );
              await terminal.submitReviewed(expected);
              terminal.focus?.();
            });
          },
        );
        return;
      }
      if (pressed) await this.record(destination);
      return;
    }
    if (pressed) {
      if (this.shortcutHeld || this.status !== 'idle') return;
      this.shortcutHeld = mode;
      this.releasePending = false;
      await this.record(destination);
      if (this.releasePending && String(this.status) === 'listening')
        await this.record(destination);
    } else if (this.shortcutHeld === mode) {
      this.shortcutHeld = null;
      this.releasePending = true;
      if (this.status === 'listening') await this.record(destination);
    }
  };

  revealTarget(target: string) {
    const ui = this.#app.ui;
    const device = ui.device;
    const w = this.#app.panes.owner(target);
    if (!w) throw new Error('The dictation terminal was closed. Your text is preserved.');
    device.activeWorkspace = w.id;
    device.activeHost = w.hostId;
    w.focused = target;
    if (ui.maximize && ui.maximize !== target) ui.maximize = '';
    if (device.collapsedHosts?.[w.hostId])
      device.collapsedHosts = { ...device.collapsedHosts, [w.hostId]: false };
    return w;
  }

  async #focusTarget(target: string) {
    const ui = this.#app.ui;
    this.revealTarget(target);
    ui.modal = '';
    ui.paneMenu = null;
    if (this.#app.detached.panes.has(target)) {
      await call('terminal_window_focus', { paneId: target });
      return;
    }
    if (desktop) {
      const window = getCurrentWindow();
      await window.show();
      await window.unminimize();
      await window.setFocus();
    }
    await tick();
    const pane = document.querySelector<HTMLElement>(`[data-pane-id="${CSS.escape(target)}"]`);
    if (!pane || !pane.getClientRects().length || pane.getBoundingClientRect().width <= 0)
      throw new Error('The dictation terminal is not visible. Your text is preserved.');
    pane.querySelector<HTMLTextAreaElement>('.xterm-helper-textarea')?.focus();
  }

  submitText = async () => {
    const app = this.#app;
    const ui = app.ui;
    if (!this.text.trim()) return;
    if (this.mode === 'command') {
      app.commands.text = applyFinalDictionary(this.raw, this.text, dictionary());
      ui.modal = 'command';
      return;
    }
    const pane = app.panes.workspaces.flatMap((w) => w.panes).find((p) => p.id === this.target);
    if (!pane)
      throw new Error(
        'The recording destination was closed. Use Copy dictation to recover the text.',
      );
    const terminal = terminals.get(pane.id);
    if (!terminal) throw new Error('Terminal is unavailable. Your dictation is preserved.');
    // PTY delivery does not require OS/window focus. A focus failure is advisory.
    await this.#focusTarget(pane.id).catch(() => {});
    await deliverDictation(
      terminal,
      this.text,
      ui.device.voice.automatic && !this.finishWithoutSubmit,
    );
    this.recent = {
      text: this.text,
      raw: this.raw,
      target: pane.id,
      submitContext:
        ui.device.voice.automatic && !this.finishWithoutSubmit ? undefined : terminal.context(),
    };
    if (this.recent.submitContext && ui.device.inputMode !== 'hold')
      ui.notice = `Review the text in ${pane.name}. Double-tap ${this.bindingFor('dictation').label} to send.`;
    clearTimeout(this.#recoveryTimer);
    this.#recoveryTimer = setTimeout(() => {
      this.recent = null;
    }, 120_000);
    this.text = '';
    this.raw = '';
  };

  #hold(e: unknown) {
    const message = e instanceof Error ? e.message : String(e);
    if (this.text.trim())
      this.held = {
        text: this.text,
        raw: this.raw || this.text,
        target: this.target,
        error: message,
      };
    else this.#app.ui.error = message;
    this.text = '';
    this.raw = '';
  }

  sendHeld = async () => {
    const held = this.held;
    if (!held || this.status !== 'idle') return;
    this.held = null;
    this.mode = 'dictation';
    this.target = held.target;
    this.finishWithoutSubmit = false;
    this.text = held.text;
    this.raw = held.raw;
    try {
      await this.submitText();
    } catch (e) {
      this.#hold(e);
    }
  };

  copyHeld = () => {
    if (this.held) void navigator.clipboard.writeText(this.held.text).catch(this.#app.ui.fail);
  };

  /** Starts a recording, or finishes the one that is listening. */
  record = async (mode: CaptureMode, target = this.#app.panes.activePane?.id || '') => {
    const app = this.#app;
    const ui = app.ui;
    if (!app.appHasFocus()) return;
    this.reviewHotkey.cancel();
    if (mode === 'dictation' && this.status === 'idle' && !ui.device.listenOnboarded) {
      this.listenIntroTarget = target;
      ui.modal = 'listen-intro';
      if (desktop && app.detached.focus) {
        await getCurrentWindow().show();
        await getCurrentWindow().unminimize();
        await getCurrentWindow().setFocus();
      }
      return;
    }
    if (this.status !== 'idle' && this.mode !== mode) return;
    if (this.status === 'listening') {
      const finishingRecording = this.#recordingId;
      const finishingCapture = this.#capture;
      this.status = 'finishing-recording';
      if (!(await this.#recordingTail.wait()) || this.#recordingId !== finishingRecording) return;
      this.status = 'transcribing';
      // Flush the final partial PCM frame and await every queued IPC write before ASR finalization.
      await finishingCapture?.stop();
      if (this.#recordingId !== finishingRecording) return;
      await call('voice_stop');
      return;
    }
    if (this.status !== 'idle' || app.voiceModels.switching || app.panes.filesLocked) return;
    if (mode === 'dictation' && !target) throw new Error('Select a terminal first.');
    this.mode = mode;
    this.finishWithoutSubmit = false;
    this.target = mode === 'dictation' ? target : '';
    if (mode === 'broadcast') {
      app.broadcast.text = '';
      app.broadcast.snapshot = null;
      ui.modal = 'broadcast';
    }
    if (mode === 'command') {
      app.commands.text = '';
      app.commands.review = null;
      app.commands.error = '';
      ui.modal = 'command';
    }
    this.text = '';
    this.raw = '';
    this.status = 'loading';
    this.preparing = 'Preparing…';
    this.#recordingId = uid();
    const startedRecording = this.#recordingId;
    const startedAt = performance.now();
    const mark = (event: string) =>
      diagnostic(
        `voice.start.${event}`,
        startedRecording,
        Math.round(performance.now() - startedAt),
      );
    mark('requested');
    void tick().then(() =>
      requestAnimationFrame(() => {
        if (this.#recordingId === startedRecording) mark('preparing-render-frame');
      }),
    );
    let startedCapture: Capture | undefined;
    try {
      // A hotkey during startup warmup waits for it instead of being discarded.
      if (app.voiceModels.cacheTask) this.preparing = 'Waiting for voice models…';
      await app.voiceModels.cacheTask?.catch(() => {});
      if (this.#recordingId !== startedRecording || !app.appHasFocus()) return;
      mark('preload-wait-complete');
      if (mode === 'dictation') {
        this.preparing = 'Focusing terminal…';
        await this.#focusTarget(target);
      }
      if (this.#recordingId !== startedRecording) return;
      mark('focus-complete');
      this.preparing = 'Preparing speech model…';
      await call('voice_start', {
        recording: this.#recordingId,
        model: ui.device.voice.model,
        backend: ui.device.voice.backend,
        cleanup: ui.device.voice.cleanup && mode !== 'command',
      });
      if (this.#recordingId !== startedRecording) return;
      mark('model-ready');
      this.preparing = 'Starting microphone…';
      startedCapture = new Capture();
      this.#capture = startedCapture;
      await startedCapture.start(
        ui.device.voice.microphone,
        (e) => {
          ui.fail(e);
          // Drain accepted audio on backpressure; do not discard the dictation.
          if (this.status === 'listening') void ui.task(() => this.record(mode));
        },
        startedRecording,
      );
      mark('capture-start-returned');
      if (this.#recordingId !== startedRecording) {
        await startedCapture.stop();
        return;
      }
      this.status = 'listening';
      mark('listening-state');
      void tick().then(() =>
        requestAnimationFrame(() => {
          if (this.#recordingId === startedRecording) mark('listening-render-frame');
        }),
      );
    } catch (e) {
      await startedCapture?.stop().catch(() => {});
      if (this.#recordingId !== startedRecording) return;
      await call('voice_cancel').catch(() => {});
      this.#recordingId = '';
      this.status = 'idle';
      throw e;
    }
  };

  cancel = async () => {
    this.#recordingId = '';
    this.#recordingTail.cancel();
    await this.#capture?.stop();
    await call('voice_cancel');
    this.text = '';
    this.raw = '';
    this.status = 'idle';
  };

  onPhase = (v: VoicePhaseEvent) => {
    if (v.recording !== this.#recordingId) return;
    if (this.status !== 'finishing-recording') this.status = v.phase;
    this.cleanupPart = v.part || 0;
    this.cleanupTotal = v.total || 0;
    this.cleanupWords = v.words || 0;
  };

  onText = (v: VoiceTextEvent) => {
    const app = this.#app;
    const ui = app.ui;
    if (v.recording !== this.#recordingId) return;
    this.text = v.text;
    this.raw = v.raw || v.text;
    if (this.mode === 'broadcast') app.broadcast.text = v.text;
    if (this.mode === 'command') {
      app.commands.text = v.text;
      app.commands.review = null;
      if (v.error) app.commands.error = v.error;
    }
    if (!v.final) return;
    if (v.error) {
      this.#recordingTail.cancel();
      this.#recordingId = '';
      this.status = 'idle';
      if (this.mode === 'dictation') this.#hold(v.error);
      else {
        ui.error = v.error;
        this.text = '';
        this.raw = '';
      }
      void this.#capture?.stop();
      void call('voice_cancel');
    } else if (this.mode === 'broadcast') {
      this.status = 'idle';
      app.broadcast.text = applyFinalDictionary(this.raw, this.text, dictionary());
      this.text = '';
      this.raw = '';
      void tick().then(app.broadcast.focusInput);
    } else if (this.mode === 'command') {
      this.status = 'idle';
      app.commands.text = applyFinalDictionary(this.raw, this.text, dictionary());
      this.text = '';
      ui.modal = 'command';
      void tick().then(app.commands.focusInput);
    } else {
      this.text = applyFinalDictionary(this.raw, this.text, dictionary());
      this.status = 'pasting';
      void ui.task(async () => {
        try {
          await this.submitText();
        } catch (e) {
          this.#hold(e);
        } finally {
          if (this.#recordingId === v.recording) this.status = 'idle';
        }
      });
    }
  };
}
