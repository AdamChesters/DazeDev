# DazeDev Mobile

Native Android companion for DazeDev. Connect SSH/tmux hosts, browse shared workspaces, open an interactive terminal, and prepare editable speech drafts on the phone. Each host has its own connection and trusted-key store.

Build from this directory with `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease`. Requirements: JDK 17 or newer, Android SDK/build tools 36, and `npm --prefix apps/desktop ci` from the repository root. Gradle stages the canonical host helper, xterm bundle and generated DazeDev logo. Release APKs are unsigned.

Use `python3 scripts/check-local.py --fixture-python /path/to/venv/bin/python` with a disposable environment containing Paramiko 5.0.0 to run all SSH fixture tests. No production accounts or keys are needed. Device UI and voice measurements need a real phone or usable emulator.

Application ID: `com.adamchesters.dazedev.mobile`. It installs alongside the former app; it cannot read another application's private settings. Passwords and passphrases are not saved. Check server fingerprints before trusting them. Windows-local sessions need a separate bridge.

Moonshine English Medium Streaming stays local after preparation. First use downloads about 269 MB with consent. Pasting a draft does not press Enter. No remote transcription fallback is configured.

Built with AndroidX/Compose, JSch, xterm.js and Moonshine. Full notices are available offline in Licenses and credits.
