#!/usr/bin/env python3
"""Verify the packaged Linux WebKit UI and settings persistence on a test display.

Portable folder:  verify-linux-startup.py PATH/TO/DazeDev_<version>_linux-x64
Installed (.deb): verify-linux-startup.py --binary /usr/bin/DazeDev
Options: --microphone also requires a working WebKit microphone stream (needs a
PulseAudio/PipeWire source); --screenshot FILE saves the window as PNG.
"""
import argparse
import base64
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


def wait_for(check, description):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.2)
    raise AssertionError(f"Timed out: {description}")


MICROPHONE = """
const done = arguments[arguments.length - 1];
if (!navigator.mediaDevices) { done({error: 'navigator.mediaDevices is unavailable'}); return; }
navigator.mediaDevices.getUserMedia({audio: true}).then(async (stream) => {
  const track = stream.getAudioTracks()[0];
  const result = {tracks: stream.getAudioTracks().length, label: track && track.label,
                  state: track && track.readyState};
  stream.getTracks().forEach((t) => t.stop());
  const devices = await navigator.mediaDevices.enumerateDevices();
  result.inputs = devices.filter((d) => d.kind === 'audioinput').map((d) => d.label);
  done(result);
}).catch(async (e) => {
  const devices = await navigator.mediaDevices.enumerateDevices().catch(() => []);
  done({error: String(e), devices: devices.map((d) => d.kind + ':' + d.label)});
});
"""


def released(lock):
    """True once the previous app instance has dropped its single-instance lock."""
    if not lock.exists():
        return True
    with lock.open("rb") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return False
        fcntl.flock(handle, fcntl.LOCK_UN)
        return True


def verify(package=None, binary=None, microphone=False, screenshot=None):
    with tempfile.TemporaryDirectory(prefix="dazedev-startup-") as temporary:
        scratch = Path(temporary)
        env = dict(os.environ, TAURI_WEBVIEW_AUTOMATION="true")
        for key, folder in [("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "share"),
                            ("XDG_CACHE_HOME", "cache")]:
            env[key] = str(scratch / folder)
            (scratch / folder).mkdir()
        env.pop("DAZEDEV_DATA_DIR", None)
        if binary is None:
            # Never launch from the deliverable or migrate the build user's settings.
            if (package / "data").exists():
                raise AssertionError("The package must not contain client data")
            root = scratch / "app"
            shutil.copytree(package, root, symlinks=True)
            binary = root / "Start DazeDev.sh"
            data = root / "data"
        else:
            # Installed packages keep settings in the user's XDG data folder.
            data = scratch / "share" / "DazeDev"
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        base = f"http://127.0.0.1:{port}"

        def request(method, path, payload=None):
            req = urllib.request.Request(
                base + path, method=method,
                data=json.dumps(payload).encode() if payload is not None else None,
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=35) as response:
                value = json.load(response)["value"]
            if isinstance(value, dict) and "error" in value:
                raise AssertionError(value)
            return value

        with (scratch / "driver.log").open("w+") as log:
            driver = subprocess.Popen(
                ["WebKitWebDriver", f"--port={port}", "--host=127.0.0.1"],
                env=env, stdout=log, stderr=log, start_new_session=True,
            )
            session = None
            try:
                def ready():
                    if driver.poll() is not None:
                        raise AssertionError("WebKitWebDriver exited before startup")
                    try:
                        return request("GET", "/status")["ready"]
                    except urllib.error.URLError:
                        return False

                wait_for(ready, "WebKitWebDriver")
                for launch in range(2):
                    session = request("POST", "/session", {"capabilities": {"alwaysMatch": {
                        "webkitgtk:browserOptions": {"binary": str(binary)}
                    }}})["sessionId"]
                    prefix = f"/session/{session}"

                    def js(script):
                        return request("POST", prefix + "/execute/sync",
                                       {"script": script, "args": []})

                    wait_for(lambda: js("return !!document.querySelector('button[aria-label=\"Settings\"]')"),
                             "embedded interface")
                    assert request("GET", prefix + "/url").startswith("tauri://localhost")
                    assert "Connect your first host" in js("return document.body.innerText")
                    # Wait for native initialization before replacing the first-run dialog.
                    wait_for(lambda: js("return !!document.querySelector('.modal')"), "voice setup dialog")
                    js("document.querySelector('button[aria-label=\"Settings\"]').click()")
                    wait_for(lambda: js("return document.body.textContent.includes('Portable data:')"),
                             "native client information")
                    assert str(data) in js("return document.body.textContent")  # inside a closed <details>
                    theme = "[...document.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='charcoal'))"
                    if launch == 0:
                        assert js(f"return {theme}.value") == "blue"
                        js(f"const s={theme};s.value='charcoal';s.dispatchEvent(new Event('change',{{bubbles:true}}))")

                        def saved():
                            path = data / "device.json"
                            return path.exists() and json.loads(path.read_text()).get("theme") == "charcoal"

                        wait_for(saved, "settings written by the interface")
                    else:
                        assert js(f"return {theme}.value") == "charcoal"
                        assert js("return document.documentElement.dataset.theme") == "charcoal"
                    if launch == 1 and microphone:
                        result = request("POST", prefix + "/execute/async",
                                         {"script": MICROPHONE, "args": []})
                        print("Microphone:", json.dumps(result))
                        if "error" in result or result.get("tracks", 0) < 1 \
                                or result.get("state") != "live":
                            raise AssertionError(f"Microphone capture failed: {result}")
                    if launch == 1 and screenshot:
                        image = request("GET", prefix + "/screenshot")
                        Path(screenshot).write_bytes(base64.b64decode(image))
                    assert not (data / "startup-error.txt").exists()
                    request("DELETE", prefix)
                    session = None
                    # The next launch must not race the previous process's shutdown.
                    wait_for(lambda: released(data / "instance.lock"), "previous instance exit")
                settings = json.loads((data / "device.json").read_text())
                assert settings["hosts"] == [] and isinstance(settings["voice"], dict)
                print(f"PASS packaged Linux UI ({binary}): fresh startup, native settings write "
                      f"to {data}, restart persistence" + (", microphone stream" if microphone else ""))
            except Exception:
                log.flush()
                log.seek(0)
                print(log.read(), file=sys.stderr)
                raise
            finally:
                if session is not None:
                    try:
                        request("DELETE", f"/session/{session}")
                    except Exception:
                        pass
                try:
                    os.killpg(driver.pid, signal.SIGTERM)
                    driver.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(driver.pid, signal.SIGKILL)
                    driver.wait()
                except ProcessLookupError:
                    driver.wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("package", nargs="?", type=Path, help="extracted portable folder")
    parser.add_argument("--binary", type=Path, help="installed executable, e.g. /usr/bin/DazeDev")
    parser.add_argument("--microphone", action="store_true")
    parser.add_argument("--screenshot", type=Path)
    args = parser.parse_args()
    if (args.package is None) == (args.binary is None):
        parser.error("give either a portable folder or --binary")
    verify(args.package.resolve() if args.package else None, args.binary, args.microphone,
           args.screenshot)
