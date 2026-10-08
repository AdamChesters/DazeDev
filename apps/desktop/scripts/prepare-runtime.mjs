import { mkdir, writeFile, copyFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
const win = process.platform === 'win32';
const platform = `${process.platform}-${process.arch}`;
const variants = {
  'win32-x64': [
    'win-cpu-x64.zip',
    '917f39c076402c421224824607397af20f53625a60defc20e8dd22446bf4c5d7',
    'win-x64-1.22.0.zip',
    '174c616efc0271194488642a72f1a514e01487da4dfe84c49296d66e40ebe0da',
  ],
  'linux-x64': [
    'ubuntu-x64.tar.gz',
    '9abf88aea48a55d0f80edb1ee20220b186848cca0b4e919d71518cfd7ca67443',
    'linux-x64-1.22.0.tgz',
    '8344d55f93d5bc5021ce342db50f62079daf39aaafb5d311a451846228be49b3',
  ],
  'darwin-arm64': [
    'macos-arm64.tar.gz',
    '033c845c1df9bf945ff37bb193238b40910b2244be3e1e637b2ceb5878f1a6f5',
    'osx-arm64-1.22.0.tgz',
    'cab6dcbd77e7ec775390e7b73a8939d45fec3379b017c7cb74f5b204c1a1cc07',
  ],
  'darwin-x64': [
    'macos-x64.tar.gz',
    '03430a394d0a169a5e6d8f01c09f48cf58eb026af6fc95940a4a528e2e50cf38',
    'osx-x86_64-1.22.0.tgz',
    'e4ec94a7696de74fb1b12846569aa94e499958af6ffa186022cfde16c9d617f0',
  ],
};
const variant = variants[platform];
if (!variant) throw new Error(`Unsupported runtime platform: ${platform}`);
const runtimes = [
  {
    name: 'llama.cpp b10964',
    url:
      'https://github.com/ggml-org/llama.cpp/releases/download/b10964/llama-b10964-bin-' +
      variant[0],
    sha: variant[1],
  },
  {
    name: 'ONNX Runtime 1.22.0',
    url:
      'https://github.com/microsoft/onnxruntime/releases/download/v1.22.0/onnxruntime-' +
      variant[2],
    sha: variant[3],
  },
];
const dest = path.resolve('src-tauri/runtime');
await mkdir(dest, { recursive: true });
for (const runtime of runtimes) {
  const temp = path.join(os.tmpdir(), 'dazedev-runtime-' + runtime.sha.slice(0, 12));
  await mkdir(temp, { recursive: true });
  const response = await fetch(runtime.url);
  if (!response.ok) throw new Error(`Runtime download: ${response.status}`);
  const data = Buffer.from(await response.arrayBuffer());
  if (createHash('sha256').update(data).digest('hex') !== runtime.sha)
    throw new Error(`${runtime.name} checksum mismatch`);
  const archive = path.join(temp, path.basename(runtime.url));
  await writeFile(archive, data);
  const extract = path.join(temp, 'extract');
  await mkdir(extract, { recursive: true });
  if (win)
    execFileSync('powershell', [
      '-NoProfile',
      '-Command',
      `Expand-Archive -LiteralPath '${archive.replaceAll("'", "''")}' -DestinationPath '${extract.replaceAll("'", "''")}' -Force`,
    ]);
  else execFileSync('tar', ['-xzf', archive, '-C', extract]);
  async function collect(folder) {
    for (const ent of await readdir(folder, { withFileTypes: true })) {
      const p = path.join(folder, ent.name);
      if (ent.isDirectory()) await collect(p);
      else if (
        ent.name === 'llama-server.exe' ||
        ent.name === 'llama-server' ||
        /\.(dll|dylib|metallib|so(?:\.\d+)*)$/.test(ent.name) ||
        /license|notice/i.test(ent.name)
      ) {
        const name = /license|notice/i.test(ent.name)
          ? runtime.name.replaceAll(' ', '-') + '-' + ent.name
          : ent.name;
        await copyFile(p, path.join(dest, name));
        if (!win) execFileSync('chmod', ['+x', path.join(dest, name)]);
      }
    }
  }
  await collect(extract);
}
await writeFile(
  path.join(dest, 'runtime-version.txt'),
  runtimes.map((r) => `${r.name}\n${r.sha}\n`).join('\n'),
);
console.log('Verified and prepared bundled speech and cleanup runtimes.');
