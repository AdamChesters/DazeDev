import base64
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "github_release", pathlib.Path(__file__).resolve().parents[1] / "scripts/github-release.py"
)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class GithubReleaseTests(unittest.TestCase):
    def test_signed_asset_matches_archive_and_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            key = root / "key.pem"
            subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True)
            public = root / "public.pem"
            subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-out", str(public)], check=True)
            archive = root / "DazeDev_0.2.0_windows-x64.zip"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("DazeDev.exe", b"MZ-test-fixture")
            notes = root / "notes.txt"
            notes.write_text("Public release")
            manifest = publisher.prepare(archive, notes, key, root / "output", "0.2.0")
            signed = json.loads(manifest.read_text())
            payload = json.loads(signed["payload"])
            self.assertEqual(payload["url"], "https://github.com/AdamChesters/DazeDev/releases/download/v0.2.0/" + archive.name)
            self.assertEqual(payload["sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
            self.assertEqual(payload["size"], archive.stat().st_size)
            (root / "payload").write_text(signed["payload"])
            (root / "signature").write_bytes(base64.b64decode(signed["signature"]))
            subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(public),
                            "-in", str(root / "payload"), "-sigfile", str(root / "signature")], check=True, capture_output=True)
            with self.assertRaises(FileExistsError):
                publisher.prepare(archive, notes, key, root / "output", "0.2.0")
            with self.assertRaises(ValueError):
                publisher.prepare(archive, notes, key, root / "other", "0.3.0")


if __name__ == "__main__":
    unittest.main()
