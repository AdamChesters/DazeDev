/** Precomputed paths keep animation off the UI thread without per-frame simulation or paint. */
const TAU = Math.PI * 2;
const wrap = (n: number) => ((n % 1) + 1) % 1;
export const energyCount = (width: number, height: number) =>
  Math.max(24, Math.min(80, Math.ceil((2 * (width + height)) / 24)));
export const makeEnergyMotes = (count: number) =>
  Array.from({ length: count }, (_, i) => ({
    seed: i * 2.399963,
    // Periods are twice those in alpha.10 and vary independently to avoid a synchronized orbit.
    speed: (24.8 + (i % 3) * 5.6) * (0.85 + ((i * 17) % 23) / 76),
  }));
export const energyMotes = makeEnergyMotes(60);

export function borderPoint(width: number, height: number, radius: number, progress: number) {
  const w = Math.max(1, width - 4),
    h = Math.max(1, height - 4);
  const r = Math.max(0.5, Math.min(radius, w / 2, h / 2));
  const a = Math.max(0, w - 2 * r),
    b = Math.max(0, h - 2 * r),
    arc = (Math.PI * r) / 2;
  let d = wrap(progress) * (2 * (a + b) + 4 * arc);
  const lengths = [a, arc, b, arc, a, arc, b, arc];
  let side = 0;
  while (side < 7 && d > lengths[side]) d -= lengths[side++];
  let x: number, y: number, angle: number;
  if (side % 2 === 0) {
    angle = (side * Math.PI) / 4;
    [x, y] =
      side === 0
        ? [r + d, 0]
        : side === 2
          ? [w, r + d]
          : side === 4
            ? [w - r - d, h]
            : [0, h - r - d];
  } else {
    angle = ((side - 1) * Math.PI) / 4 + d / r;
    const [cx, cy] =
      side === 1 ? [w - r, r] : side === 3 ? [w - r, h - r] : side === 5 ? [r, h - r] : [r, r];
    x = cx + Math.sin(angle) * r;
    y = cy - Math.cos(angle) * r;
  }
  return { x: x + 2, y: y + 2, angle };
}

function motePoint(w: number, h: number, radius: number, t: number, seed: number) {
  // Derivative ranges from near-zero to twice normal speed: smooth rests and surges.
  const phase = t + (Math.sin(TAU * t * 3 + seed) * 0.98) / (TAU * 3);
  const p = borderPoint(w, h, radius, phase + seed / TAU);
  const amplitude = Math.min(6, Math.max(1.5, h / 7));
  const normal = Math.sin(TAU * t * 9 + seed) * amplitude;
  const tangent = Math.cos(TAU * t * 9 + seed) * amplitude * 0.6;
  return {
    x: p.x - Math.sin(p.angle) * normal + Math.cos(p.angle) * tangent,
    y: p.y + Math.cos(p.angle) * normal + Math.sin(p.angle) * tangent,
  };
}

/** kind 0 is a mote; 1/2 are independent short-lived smoke puffs from its recent path. */
export function energyFrames(
  width: number,
  height: number,
  seed: number,
  radius = 8,
  kind = 0,
): Keyframe[] {
  const frames: Keyframe[] = [];
  const steps = 360;
  const brightness = 0.3 + 0.7 * (0.5 + 0.5 * Math.sin(seed * 1.7));
  for (let n = 0; n <= steps; n++) {
    const t = n / steps;
    const life = wrap(t * 12 + kind * 0.43 + seed / TAU);
    const age = kind ? life : 0;
    const p = motePoint(width, height, radius, t - age / 12, seed);
    // Smoke rises and curls independently of heading instead of forming a rigid tail.
    const drift = Math.min(14, Math.max(4, height / 3));
    p.x += kind ? Math.sin(age * TAU + seed + kind) * age * drift * 0.8 : 0;
    p.y -= kind ? age * drift + Math.sin(age * Math.PI + seed) * age * 3 : 0;
    const scale = kind ? 0.4 + age * 1.4 : 0.75 + 0.25 * Math.sin(t * TAU * 4 + seed);
    const pulse = 0.45 + 0.55 * (0.5 + 0.5 * Math.sin(t * TAU * 5 + seed));
    // Fade before each respawn so a puff never jumps visibly across its path.
    const fade = kind ? Math.sin(Math.PI * age) ** 2 * 0.48 : 1;
    frames.push({
      offset: t,
      transform: `translate3d(${p.x - 10}px,${p.y - 10}px,0) scale(${scale})`,
      opacity: brightness * pulse * fade,
    });
  }
  frames[steps] = { ...frames[0], offset: 1 };
  return frames;
}
