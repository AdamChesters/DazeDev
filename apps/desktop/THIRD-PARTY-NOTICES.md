# Third-party components

DazeDev's first implementation uses the following independently maintained components. Their authors retain ownership and credit. This document does not assign their work to Adam Chesters.

| Component | License / source |
|---|---|
| Tauri | MIT / Apache-2.0 · https://github.com/tauri-apps/tauri |
| Svelte | MIT · https://github.com/sveltejs/svelte |
| xterm.js and official addons (fit, WebGL, web links, search, clipboard, Unicode 11, serialize, progress, image, ligatures) | MIT · https://github.com/xtermjs/xterm.js |
| Lucide | ISC · https://github.com/lucide-icons/lucide |
| ring 0.17.14 | ISC / Apache-2.0 / MIT · https://github.com/briansmith/ring · full bundled notices: `src-tauri/licenses/ring-0.17.14.txt` |
| portable-pty | MIT · https://github.com/wez/wezterm |
| transcribe.cpp | MIT · https://github.com/handy-computer/transcribe.cpp |
| llama.cpp | MIT · https://github.com/ggml-org/llama.cpp |
| Microsoft Visual C++ runtime | Microsoft redistributable runtime · https://learn.microsoft.com/cpp/windows/redistributing-visual-cpp-files |
| ONNX Runtime | MIT · https://github.com/microsoft/onnxruntime |
| ort Rust bindings | MIT / Apache-2.0 · https://github.com/pykeio/ort |
| Silero VAD | MIT · https://github.com/snakers4/silero-vad |
| Whisper models | MIT · https://github.com/openai/whisper |
| Parakeet Unified English | CC-BY-4.0 · https://huggingface.co/nvidia/parakeet-unified-en-0.6b |
| S1-mini by Superwhisper | Apache-2.0 with naming clause · https://huggingface.co/superwhisper/s1-mini/blob/main/LICENSE |

Model files are optional, separately downloaded assets. `src-tauri/resources/models.json` pins exact URLs, sizes and hashes. The S1-mini name and Superwhisper attribution are displayed in the app. The bundled llama.cpp release includes its license. Additional license texts are collected under `src-tauri/licenses` for inclusion in the installer.
