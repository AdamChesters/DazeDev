#!/usr/bin/env bash
set -euo pipefail
version=$(node -p "require('./package.json').version")
platform=$(node -p "process.platform + '-' + process.arch")
root="$PWD/artifacts/DazeDev_${version}_${platform}"
if [[ -e "$root" || -e "$root.zip" || -e "$root.tar.gz" ]]; then
  echo "Package output already exists. Use a fresh artifacts directory to avoid including stale files or client data." >&2
  exit 1
fi
mkdir -p "$root"
if [[ "$OSTYPE" == darwin* ]]; then
  cp -R src-tauri/target/release/bundle/macos/DazeDev.app "$root/"
  # Ad-hoc signatures permit native execution; these are not Apple notarization.
  find "$root/DazeDev.app/Contents/Resources/runtime" -type f \( -name '*.dylib' -o -name 'llama-server' \) -exec codesign --force --sign - {} \;
  codesign --force --deep --sign - "$root/DazeDev.app"
  codesign --verify --deep --strict "$root/DazeDev.app"
else
  cp src-tauri/target/release/DazeDev "$root/"
  cp -R src-tauri/runtime src-tauri/licenses "$root/"
  cat > "$root/Start DazeDev.sh" <<'SH'
#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export LD_LIBRARY_PATH="$ROOT/runtime${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$ROOT/DazeDev" "$@"
SH
  chmod +x "$root/Start DazeDev.sh"
fi
cat > "$root/README.txt" <<'TXT'
DazeDev cross-platform test build
Extract the entire folder to a writable location. Keep the folder together.
macOS: open DazeDev.app. Linux: run ./Start\ DazeDev.sh
Settings, models and recovery files are in data/ beside the app. Do not install
into /usr/bin or move only the .app into a read-only Applications directory.
Mac builds are ad-hoc signed, not Apple-notarized. macOS may require approval
under System Settings > Privacy & Security > Open Anyway. Do not disable Gatekeeper.
Linux target: Mint 22.x x64 (Ubuntu 24.04), with WebKitGTK 4.1, GTK3 and libgomp.
Install missing runtime packages with:
sudo apt install libwebkit2gtk-4.1-0 libgtk-3-0t64 libgomp1
Local sessions need python3 and tmux; remote SSH targets need them on the host.
Voice: Parakeet or Whisper, Silero and S1-mini run locally. Models download in app.
These first test builds use CPU voice inference. No GPU performance claim is made.
Hotkeys work while the app is focused; system-wide non-Windows hotkeys are not implemented.
Updates on macOS/Linux are manual. Keep data/ when replacing application files.
TXT
if [[ "$OSTYPE" == darwin* ]]; then
  ditto -c -k --sequesterRsrc --keepParent "$root" "$root.zip"
else
  tar -C artifacts -czf "$root.tar.gz" "$(basename "$root")"
fi
