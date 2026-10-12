import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
const root = 'src-tauri/licenses';
await mkdir(root, { recursive: true });
const sources = [
  ['Tauri', 'https://raw.githubusercontent.com/tauri-apps/tauri/dev/LICENSE-MIT'],
  [
    'transcribe.cpp',
    'https://raw.githubusercontent.com/handy-computer/transcribe.cpp/be7a8b35e9ba2df20298bd26e32d53407c3bcbcd/LICENSE',
  ],
  ['llama.cpp', 'https://raw.githubusercontent.com/ggml-org/llama.cpp/b10964/LICENSE'],
  [
    'Silero',
    'https://raw.githubusercontent.com/snakers4/silero-vad/60b7ffa243625ebdc1070275a29f18c87843786a/LICENSE',
  ],
  [
    'S1-mini',
    'https://huggingface.co/superwhisper/s1-mini/resolve/88f6b15896c73bbb13a3b596e0afe8ea0d5150b4/LICENSE',
  ],
  ['Whisper', 'https://raw.githubusercontent.com/openai/whisper/main/LICENSE'],
  ['whisper.cpp', 'https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/LICENSE'],
  ['ggml', 'https://raw.githubusercontent.com/ggml-org/ggml/master/LICENSE'],
  ['portable-pty', 'https://raw.githubusercontent.com/wezterm/wezterm/main/LICENSE.md'],
  ['ONNX Runtime', 'https://raw.githubusercontent.com/microsoft/onnxruntime/main/LICENSE'],
];
let text = await readFile('THIRD-PARTY-NOTICES.md', 'utf8');
for (const [name, url] of sources) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(name + ' license fetch failed');
  text += '\n\n' + name + '\n' + url + '\n\n' + (await r.text());
}
for (const name of ['svelte', '@xterm/xterm', '@lucide/svelte', '@tauri-apps/api']) {
  const dir = path.join('node_modules', name);
  for (const file of await readdir(dir)) {
    if (/^licen[sc]e/i.test(file))
      text += '\n\n' + name + '\n\n' + (await readFile(path.join(dir, file), 'utf8'));
  }
}
await writeFile(path.join(root, 'THIRD-PARTY-LICENSES.txt'), text);
