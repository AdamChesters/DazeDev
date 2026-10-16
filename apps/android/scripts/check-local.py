"""Run Android checks with disposable loopback SSH and real tmux fixtures."""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument("--fixture-python", required=True, help="Python environment containing Paramiko 5.0.0")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
# The default browser bridge folder may belong to a live desktop session; tests must never touch it.
shared_bridge = pathlib.Path("/tmp") / f"dazedev-browser-{os.getuid()}"
def bridge_files():
    return sorted(p.name for p in shared_bridge.iterdir()) if shared_bridge.is_dir() else []
before = bridge_files()
servers = []
result = 1
with tempfile.TemporaryDirectory(prefix="dazedev-android-checks-") as scratch:
    try:
        fixture = pathlib.Path(scratch) / "ssh"
        real = pathlib.Path(scratch) / "host"
        for folder, extra in [(fixture, []), (real, ["--real-host"])]:
            server = subprocess.Popen([args.fixture_python, str(root / "scripts/ssh-fixture.py"), str(folder), *extra], stdout=subprocess.DEVNULL)
            servers.append(server)
            deadline = time.monotonic() + 20
            while not (folder / "fixture-ports.json").exists():
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Disposable SSH fixture did not start")
                time.sleep(.05)
        ports = json.loads((fixture / "fixture-ports.json").read_text())
        # A throwaway signing key lets assembleRelease run; it is deleted with the scratch folder.
        keystore = pathlib.Path(scratch) / "check-release.jks"
        keytool = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/keytool" if os.environ.get("JAVA_HOME") else shutil.which("keytool")
        subprocess.run([str(keytool), "-genkeypair", "-keystore", str(keystore), "-storepass", "check-only", "-keypass", "check-only",
            "-alias", "check", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=Local check only"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        env = dict(os.environ,
            BROWSER="/bin/true",
            DAZEDEV_REQUIRE_FIXTURES="1",
            DAZEDEV_TEST_SSH_PORT=str(ports["127.0.0.1"]),
            DAZEDEV_TEST_SSH_PORT_V6=str(ports.get("::1", "")),
            DAZEDEV_TEST_SSH_KEY=str(fixture / "fixture-key.pem"),
            DAZEDEV_TEST_SSH_ED25519_KEY=str(fixture / "fixture-ed25519.pem"),
            DAZEDEV_TEST_REAL_FIXTURE=str(real),
            DAZEDEV_RELEASE_STORE_FILE=str(keystore),
            DAZEDEV_RELEASE_STORE_PASSWORD="check-only",
            DAZEDEV_RELEASE_KEY_ALIAS="check",
            DAZEDEV_RELEASE_KEY_PASSWORD="check-only")
        result = subprocess.call([str(root / "gradlew"), "--offline", "--no-daemon", "--max-workers=2",
            "ktlintCheck", "lintDebug", "testDebugUnitTest", "assembleDebug", "assembleDebugAndroidTest", "assembleRelease"], cwd=root, env=env)
    finally:
        for server in servers:
            server.terminate()
        for server in servers:
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
after = bridge_files()
if before != after:
    # A live desktop session may also change it; review any difference by hand.
    print(f"Shared browser bridge folder changed during checks: added={sorted(set(after)-set(before))} removed={sorted(set(before)-set(after))}")
else:
    print(f"Shared browser bridge folder unchanged ({len(after)} entries).")
raise SystemExit(result)
