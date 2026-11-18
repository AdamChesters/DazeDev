/**
 * The motes the voice throws off a pane border while dictating. Pure maths with no drawing, so
 * it can be tested: VoiceBorderWave feeds it the live wave and paints what it holds.
 *
 * A mote is born on the border, more often where the wave is tall. It drifts a short way into
 * the pane and fades. Their density along the border therefore traces the waveform, and in
 * silence there are none.
 */

export type Random = () => number;

/**
 * Birth attempts a second for each 1000px of border. An attempt succeeds as often as the wave
 * is tall at the point it lands on, so louder speech makes more motes.
 */
export const moteAttempts = 700;
/** A mote lives between these two times, in milliseconds. */
export const moteLifeMs = [450, 800] as const;
/** The part of its life a mote takes to appear. The rest is the fade. */
const rise = 0.15;

/** Room for the motes of loud speech on a border `perimeter` pixels long. */
export function moteCapacity(perimeter: number) {
  return Math.max(48, Math.min(1400, Math.round(perimeter * 0.3)));
}

export class MoteField {
  /** Index of the border point each mote was born at. */
  readonly point: Int32Array;
  /** 0 at birth, 1 and over when the slot is free. */
  readonly age: Float32Array;
  /** How far it travels inward in the end, as a share of the reach (0..1). */
  readonly travel: Float32Array;
  /** How far it slides along the border in the end, in pixels either way. */
  readonly drift: Float32Array;
  /** Radius, as a share of the largest (0..1). */
  readonly size: Float32Array;
  /** Brightest opacity it reaches (0..1). */
  readonly glow: Float32Array;
  readonly #rate: Float32Array;
  #carry = 0;
  #next = 0;

  constructor(
    readonly capacity: number,
    private random: Random = Math.random,
  ) {
    this.point = new Int32Array(capacity);
    this.age = new Float32Array(capacity).fill(1);
    this.travel = new Float32Array(capacity);
    this.drift = new Float32Array(capacity);
    this.size = new Float32Array(capacity);
    this.glow = new Float32Array(capacity);
    this.#rate = new Float32Array(capacity);
  }

  /**
   * Moves time on by `elapsedMs`. `heights` is the wave height (0..1) at each border point,
   * `level` the voice level (0..1) and `perimeter` the length of the border in pixels.
   */
  step(heights: ArrayLike<number>, level: number, elapsedMs: number, perimeter: number) {
    for (let i = 0; i < this.capacity; i++)
      if (this.age[i] < 1) this.age[i] += elapsedMs * this.#rate[i];
    if (!heights.length || level <= 0) return;
    this.#carry += (moteAttempts * perimeter * elapsedMs) / 1e6;
    const attempts = Math.floor(this.#carry);
    this.#carry -= attempts;
    for (let n = 0; n < attempts; n++) {
      const point = Math.min(heights.length - 1, Math.floor(this.random() * heights.length));
      const height = heights[point];
      if (this.random() >= height) continue;
      const slot = this.#free();
      if (slot < 0) return;
      this.point[slot] = point;
      this.age[slot] = 0;
      this.#rate[slot] = 1 / (moteLifeMs[0] + this.random() * (moteLifeMs[1] - moteLifeMs[0]));
      this.travel[slot] = Math.min(1, height) * (0.45 + 0.55 * this.random());
      this.drift[slot] = (this.random() * 2 - 1) * 6;
      this.size[slot] = this.random();
      this.glow[slot] = (0.45 + 0.45 * this.random()) * (0.55 + 0.45 * Math.min(1, level));
    }
  }

  /** How far mote `i` has travelled inward, as a share of the reach. It slows as it goes. */
  depth(i: number) {
    const left = 1 - Math.min(1, this.age[i]);
    return this.travel[i] * (1 - left * left);
  }

  /** Opacity of mote `i` now: a quick rise, then a slower fade to nothing. */
  opacity(i: number) {
    const age = this.age[i];
    if (age >= 1) return 0;
    const shape = age < rise ? age / rise : ((1 - age) / (1 - rise)) ** 1.5;
    return this.glow[i] * shape;
  }

  /** Number of motes alive. */
  get live() {
    let live = 0;
    for (let i = 0; i < this.capacity; i++) if (this.age[i] < 1) live++;
    return live;
  }

  clear() {
    this.age.fill(1);
    this.#carry = 0;
  }

  #free() {
    for (let n = 0; n < this.capacity; n++) {
      const slot = (this.#next + n) % this.capacity;
      if (this.age[slot] >= 1) {
        this.#next = (slot + 1) % this.capacity;
        return slot;
      }
    }
    return -1;
  }
}
