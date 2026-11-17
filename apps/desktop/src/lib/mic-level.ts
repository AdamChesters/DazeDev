/** Microphone level maths shared by the listening waveform and the settings meter. */

export type RawLevel = { rms: number; peak: number };
export type LevelReader = () => RawLevel | null;
export type WaveReader = () => Float32Array | null;

/**
 * Microphone samples (16 kHz) per point of the waveform around a pane: a 150 Hz voice then
 * makes a wave about 140px long on any pane. Divide it for samples at a lower rate.
 */
export const waveDensity = 2.3;
/** Quietest level the meter shows, in dBFS. Speech normally sits between -40 and -10. */
export const meterFloorDb = -60;
/** A sample this close to full scale counts as clipping. */
export const clipThreshold = 0.98;
const clipHoldMs = 1200;
const peakHoldMs = 900;
/** Fall rates, in meter widths per second. */
const levelFall = 1.6;
const peakFall = 0.5;

export function measure(samples: ArrayLike<number>): RawLevel {
  let sum = 0;
  let peak = 0;
  for (let i = 0; i < samples.length; i++) {
    const value = Math.abs(samples[i]);
    if (value > peak) peak = value;
    sum += value * value;
  }
  return { rms: samples.length ? Math.sqrt(sum / samples.length) : 0, peak };
}

export function toDb(amplitude: number) {
  return amplitude > 0 ? 20 * Math.log10(amplitude) : -Infinity;
}

/** Maps an amplitude to 0..1 on a decibel scale, so quiet speech still moves the meter. */
export function toUnit(amplitude: number, floorDb = meterFloorDb) {
  const db = toDb(amplitude);
  if (db <= floorDb) return 0;
  return Math.min(1, (db - floorDb) / -floorDb);
}

/** Smooths raw readings: the level rises at once and falls steadily, the peak holds then falls. */
export class Meter {
  level = 0;
  peak = 0;
  db = -Infinity;
  clipped = false;
  #last = 0;
  #peakAt = 0;
  #clipAt = -Infinity;

  update(raw: RawLevel | null, now: number) {
    const seconds = this.#last ? Math.min(0.25, Math.max(0, (now - this.#last) / 1000)) : 0;
    this.#last = now;
    const target = raw ? toUnit(raw.rms) : 0;
    this.level = Math.max(target, this.level - levelFall * seconds);
    const peak = raw ? toUnit(raw.peak) : 0;
    if (peak >= this.peak) {
      this.peak = peak;
      this.#peakAt = now;
    } else if (now - this.#peakAt > peakHoldMs) {
      this.peak = Math.max(peak, this.peak - peakFall * seconds);
    }
    if (raw && raw.peak >= clipThreshold) this.#clipAt = now;
    this.clipped = now - this.#clipAt < clipHoldMs;
    this.db = raw ? toDb(raw.rms) : -Infinity;
    return this;
  }

  reset() {
    this.level = 0;
    this.peak = 0;
    this.db = -Infinity;
    this.clipped = false;
    this.#last = 0;
    this.#clipAt = -Infinity;
  }
}

/**
 * Shapes raw microphone samples into `out.length` displacements between -1 and 1 for drawing a
 * waveform around a closed loop. The wave keeps its real shape and its height follows `level`
 * (0..1). `density` is how many samples each point advances, so the wave looks the same on a
 * small and a large loop: the samples are laid forwards then backwards as often as needed, an
 * even number of times, which makes the loop join without a step.
 */
export function waveShape(
  samples: ArrayLike<number>,
  level: number,
  out: Float32Array,
  density = 1,
) {
  const count = out.length;
  const span = samples.length - 1;
  if (span < 1 || level <= 0) return out.fill(0);
  // Scaled so ordinary speech fills most of the height and only its sharpest peaks are clamped.
  // The floor stops silence being magnified.
  const gain = level / (2.2 * Math.max(measure(samples).rms, 10 ** (meterFloorDb / 20)));
  // An even number of passes over as much of the buffer as gives exactly `density`.
  const passes = Math.max(2, 2 * Math.ceil((count * density) / (2 * span)));
  const used = Math.min(span, (count * density) / passes);
  for (let i = 0; i < count; i++) {
    const travelled = (i / count) * passes * used;
    const pass = Math.floor(travelled / used);
    const within = travelled - pass * used;
    const position = pass % 2 ? used - within : within;
    const index = Math.floor(position);
    const mix = position - index;
    const sample = samples[index] * (1 - mix) + samples[Math.min(index + 1, span)] * mix;
    out[i] = Math.max(-1, Math.min(1, sample * gain));
  }
  return out;
}

/**
 * Where to start drawing so a steady voice holds still instead of sliding: the first upward
 * zero crossing within `limit` samples, or 0 when there is none.
 */
export function triggerOffset(samples: ArrayLike<number>, limit: number) {
  const end = Math.min(limit, samples.length - 1);
  for (let i = 1; i <= end; i++) if (samples[i - 1] < 0 && samples[i] >= 0) return i;
  return 0;
}

/** Plain-language reading for the settings meter. */
export function levelAdvice(peakDb: number, clipped: boolean) {
  if (clipped) return 'Too loud: the input is clipping. Lower the microphone level.';
  if (peakDb < -70) return 'No sound from this microphone.';
  if (peakDb < -45) return 'Very quiet. Raise the microphone level or move closer.';
  if (peakDb < -30) return 'Quiet, but usable.';
  return 'Good level.';
}
