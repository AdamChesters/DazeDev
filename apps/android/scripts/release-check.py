"""Release gate: an APK is ready to publish only with a passing phone-test record for that exact build.

Usage: python3 scripts/release-check.py APK RECORD.json [--build-tools DIR]

The record is written by the person who tested the APK on a physical phone:

{
  "versionName": "0.1.0-alpha.7",
  "versionCode": 8,
  "apkSha256": "<optional; SHA-256 of the tested APK>",
  "device": "Pixel 8",
  "androidVersion": "16",
  "date": "2026-10-16",
  "tester": "Name",
  "checks": [{"name": "install over previous version", "passed": true}, ...]
}

The script checks that the APK's version matches the record, that the signature verifies with
APK Signature Scheme v3 and is not the Android debug key, and that every recorded check passed.
It prints "READY TO PUBLISH" and the SHA256SUMS line for the published file name
(DazeDev-Mobile-<versionName>.apk) only when all of that holds.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys

REQUIRED = ["versionName", "versionCode", "device", "androidVersion", "date", "tester", "checks"]


def build_tools(override):
    if override:
        return pathlib.Path(override)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("Set ANDROID_HOME or pass --build-tools.")
    versions = sorted((pathlib.Path(sdk) / "build-tools").iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    if not versions:
        raise SystemExit("No Android build tools found.")
    return versions[-1]


def run(tool, *args):
    result = subprocess.run([str(tool), *args], capture_output=True, text=True)
    return result.returncode, result.stdout + result.stderr


def apk_version(tools, apk):
    code, out = run(tools / "aapt2", "dump", "badging", str(apk))
    match = re.search(r"^package: .*versionCode='(\d+)' versionName='([^']*)'", out, re.M)
    if code != 0 or not match:
        raise SystemExit(f"Could not read the APK version: {out.strip()[:400]}")
    return int(match.group(1)), match.group(2)


def record_problems(record):
    problems = [f"Record is missing {field}." for field in REQUIRED if field not in record or record[field] in ("", None, [])]
    if problems:
        return problems
    try:
        tested = datetime.date.fromisoformat(str(record["date"]))
        if tested > datetime.date.today():
            problems.append("Record date is in the future.")
    except ValueError:
        problems.append("Record date must be YYYY-MM-DD.")
    checks = record["checks"]
    if not isinstance(checks, list) or not all(isinstance(c, dict) and c.get("name") for c in checks):
        problems.append('Record checks must be a list of {"name": ..., "passed": true|false}.')
    else:
        problems += [f"Phone check failed or not run: {c['name']}." for c in checks if c.get("passed") is not True]
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("record", type=pathlib.Path)
    parser.add_argument("--build-tools", help="Android build-tools folder with aapt2 and apksigner")
    args = parser.parse_args()
    tools = build_tools(args.build_tools)
    problems = []
    try:
        record = json.loads(args.record.read_text())
        if not isinstance(record, dict):
            raise ValueError("not an object")
    except (OSError, ValueError) as error:
        record = {}
        problems.append(f"No usable phone-test record ({error}).")
    problems += record_problems(record) if record else []

    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    version_code, version_name = apk_version(tools, args.apk)
    if record:
        if str(record.get("versionName")) != version_name or str(record.get("versionCode")) != str(version_code):
            problems.append(f"APK is {version_name} ({version_code}); the record tested {record.get('versionName')} ({record.get('versionCode')}).")
        if record.get("apkSha256") and record["apkSha256"].lower() != digest:
            problems.append("APK SHA-256 differs from the tested APK in the record.")

    code, out = run(tools / "apksigner", "verify", "--verbose", "--print-certs", str(args.apk))
    if code != 0 or "Verifies" not in out:
        problems.append(f"Signature does not verify: {out.strip()[:400]}")
    if not re.search(r"Verified using v3 scheme \(APK Signature Scheme v3\): true", out):
        problems.append("APK is not signed with APK Signature Scheme v3.")
    if "CN=Android Debug" in out:
        problems.append("APK is signed with the Android debug key.")

    print(f"APK: {args.apk.name} {version_name} ({version_code})")
    if problems:
        for problem in problems:
            print(f"- {problem}")
        print("NOT READY TO PUBLISH")
        return 1
    print(f"Tested on {record['device']} (Android {record['androidVersion']}) by {record['tester']} on {record['date']}; {len(record['checks'])} checks passed.")
    print("READY TO PUBLISH")
    print(f"SHA256SUMS line: {digest}  DazeDev-Mobile-{version_name}.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())
