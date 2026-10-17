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
{
  cat /etc/linuxmint/info 2>/dev/null || true
  grep -E '^(PRETTY_NAME|VERSION_CODENAME|UBUNTU_CODENAME)=' /etc/os-release
  ldd --version | sed -n 1p
} | tee "$results/os.txt"

deb=$(ls "$artifacts"/*.deb)
appimage=$(ls "$artifacts"/*.AppImage)
apt-get update -qq

step "Install $(basename "$deb") with apt resolving dependencies"
pkg=$(dpkg-deb -f "$deb" Package)
dpkg-deb -f "$deb" Package Version Depends Recommends | tee "$results/deb-control.txt"
apt-get install -y --no-install-recommends --simulate "$deb" | grep '^Inst ' | tee "$results/apt-resolved.txt"
apt-get install -y "$deb" > "$results/apt-install.log" 2>&1 || { cat "$results/apt-install.log"; exit 1; }
dpkg -s "$pkg" | grep -E '^(Status|Version)' | tee -a "$results/deb-control.txt"
dpkg -L "$pkg" > "$results/deb-files.txt"
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
  imagemagick x11-utils xdotool ca-certificates util-linux > /dev/null
id tester > /dev/null 2>&1 || useradd -m -s /bin/bash tester
# Containers may restrict unprivileged user namespaces needed by the WebKit sandbox.
sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 > /dev/null 2>&1 || true
work=/home/tester/work
rm -rf "$work"; mkdir -p "$work"
cp "$scripts/verify-linux-startup.py" "$appimage" "$work/"
[[ -f "$artifacts/voice_smoke" ]] && cp "$artifacts/voice_smoke" "$artifacts/jfk.wav" "$work/"
chown -R tester: "$work"
chmod +x "$work"/*.AppImage
sandbox=""
if ! runuser -u tester -- unshare -U true 2> /dev/null; then
  # Only for this container: the WebKit sandbox needs user namespaces.
  sandbox="WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1"
  echo "User namespaces unavailable here; WebKit sandbox disabled for these tests only." | tee "$results/sandbox.txt"
fi
cat > "$work/as-tester.sh" <<'SH'
#!/usr/bin/env bash
# Session for the test user: private runtime folder, D-Bus and a null audio source.
set -euo pipefail
export XDG_RUNTIME_DIR=$(mktemp -d)
if pulseaudio --daemonize=yes --exit-idle-time=-1 2>/dev/null; then
  for _ in $(seq 50); do pactl info > /dev/null 2>&1 && break; sleep 0.2; done
  pactl load-module module-null-sink sink_name=dazedev_test > /dev/null || true
  pactl set-default-source dazedev_test.monitor || true
else
  echo "PulseAudio did not start; microphone check will fail" >&2
fi
exec dbus-run-session -- "$@"
SH
chmod +x "$work/as-tester.sh"
as_tester() { runuser -u tester -- env -C "$work" HOME=/home/tester $sandbox "$work/as-tester.sh" "$@"; }
failed=()
check() { # name logfile command...: record PASS/FAIL and keep going
  local name=$1 log=$2; shift 2
  step "$name"
  if "$@" > "$results/$log" 2>&1 && grep -q '^PASS' "$results/$log"; then
    echo "PASS $name" | tee -a "$results/checks.txt"
  else
    tail -40 "$results/$log"; echo "FAIL $name" | tee -a "$results/checks.txt"; failed+=("$name")
  fi
  tail -3 "$results/$log"
}
xvfb() { as_tester xvfb-run -a -s '-screen 0 1440x920x24' "$@"; }

check ".deb: interface loads, settings saved in XDG folder and kept on restart" deb-startup.txt \
  xvfb python3 -I verify-linux-startup.py --binary /usr/bin/DazeDev --screenshot "$work/deb-window.png"
cp "$work/deb-window.png" "$results/" 2>/dev/null || true
check ".deb: WebKit microphone stream" deb-microphone.txt \
  xvfb python3 -I verify-linux-startup.py --binary /usr/bin/DazeDev --microphone
if [[ -f "$work/voice_smoke" ]]; then
  check ".deb: installed voice runtime (Parakeet, Silero, S1-mini cleanup)" deb-voice.txt \
    as_tester ./voice_smoke "$work/models" "$runtime/llama-server" jfk.wav parakeet
  rm -rf "$work/models"
fi

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
check "AppImage: window mapped, interface painted, settings in XDG folder" appimage-startup.txt \
  xvfb ./appimage-run.sh
cp "$work/appimage-window.png" "$results/" 2>/dev/null || true

step "Remove"
apt-get remove -y "$pkg" > /dev/null
test ! -e /usr/bin/DazeDev
system="$(sed -n "s/^DESCRIPTION=//p" /etc/linuxmint/info 2>/dev/null || true) ($(. /etc/os-release; echo "$PRETTY_NAME"))"
if (( ${#failed[@]} )); then
  printf 'FAIL on %s: %s\n' "$system" "${failed[*]}" | tee "$results/summary.txt"; exit 1
fi
echo "PASS install, start and removal on $system" | tee "$results/summary.txt"
