/**
 * The live microphone waveform for a terminal shown in its own window. The microphone is open
 * only in the workspace window, so that window sends a small packed copy of the recent samples
 * to the terminal window about 30 times a second while dictation is listening into it.
 */
import { measure, type RawLevel, type WaveReader } from './mic-level';

/** Input samples averaged into each sent value: 2048 samples at 16 kHz become 512 at 4 kHz. */
export const waveFactor = 4;
/** About 30 frames a second. */
export const waveIntervalMs = 33;
/** A window with no newer frame than this lets its wave fall flat instead of freezing. */
export const waveStaleMs = 250;
/** Longest packed wave a window accepts, in base64 characters (3072 samples). */
const maxEncoded = 4096;

export type WaveMessage = {
  kind: 'voice-wave';
  /** Level of the full-rate samples, so the window's meter matches a docked pane exactly. */
  rms: number;
  peak: number;
  /** Largest absolute value among the averaged samples; each byte is a fraction of it. */
  scale: number;
  /** One signed byte per averaged sample, base64. */
  data: string;
};

/** Packs microphone samples into a WaveMessage. Reuses its buffers between frames. */
export class WaveEncoder {
  #averaged = new Float32Array(0);
  #bytes = new Uint8Array(0);
  constructor(private factor = waveFactor) {}

  encode(samples: ArrayLike<number>): WaveMessage | null {
    const factor = this.factor;
    const count = Math.floor(samples.length / factor);
    if (count < 2) return null;
    if (this.#bytes.length !== count) {
      this.#averaged = new Float32Array(count);
      this.#bytes = new Uint8Array(count);
    }
    // Averaging each group keeps the voice and drops detail too fine to see around a window.
    let scale = 0;
    for (let i = 0; i < count; i++) {
      let sum = 0;
      for (let j = i * factor; j < (i + 1) * factor; j++) sum += samples[j];
      const value = sum / factor;
      this.#averaged[i] = value;
      if (Math.abs(value) > scale) scale = Math.abs(value);
    }
    // Scaled to the loudest value so quiet speech keeps its shape in 8 bits.
    for (let i = 0; i < count; i++)
      this.#bytes[i] = scale ? Math.round((this.#averaged[i] / scale) * 127) & 0xff : 0;
    const { rms, peak } = measure(samples);
    return {
      kind: 'voice-wave',
      rms,
      peak,
      scale,
      data: btoa(String.fromCharCode.apply(null, this.#bytes as unknown as number[])),
    };
  }
}

/**
 * Unpacks the frames a terminal window receives and hands them to its waveform. Readings stop
 * (return null) once the last frame is older than `staleMs`, so the wave falls flat when frames
 * stop arriving.
 */
export class WaveFeed {
  #samples = new Float32Array(0);
  #level: RawLevel = { rms: 0, peak: 0 };
  #at = -Infinity;
  constructor(
    private now: () => number = () => performance.now(),
    private staleMs = waveStaleMs,
  ) {}

  /** Takes one frame. Returns false, keeping nothing, when the frame is malformed. */
  receive(message: unknown): boolean {
    if (!message || typeof message !== 'object') return false;
    const { rms, peak, scale, data } = message as Record<string, unknown>;
    if (typeof data !== 'string' || data.length > maxEncoded) return false;
    if (![rms, peak, scale].every((value) => typeof value === 'number' && Number.isFinite(value)))
      return false;
    let binary: string;
    try {
      binary = atob(data);
    } catch {
      return false;
    }
    if (binary.length < 2) return false;
    if (this.#samples.length !== binary.length) this.#samples = new Float32Array(binary.length);
    const unit = (scale as number) / 127;
    // Each byte is signed: shifting it to the top of a 32-bit integer and back restores the sign.
    for (let i = 0; i < binary.length; i++)
      this.#samples[i] = ((binary.charCodeAt(i) << 24) >> 24) * unit;
    this.#level.rms = rms as number;
    this.#level.peak = peak as number;
    this.#at = this.now();
    return true;
  }

  /** Forgets the last frame, so a new recording never starts with an old wave. */
  clear() {
    this.#at = -Infinity;
  }

  #fresh() {
    return this.now() - this.#at <= this.staleMs;
  }

  /** The latest samples, at 1/`waveFactor` of the microphone rate, or null when stale. */
  read: WaveReader = () => (this.#fresh() ? this.#samples : null);
  /** The level measured on the full-rate samples, or null when stale. */
  level = (): RawLevel | null => (this.#fresh() ? this.#level : null);
}

/**
 * The detached pane that should receive the live waveform, or '' for none: only while
 * dictation is listening and its target is shown in its own window.
 */
export function waveTarget(listening: boolean, target: string, detached: (id: string) => boolean) {
  return listening && target && detached(target) ? target : '';
}

/**
 * Sends packed frames from `read` through `send` every `interval` ms, skipping a frame while
 * the previous one is still on its way. Returns the stop function.
 */
export function streamWave(
  read: WaveReader,
  send: (message: WaveMessage) => Promise<unknown>,
  interval = waveIntervalMs,
): () => void {
  const encoder = new WaveEncoder();
  let busy = false;
  let stopped = false;
  const timer = setInterval(() => {
    if (busy || stopped) return;
    const samples = read();
    const message = samples && encoder.encode(samples);
    if (!message) return;
    busy = true;
    void send(message)
      .catch(() => {})
      .finally(() => {
        busy = false;
      });
  }, interval);
  return () => {
    stopped = true;
    clearInterval(timer);
  };
}
