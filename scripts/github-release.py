#!/usr/bin/env python3
"""Prepare signed Windows update assets; optionally publish an existing Git tag.

Signing stays local. No client credentials or hosted build workflow are required.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import zipfile

REPO = "AdamChesters/DazeDev"


def prepare(archive, notes, key, output, version):
    name = f"DazeDev_{version}_windows-x64.zip"
    if archive.name != name:
        raise ValueError("Archive version does not match source")
    with zipfile.ZipFile(archive) as package:
        if not package.read("DazeDev.exe").startswith(b"MZ"):
            raise ValueError("Archive lacks the Windows executable")
    payload = json.dumps(dict(
        version=version, notes=notes.read_text(),
        url=f"https://github.com/{REPO}/releases/download/v{version}/{name}",
        sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
        size=archive.stat().st_size,
    ), separators=(",", ":")).encode()
    with tempfile.TemporaryDirectory() as tmp:
        source = pathlib.Path(tmp) / "payload"
        source.write_bytes(payload)
        signature = subprocess.check_output([
            "openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(source),
        ])
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / "latest.json"
    # Do not silently replace already prepared release metadata.
    with manifest.open("x") as target:
        json.dump(dict(payload=payload.decode(), signature=base64.b64encode(signature).decode()), target)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=pathlib.Path)
    parser.add_argument("--notes-file", required=True, type=pathlib.Path)
    parser.add_argument("--key", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--publish", action="store_true", help="Upload to GitHub and publish; requires an existing matching tag and release authorization")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    version = json.loads((root / "package.json").read_text())["version"]
    # Verify the signing key matches the trust root shipped in the application.
    public = subprocess.check_output(["openssl", "pkey", "-in", str(args.key), "-pubout", "-outform", "DER"])
    expected = base64.b64decode((root / "src-tauri/resources/update-public-key.txt").read_text().strip())
    if public != bytes.fromhex("302a300506032b6570032100") + expected:
        raise SystemExit("Signing key does not match the application's update key")
    if args.publish:
        # Refuse dirty source and mismatched tags; never create a tag implicitly.
        if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root).strip():
            raise SystemExit("Commit and verify release source before publication")
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).strip()
        tag = subprocess.check_output(["git", "rev-parse", f"v{version}^{{commit}}"], cwd=root).strip()
        if head != tag:
            raise SystemExit("Release tag must match the verified source checkout")
        remote = subprocess.check_output(["gh", "api", f"repos/{REPO}/commits/v{version}", "--jq", ".sha"]).strip()
        if remote != head:
            raise SystemExit("Remote release tag must match the verified source checkout")
    manifest = prepare(args.archive, args.notes_file, args.key, args.output, version)
    label = version
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(-alpha\.0)?", version)
    if match:
        major, minor, patch, alpha = match.groups()
        label = f"{'Alpha-V' if alpha else ''}{major}.{minor}" + (f".{int(patch):02d}" if int(patch) else "")
    if args.publish:
        command = ["gh", "release", "create", f"v{version}", str(args.archive), str(manifest),
                   "--repo", REPO, "--verify-tag", "--draft", "--title", f"DazeDev {label}",
                   "--notes-file", str(args.notes_file)]
        if "-" in version:
            command.append("--prerelease")
        subprocess.run(command, check=True)
        # A failed upload leaves a draft, never a partially available update.
        subprocess.run(["gh", "release", "edit", f"v{version}", "--repo", REPO, "--draft=false"], check=True)
    print(f"{'Published' if args.publish else 'Prepared'} GitHub update {version}: {manifest}")


if __name__ == "__main__":
    main()
