import { releaseVersion } from '../release-version';
import { UpdateCheck } from '../update-checks';

export type UpdateInfo = { version: string; notes: string; size: number };
export type UpdateState =
  'unchecked' | 'checking' | 'current' | 'available' | 'error' | 'installing';

export interface UpdatesDeps {
  desktop: boolean;
  supported: () => boolean;
  modal: () => string;
  setModal: (modal: string) => void;
  /** True when an automatic prompt would not cover another interaction. */
  canPresent: () => boolean;
  /** True while dictation is running or unsent dictated text exists. */
  voiceBusy: () => boolean;
  check: () => Promise<UpdateInfo | null>;
  download: (version: string) => Promise<unknown>;
  install: () => Promise<unknown>;
  saveDevice: () => Promise<void>;
  onerror: (message: string) => void;
}

// Update check, prompt and install state.
export class Updates {
  available = $state<UpdateInfo | null>(null);
  status = $state('');
  busy = $state(false);
  progress = $state(0);
  prompt = $state(false);
  state = $state<UpdateState>('unchecked');
  automaticPending = $state(false);
  label = $derived.by(() =>
    !this.#deps.desktop || !this.#deps.supported()
      ? 'Manual updates'
      : this.state === 'checking'
        ? 'Checking…'
        : this.state === 'installing'
          ? 'Installing…'
          : this.state === 'error'
            ? 'Check failed'
            : this.available
              ? 'Update available'
              : this.state === 'current'
                ? 'Up to date'
                : 'Not checked',
  );
  #deps: UpdatesDeps;
  #return = '';
  #check = new UpdateCheck<UpdateInfo | null>();

  constructor(deps: UpdatesDeps) {
    this.#deps = deps;
  }

  open = () => {
    const modal = this.#deps.modal();
    this.automaticPending = false;
    this.#return = ['settings', 'support'].includes(modal) ? modal : '';
    this.#deps.setModal('');
    this.prompt = true;
  };

  close = () => {
    this.prompt = false;
    this.#deps.setModal(this.#return);
    this.#return = '';
  };

  /** Shows a prompt found by an automatic check once nothing else is open. */
  presentPending() {
    if (this.automaticPending && this.#deps.canPresent() && this.state === 'available') {
      this.automaticPending = false;
      this.#return = '';
      this.prompt = true;
    }
  }

  check = async (manual = false, settingsOpening = false) => {
    const deps = this.#deps;
    if (!deps.desktop || !deps.supported() || this.state === 'installing') return;
    const origin = deps.modal();
    const originPrompt = this.prompt;
    try {
      await this.#check.run(async () => {
        this.busy = true;
        this.state = 'checking';
        this.status = 'Checking for updates…';
        try {
          this.available = await deps.check();
          this.state = this.available ? 'available' : 'current';
          if (!this.available) this.automaticPending = false;
          this.status = this.available
            ? `Version ${releaseVersion(this.available.version)} is available.`
            : 'You are up to date.';
        } catch (e) {
          this.state = 'error';
          this.automaticPending = false;
          this.status = this.available
            ? `Unable to refresh updates: ${e}. Version ${releaseVersion(this.available.version)} was previously found.`
            : String(e);
        } finally {
          this.busy = false;
        }
        return this.available;
      });
      if (this.available && this.state === 'available') {
        const modal = deps.modal();
        // Each actual Settings opening can remind the user; Later returns without reopening it.
        if (settingsOpening && modal === 'settings') this.open();
        else if (manual) {
          // A completed request must not replace a dialog opened after it started.
          if (
            modal === origin &&
            this.prompt === originPrompt &&
            (modal === 'settings' || (originPrompt && deps.canPresent()))
          )
            this.open();
        } else if (!settingsOpening) this.automaticPending = true;
      }
    } catch (e) {
      this.state = 'error';
      this.status = String(e);
    }
  };

  install = async () => {
    const deps = this.#deps;
    if (!this.available || this.busy) return;
    if (deps.voiceBusy())
      throw new Error('Finish dictation and send, copy or discard held text before updating.');
    this.busy = true;
    this.state = 'installing';
    this.progress = 0;
    this.status = 'Downloading and verifying…';
    try {
      await deps.download(this.available.version);
      await deps.saveDevice();
      if (deps.voiceBusy())
        throw new Error(
          'Finish dictation before restarting to install. The downloaded update is retained.',
        );
      this.status = 'Restarting to install…';
      await deps.install();
    } catch (e) {
      this.status = String(e);
      deps.onerror(this.status);
      this.state = 'error';
      this.busy = false;
    }
  };
}
