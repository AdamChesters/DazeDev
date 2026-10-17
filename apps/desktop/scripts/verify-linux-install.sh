#!/usr/bin/env bash
# Install and start the DazeDev Linux packages on a clean Debian-family system.
# Run as root in a disposable container or VM: it installs packages and creates
# a test user. Usage: verify-linux-install.sh ARTIFACT_DIR RESULT_DIR
# ARTIFACT_DIR holds the .deb, the .AppImage and optionally voice_smoke + jfk.wav.
set -euo pipefail
artifacts=$(realpath "$1")
results=$(realpath -m "$2")
scripts=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$results"
export DEBIAN_FRONTEND=noninteractive
step() { printf '\n== %s\n' "$*"; }

step "System"
grep -E '^(PRETTY_NAME|VERSION_CODENAME|UBUNTU_CODENAME)=' /etc/os-release | tee "$results/os.txt"
ldd --version | head -1 | tee -a "$results/os.txt"

deb=$(ls "$artifacts"/*.deb)
appimage=$(ls "$artifacts"/*.AppImage)
apt-get update -qq

step "Install $(basename "$deb") with apt resolving dependencies"
dpkg-deb -f "$deb" Package Version Depends Recommends | tee "$results/deb-control.txt"
apt-get install -y --no-install-recommends --simulate "$deb" | grep '^Inst ' | tee "$results/apt-resolved.txt"
apt-get install -y "$deb" > "$results/apt-install.log" 2>&1 || { cat "$results/apt-install.log"; exit 1; }
dpkg -s dazedev | grep -E '^(Status|Version)' | tee -a "$results/deb-control.txt"
dpkg -L dazedev > "$results/deb-files.txt"
test -x /usr/bin/DazeDev
runtime=$(dirname "$(grep '/runtime/llama-server$' "$results/deb-files.txt")")
test -x "$runtime/llama-server"
test -f "$runtime/libonnxruntime.so"
grep -l '^Exec=DazeDev' /usr/share/applications/*.desktop | tee -a "$results/deb-control.txt"

step "Shared libraries"
for f in /usr/bin/DazeDev "$runtime/llama-server" "$runtime"/*.so*; do
  if ldd "$f" | grep 'not found'; then echo "Missing library for $f" >&2; exit 1; fi
done
echo "All installed ELF files resolve their libraries." | tee "$results/ldd.txt"
"$runtime/llama-server" --version 2>&1 | tail -2 | tee -a "$results/ldd.txt"

step "Test-only tools (not package dependencies)"
apt-get install -y -qq xvfb xauth webkit2gtk-driver pulseaudio pulseaudio-utils dbus-x11 \
  imagemagick x11-utils xdotool > /dev/null
id tester > /dev/null 2>&1 || useradd -m -s /bin/bash tester
# Containers may restrict unprivileged user namespaces needed by the WebKit sandbox.
sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 > /dev/null 2>&1 || true
work=/home/tester/work
rm -rf "$work"; mkdir -p "$work"
cp "$scripts/verify-linux-startup.py" "$appimage" "$work/"
[[ -f "$artifacts/voice_smoke" ]] && cp "$artifacts/voice_smoke" "$artifacts/jfk.wav" "$work/"
chown -R tester: "$work"
chmod +x "$work"/*.AppImage
cat > "$work/as-tester.sh" <<'SH'
#!/usr/bin/env bash
# Session for the test user: private runtime folder, D-Bus and a null audio source.
set -euo pipefail
export XDG_RUNTIME_DIR=$(mktemp -d)
pulseaudio --daemonize=yes --exit-idle-time=-1 --log-target=stderr 2>/dev/null
for _ in $(seq 50); do pactl info > /dev/null 2>&1 && break; sleep 0.2; done
pactl load-module module-null-sink sink_name=dazedev_test > /dev/null
pactl set-default-source dazedev_test.monitor
exec dbus-run-session -- "$@"
SH
chmod +x "$work/as-tester.sh"
as_tester() { runuser -u tester -- env -C "$work" HOME=/home/tester "$work/as-tester.sh" "$@"; }

step ".deb: interface, settings persistence and microphone under Xvfb"
as_tester xvfb-run -a -s '-screen 0 1440x920x24' python3 -I verify-linux-startup.py \
  --binary /usr/bin/DazeDev --microphone --screenshot "$work/deb-window.png" \
  2>&1 | tee "$results/deb-startup.txt"
grep -q '^PASS' "$results/deb-startup.txt"
cp "$work/deb-window.png" "$results/"

if [[ -f "$work/voice_smoke" ]]; then
  step ".deb: installed voice runtime (Parakeet, Silero, S1-mini cleanup)"
  as_tester ./voice_smoke "$work/models" "$runtime/llama-server" jfk.wav parakeet \
    2>&1 | tail -5 | tee "$results/deb-voice.txt"
  grep -q '^PASS parakeet' "$results/deb-voice.txt"
  rm -rf "$work/models"
fi

step "AppImage: start, window and interface under Xvfb"
cat > "$work/appimage-run.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
export XDG_DATA_HOME=$PWD/appimage-share XDG_CONFIG_HOME=$PWD/appimage-config APPIMAGE_EXTRACT_AND_RUN=1
mkdir -p "$XDG_DATA_HOME" "$XDG_CONFIG_HOME"
./*.AppImage > appimage.log 2>&1 &
pid=$!
for _ in $(seq 60); do
  window=$(xdotool search --onlyvisible --name '^DazeDev$' 2>/dev/null | head -1 || true)
  [[ -n "$window" && -f "$XDG_DATA_HOME/DazeDev/device.json" ]] && break
  kill -0 "$pid" || { cat appimage.log; echo "AppImage exited" >&2; exit 1; }
  sleep 1
done
[[ -n "$window" ]] || { cat appimage.log; echo "No DazeDev window" >&2; exit 1; }
sleep 10 # let the interface paint
kill -0 "$pid" || { cat appimage.log; echo "AppImage exited after mapping" >&2; exit 1; }
import -window root appimage-window.png
colours=$(convert appimage-window.png -format %k info:)
test ! -f "$XDG_DATA_HOME/DazeDev/startup-error.txt"
echo "Window $(xwininfo -id "$window" | grep -E 'geometry' | tr -s ' ')"
echo "Distinct screenshot colours: $colours"
kill "$pid"; wait "$pid" 2>/dev/null || true
# A blank (background-only) webview renders a handful of colours.
[[ "$colours" -gt 50 ]] || { echo "Window looks blank" >&2; exit 1; }
echo "PASS AppImage: window mapped, interface painted, settings in $XDG_DATA_HOME/DazeDev"
SH
chmod +x "$work/appimage-run.sh"
as_tester xvfb-run -a -s '-screen 0 1440x920x24' ./appimage-run.sh 2>&1 | tee "$results/appimage-startup.txt"
grep -q '^PASS' "$results/appimage-startup.txt"
cp "$work/appimage-window.png" "$results/"

step "Remove"
apt-get remove -y dazedev > /dev/null
test ! -e /usr/bin/DazeDev
echo "PASS install, start and removal on $(. /etc/os-release; echo "$PRETTY_NAME")" | tee "$results/summary.txt"
