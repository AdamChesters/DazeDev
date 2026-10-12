"""Run Android checks with disposable loopback SSH and real tmux fixtures."""
import argparse
import json
import os
import pathlib
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument("--fixture-python", required=True, help="Python environment containing Paramiko 5.0.0")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
servers = []
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
        env = dict(os.environ,
            DAZEDEV_TEST_SSH_PORT=str(ports["127.0.0.1"]),
            DAZEDEV_TEST_SSH_PORT_V6=str(ports.get("::1", "")),
            DAZEDEV_TEST_SSH_KEY=str(fixture / "fixture-key.pem"),
            DAZEDEV_TEST_REAL_FIXTURE=str(real))
        result = subprocess.call([str(root / "gradlew"), "--offline", "--no-daemon", "--max-workers=2",
            "lintDebug", "testDebugUnitTest", "assembleDebug", "assembleRelease"], cwd=root, env=env)
    finally:
        for server in servers:
            server.terminate()
        for server in servers:
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
raise SystemExit(result)
