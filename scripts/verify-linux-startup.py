#!/usr/bin/env python3
"""Verify the packaged Linux WebKit UI and settings persistence on a test display."""
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


def verify(package):
    # Never launch from the deliverable or migrate the build user's settings.
    if (package / "data").exists():
        raise AssertionError("The package must not contain client data")
    with tempfile.TemporaryDirectory(prefix="dazedev-startup-") as temporary:
        scratch = Path(temporary)
        root = scratch / "app"
        shutil.copytree(package, root, symlinks=True)
        env = dict(os.environ, TAURI_WEBVIEW_AUTOMATION="true")
        for key, folder in [("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "share"),
                            ("XDG_CACHE_HOME", "cache")]:
            env[key] = str(scratch / folder)
            (scratch / folder).mkdir()
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
                        "webkitgtk:browserOptions": {"binary": str(root / "Start DazeDev.sh")}
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
                    wait_for(lambda: js("return document.body.innerText.includes('Portable data:')"),
                             "native client information")
                    assert str(root / "data") in js("return document.body.innerText")
                    theme = "[...document.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value==='charcoal'))"
                    if launch == 0:
                        assert js(f"return {theme}.value") == "blue"
                        js(f"const s={theme};s.value='charcoal';s.dispatchEvent(new Event('change',{{bubbles:true}}))")

                        def saved():
                            path = root / "data/device.json"
                            return path.exists() and json.loads(path.read_text()).get("theme") == "charcoal"

                        wait_for(saved, "settings written by the interface")
                    else:
                        assert js(f"return {theme}.value") == "charcoal"
                        assert js("return document.documentElement.dataset.theme") == "charcoal"
                    assert not (root / "data/startup-error.txt").exists()
                    request("DELETE", prefix)
                    session = None
                settings = json.loads((root / "data/device.json").read_text())
                assert settings["hosts"] == [] and isinstance(settings["voice"], dict)
                print("PASS packaged Linux UI: fresh startup, native settings write, restart persistence")
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
    verify(Path(sys.argv[1]).resolve())
