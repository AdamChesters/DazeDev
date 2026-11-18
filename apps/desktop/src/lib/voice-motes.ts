/**
 * The motes the voice throws off a pane border while dictating. Pure maths with no drawing, so
 * it can be tested: VoiceBorderWave feeds it the live wave and paints what it holds.
 *
 * A mote is born on the border, more often where the wave is tall, and far more often the
 * louder the voice is. It shoots a short way into the pane, flickers and fades. Each rise in
 * the voice sets off a burst, and when the voice drops the motes it left die away quickly, so
 * the crowd follows the voice up and down. In silence there are none.
 */

export type Random = () => number;

/**
 * Birth attempts a second for each 1000px of border at a level of about 0.58, which is where
 * `surge` is 1. An attempt succeeds as often as the wave is tall at the point it lands on.
 */
export const moteAttempts = 1100;
/** Extra attempts for each 1000px of border when the level jumps from silence to full. */
export const moteBurst = 500;
/** A mote lives between these two times, in milliseconds, while the voice holds. */
export const moteLifeMs = [280, 620] as const;
/** How much faster a mote ages for each unit the level has fallen since it was born. */
export const quietHaste = 3;
/** The part of its life a mote takes to appear. The rest is the fade. */
const rise = 0.12;
/** How deep the flicker cuts into a mote's brightness (0..1). */
const flickerDepth = 0.55;

/** How strongly a voice level (0..1) drives births: the square, so loud speech stands out. */
export function surge(level: number) {
  const bounded = Math.max(0, Math.min(1, level));
  return 3 * bounded * bounded;
}

/** Room for the motes of loud speech on a border `perimeter` pixels long. */
export function moteCapacity(perimeter: number) {
  return Math.max(64, Math.min(2000, Math.round(perimeter * 0.5)));
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
  /** The level each mote was born at. */
  readonly #born: Float32Array;
  /** Flickers in a life, and where in the first one it starts (radians). */
  readonly #flickers: Float32Array;
  readonly #phase: Float32Array;
  #carry = 0;
  #next = 0;
  #heard = 0;

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
    this.#born = new Float32Array(capacity);
    this.#flickers = new Float32Array(capacity);
    this.#phase = new Float32Array(capacity);
  }

  /**
   * Moves time on by `elapsedMs`. `heights` is the wave height (0..1) at each border point,
   * `level` the voice level now (0..1) and `perimeter` the length of the border in pixels.
   */
  step(heights: ArrayLike<number>, level: number, elapsedMs: number, perimeter: number) {
    const now = Math.max(0, Math.min(1, level));
    // A mote born in a loud moment goes quickly once the voice has dropped.
    for (let i = 0; i < this.capacity; i++) {
      if (this.age[i] >= 1) continue;
      const haste = 1 + quietHaste * Math.max(0, this.#born[i] - now);
      this.age[i] += elapsedMs * this.#rate[i] * haste;
    }
    const jump = Math.max(0, now - this.#heard);
    this.#heard = now;
    if (!heights.length || now <= 0) {
      this.#carry = 0;
      return;
    }
    this.#carry +=
      (moteAttempts * surge(now) * perimeter * elapsedMs) / 1e6 +
      (moteBurst * jump * perimeter) / 1000;
    const attempts = Math.floor(this.#carry);
    this.#carry -= attempts;
    for (let n = 0; n < attempts; n++) {
      const point = Math.min(heights.length - 1, Math.floor(this.random() * heights.length));
      const height = heights[point];
      if (this.random() >= height) continue;
      const slot = this.#free();
      if (slot < 0) {
        // Full: the attempts left over would only pile up.
        this.#carry = 0;
        return;
      }
      this.point[slot] = point;
      this.age[slot] = 0;
      this.#born[slot] = now;
      this.#rate[slot] = 1 / (moteLifeMs[0] + this.random() * (moteLifeMs[1] - moteLifeMs[0]));
      this.travel[slot] = Math.min(1, height) * (0.5 + 0.5 * this.random());
      this.drift[slot] = (this.random() * 2 - 1) * 12;
      this.size[slot] = this.random();
      this.glow[slot] = (0.5 + 0.45 * this.random()) * (0.45 + 0.55 * now);
      this.#flickers[slot] = 2.5 + 4 * this.random();
      this.#phase[slot] = this.random() * Math.PI * 2;
    }
  }

  /** How far mote `i` has travelled inward, as a share of the reach. It starts fast and slows. */
  depth(i: number) {
    const left = 1 - Math.min(1, this.age[i]);
    return this.travel[i] * (1 - left * left * left);
  }

  /** Opacity of mote `i` now: a quick rise, then a flickering fade to nothing. */
  opacity(i: number) {
    const age = this.age[i];
    if (age >= 1) return 0;
    const shape = age < rise ? age / rise : ((1 - age) / (1 - rise)) ** 1.5;
    const wave = 0.5 + 0.5 * Math.sin(this.#phase[i] + age * this.#flickers[i] * Math.PI * 2);
    return this.glow[i] * shape * (1 - flickerDepth * wave);
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
    this.#heard = 0;
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
