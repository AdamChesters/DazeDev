/**
 * The motes the voice throws off a pane border while dictating. Pure maths with no drawing, so
 * it can be tested: VoiceBorderWave feeds it the live wave and paints what it holds.
 *
 * A mote is born on the border, more often where the wave is tall, and far more often the
 * louder the voice is. Most hop off the border and land a little way along it; the rest shoot
 * into the pane and fade. While it lives a mote also rides the wave under it, so the crowd
 * jumps with the sound. Each rise in the voice sets off a burst, and when the voice drops the
 * motes it left die away quickly. In silence there are none.
 */

export type Random = () => number;

/**
 * Birth attempts a second for each 1000px of border at a level of about 0.58, which is where
 * `surge` is 1. An attempt succeeds as often as the wave is tall at the point it lands on.
 */
export const moteAttempts = 2600;
/** Extra attempts for each 1000px of border when the level jumps from silence to full. */
export const moteBurst = 900;
/** A mote lives between these two times, in milliseconds, while the voice holds. */
export const moteLifeMs = [260, 640] as const;
/** How much faster a mote ages for each unit the level has fallen since it was born. */
export const quietHaste = 3;
/** The part of its life a mote takes to appear. The rest is the fade. */
const rise = 0.12;
/** How deep the flicker cuts into a mote's brightness (0..1). */
const flickerDepth = 0.5;
/** The share of motes that hop along the border. The rest shoot into the pane. */
const hopShare = 0.6;
/** A hopping mote starts to fade this far through its life, as it comes back down. */
const landing = 0.72;

/** The furthest a mote slides along the border in its life, in pixels either way. */
export const maxSlide = 26;

/** How strongly a voice level (0..1) drives births: the square, so loud speech stands out. */
export function surge(level: number) {
  const bounded = Math.max(0, Math.min(1, level));
  return 3 * bounded * bounded;
}

/** Room for the motes of loud speech on a border `perimeter` pixels long. */
export function moteCapacity(perimeter: number) {
  return Math.max(64, Math.min(3200, Math.round(perimeter * 0.9)));
}

export class MoteField {
  /** Index of the border point each mote was born at. */
  readonly point: Int32Array;
  /** 0 at birth, 1 and over when the slot is free. */
  readonly age: Float32Array;
  /** How far it travels inward at most, as a share of the reach (0..1). */
  readonly travel: Float32Array;
  /** How far it slides along the border in the end, in pixels either way (up to `maxSlide`). */
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
  /** 1 for a mote that hops and lands, 0 for one that shoots in and fades. */
  readonly #hops: Uint8Array;
  /** How strongly it rides the wave under it (0..1). */
  readonly #ride: Float32Array;
  #heights: ArrayLike<number> = [];
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
    this.#hops = new Uint8Array(capacity);
    this.#ride = new Float32Array(capacity);
  }

  /**
   * Moves time on by `elapsedMs`. `heights` is the wave height (0..1) at each border point,
   * `level` the voice level now (0..1) and `perimeter` the length of the border in pixels.
   */
  step(heights: ArrayLike<number>, level: number, elapsedMs: number, perimeter: number) {
    const now = Math.max(0, Math.min(1, level));
    this.#heights = heights;
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
      const hops = this.random() < hopShare;
      this.#hops[slot] = hops ? 1 : 0;
      // Further for the tall part of the wave and for a loud voice, with a wide spread.
      const tall = Math.min(1, height * 2.5);
      this.travel[slot] = (0.3 + 0.7 * tall) * (0.35 + 0.65 * this.random()) * (0.55 + 0.45 * now);
      this.#ride[slot] = 0.25 + 0.5 * this.random();
      this.drift[slot] = (this.random() * 2 - 1) * (hops ? maxSlide : maxSlide * 0.4);
      this.size[slot] = this.random();
      this.glow[slot] = (0.5 + 0.45 * this.random()) * (0.45 + 0.55 * now);
      this.#flickers[slot] = 2.5 + 4 * this.random();
      this.#phase[slot] = this.random() * Math.PI * 2;
    }
  }

  /**
   * How far in mote `i` is now, as a share of the reach. A hopping mote goes out and comes
   * back; the others start fast and slow. Both are lifted by the wave under them as it moves.
   */
  depth(i: number) {
    const age = Math.min(1, this.age[i]);
    const left = 1 - age;
    const arc = Math.sin(Math.PI * age);
    const path = this.#hops[i] ? arc : 1 - left * left * left;
    const under = this.#heights[this.point[i]] || 0;
    const lift = this.#ride[i] * Math.min(1, under * 2.5) * (this.#hops[i] ? arc : left);
    return Math.min(1, this.travel[i] * path + lift);
  }

  /**
   * Opacity of mote `i` now, flickering all the way: a quick rise, then a fade to nothing. A
   * hopping mote stays bright until it comes back down.
   */
  opacity(i: number) {
    const age = this.age[i];
    if (age >= 1) return 0;
    const fade = this.#hops[i]
      ? Math.min(1, (1 - age) / (1 - landing))
      : ((1 - age) / (1 - rise)) ** 1.5;
    const shape = age < rise ? age / rise : fade;
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
