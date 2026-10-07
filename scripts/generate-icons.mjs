import { mkdtemp, copyFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
const temp = await mkdtemp(join(tmpdir(), 'dazedev-icons-'));
try {
  execFileSync(
    process.execPath,
    ['node_modules/@tauri-apps/cli/tauri.js', 'icon', 'public/brand.svg', '--output', temp],
    { stdio: 'inherit' },
  );
  for (const file of ['icon.png', 'icon.ico', 'icon.icns'])
    await copyFile(join(temp, file), join('src-tauri/icons', file));
} finally {
  await rm(temp, { recursive: true, force: true });
}
