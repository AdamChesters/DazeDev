import { build } from "../apps/desktop/node_modules/esbuild/lib/main.js";
import { cp, mkdir, rm, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const out = resolve(process.argv[2]);
await mkdir(out, { recursive: true });
await mkdir(resolve(out, "assets"), { recursive: true });
await cp(resolve(root, "shared/releases/changelog.json"), resolve(out, "assets/changelog.json"));
const svg = await readFile(
  resolve(root, "apps/desktop/public/brand.svg"),
  "utf8",
);
const colors = {
  background: svg.match(/<rect[^>]*fill="(#[0-9a-f]+)"/i)[1],
  accent: svg.match(/<rect[^>]*stroke="(#[0-9a-f]+)"/i)[1],
  foreground: svg.match(/<path[^>]*stroke="(#[0-9a-f]+)"/i)[1],
};
const kotlin = resolve(out, "kotlin/com/adamchesters/dazedev/mobile/ui/theme");
await mkdir(kotlin, { recursive: true });
await writeFile(
  resolve(kotlin, "BrandPalette.kt"),
  `package com.adamchesters.dazedev.mobile.ui.theme
import androidx.compose.ui.graphics.Color
object BrandPalette {
    val Background = Color(0xFF${colors.background.slice(1)})
    val Foreground = Color(0xFF${colors.foreground.slice(1)})
    val Accent = Color(0xFF${colors.accent.slice(1)})
}
`,
);
await build({
  define: { DAZEDEV_COLORS: JSON.stringify(colors) },
  entryPoints: [resolve(root, "apps/android/terminal/index.js")],
  bundle: true,
  minify: true,
  outfile: resolve(out, "assets/terminal/index.js"),
  nodePaths: [resolve(root, "apps/desktop/node_modules")],
  format: "iife",
  target: "chrome100",
});
await cp(
  resolve(root, "apps/android/terminal/index.html"),
  resolve(out, "assets/terminal/index.html"),
);
const drawable = resolve(out, "res/drawable");
const mipmap = resolve(out, "res/mipmap-anydpi-v26");
await rm(resolve(out, "res"), { recursive: true, force: true });
await mkdir(drawable, { recursive: true });
await mkdir(mipmap, { recursive: true });
function attributes(tag) {
  return Object.fromEntries(
    [...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]]),
  );
}
const shapes = [
  ...svg.matchAll(/<(rect|path|circle)\b[^>]*\/?>(?:<\/[^>]+>)?/g),
].map((m) => ({ kind: m[1], ...attributes(m[0]) }));
if (shapes.length !== 4)
  throw new Error(
    "Logo geometry changed; review the Android vector conversion.",
  );
function pathData(shape) {
  if (shape.kind === "path") return shape.d;
  if (shape.kind === "circle") {
    const x = +shape.cx,
      y = +shape.cy,
      r = +shape.r;
    return `M${x - r},${y} a${r},${r} 0,1,0 ${2 * r},0 a${r},${r} 0,1,0 ${-2 * r},0`;
  }
  const x = +(shape.x || 0),
    y = +(shape.y || 0),
    w = +shape.width,
    h = +shape.height,
    r = +(shape.rx || 0);
  return `M${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h - r} Q${x + w},${y + h} ${x + w - r},${y + h} H${x + r} Q${x},${y + h} ${x},${y + h - r} V${y + r} Q${x},${y} ${x + r},${y} Z`;
}
function vector(monochrome = false) {
  const paths = shapes
    .filter((_, i) => !monochrome || i > 0)
    .map((shape) => {
      const fill =
        shape.fill === "none"
          ? "#00000000"
          : monochrome
            ? "#FFFFFFFF"
            : shape.fill || "#000000";
      return (
        `<path android:pathData="${pathData(shape)}" android:fillColor="${fill}"` +
        (shape.stroke
          ? ` android:strokeColor="${monochrome ? "#FFFFFFFF" : shape.stroke}" android:strokeWidth="${shape["stroke-width"] || 1}"`
          : "") +
        (shape["stroke-linecap"]
          ? ` android:strokeLineCap="${shape["stroke-linecap"]}"`
          : "") +
        (shape["stroke-linejoin"]
          ? ` android:strokeLineJoin="${shape["stroke-linejoin"]}"`
          : "") +
        "/>"
      );
    })
    .join("\n");
  return `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="384" android:viewportHeight="384"><group android:translateX="64" android:translateY="64">${paths}</group></vector>`;
}
await writeFile(resolve(drawable, "dazedev_logo.xml"), vector());
await writeFile(resolve(drawable, "dazedev_monochrome.xml"), vector(true));
const adaptive = `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/dazedev_icon_background"/><foreground android:drawable="@drawable/dazedev_logo"/><monochrome android:drawable="@drawable/dazedev_monochrome"/></adaptive-icon>`;
await writeFile(resolve(mipmap, "ic_launcher.xml"), adaptive);
await mkdir(resolve(out, "res/values"), { recursive: true });
await writeFile(
  resolve(out, "res/values/brand.xml"),
  `<resources><color name="dazedev_icon_background">${colors.background}</color></resources>`,
);
await cp(
  resolve(root, "apps/desktop/node_modules/@xterm/xterm/LICENSE"),
  resolve(out, "assets/xterm-license.txt"),
);

for (const name of ["addon-fit", "addon-unicode11"])
  await cp(
    resolve(root, `apps/desktop/node_modules/@xterm/${name}/LICENSE`),
    resolve(out, `assets/${name}-license.txt`),
  );
