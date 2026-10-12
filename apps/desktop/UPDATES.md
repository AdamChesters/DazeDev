# Portable release updates

DazeDev stores client data in the portable directory instead of AppData. `data/` contains settings, models, WebView data and migration recovery. `.updates/` contains verified staging files and backups. Copy the whole folder to carry client settings between locations. Remote host profiles keep their legacy directory for compatibility.

## Client flow

Windows releases are single portable ZIP downloads. Extract the complete ZIP into a writable folder and run `DazeDev.exe`; copying the executable alone is not supported. The adjacent DLLs, `runtime/` and `licenses/` are part of the application. Downloaded models and all client settings stay together under `data/`. No installer is required.

Windows builds check published releases in `AdamChesters/DazeDev` over HTTPS without authentication. The repository and release assets must be public for external users. A failed request reports that updates could not be reached; normal app startup continues.

The check considers the most recent 100 GitHub releases, selects the highest eligible semantic version, and ignores drafts and releases without `latest.json`. Test builds accept newer prereleases and stable releases; stable builds accept only stable updates. Mac and Linux packages currently require manual updates.

Automatic checks run at startup and every 30 minutes unless disabled. Opening
Settings always checks, with Updates at the top and a prompt if a newer release is
available. The sidebar version indicator opens the update dialog and shows checking,
current, available or failed status. Older and equal versions are ignored; semantic prerelease ordering is respected. An unavailable feed does not interrupt normal startup. The user chooses Later or Download & restart. No files are downloaded until consent.

The updater checks the manifest signature, URL, declared size, ZIP checksum and archive paths. It rejects archives that target data/, traverse directories, contain linked or duplicate files, exceed bounds, or lack a Windows executable. A helper from the verified package waits for the old process and takes the portable instance lock before replacing files. The updater backs up each file before replacing it. If a write fails, it attempts to restore the original files and reports the backup path. Settings, downloaded models and remote tmux sessions remain intact.

The app preserves pending workspace changes locally before exit. Plain shell processes close during an update/restart; tmux sessions persist independently. Normal host revision checks apply on reconnection. A second running copy, antivirus locks, a read-only directory or a failed restart can prevent updating; the backup remains available. These packages have publisher-signed update metadata, not Windows Authenticode certificates.

## Publishing GitHub updates

Each Windows release needs its portable ZIP and a signed `latest.json` asset on the same `v<version>` GitHub release. A Git tag or ZIP alone is not an update. Keep the signing key stable across releases so installed builds retain their trust in the publisher. Keep the key outside the repository and packages.

After building and verifying the matching source version, run from `apps/desktop` to prepare metadata locally:

```sh
python3 scripts/github-release.py artifacts/DazeDev_<version>_windows-x64.zip \
  --notes-file release-notes.txt --key /secure/path/update-key.pem \
  --output /temporary/path/github-update
```

For authorized publication, add `--publish` using a fresh output directory. This requires a clean tracked checkout, a matching local and remote release tag, and an authenticated publisher account. It uploads the ZIP and manifest into a draft before publishing. It does not build, push code, create tags, or change repository visibility.

GitHub assets are fetched over HTTPS with restricted redirects to GitHub's download hosts. Signature, version, checksum and ZIP checks still apply. Installation revalidates the exact source and package the user accepted; a changed package requires another check and consent. Invalid signatures or mismatched release metadata are rejected.
