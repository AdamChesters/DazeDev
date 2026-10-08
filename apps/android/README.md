# DazeDev Mobile

Native Android companion for DazeDev. Connect SSH/tmux hosts, browse shared workspaces, open an interactive terminal, and prepare editable speech drafts on the phone. Each host has its own connection and trusted-key store.

Build from this directory with `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease`. Requirements: JDK 17 or newer, Android SDK/build tools 36, and `npm --prefix apps/desktop ci` from the repository root. Gradle stages the canonical host helper, xterm bundle and generated DazeDev logo. Release APKs are unsigned.

Use `python3 scripts/check-local.py --fixture-python /path/to/venv/bin/python` with a disposable environment containing Paramiko 5.0.0 to run all SSH fixture tests. No production accounts or keys are needed. Device UI and voice measurements need a real phone or usable emulator.

Application ID: `com.adamchesters.dazedev.mobile`. It installs alongside the former app; it cannot read another application's private settings. Passwords and passphrases are not saved. Check server fingerprints before trusting them. Windows-local sessions need a separate bridge.

Moonshine English Medium Streaming stays local after preparation. First use downloads about 269 MB with consent. Installed models skip the preparation dialog. Tap Enter to submit the reviewed voice draft and press Return; a successful submission clears the draft. Clipboard Paste only inserts text. No remote transcription fallback is configured.

Terminal cards show their target host before you open them. If a host is not configured on this phone, its connection form opens with any saved non-secret details. Older workspace entries may require an SSH address. Saved Tailscale SSH targets reconnect on tap when tailnet access permits; the saved host identity is verified before opening the terminal. Plain SSH over Tailscale uses its configured SSH authentication method.

Built with AndroidX/Compose, JSch, xterm.js and Moonshine. Full notices are available offline in Licenses and credits.

Device-only voice tests never download models. Build `./gradlew assembleDebugAndroidTest`, install the debug app and test APK, then run `MoonshineDeviceTest#modelManifest` with AndroidJUnitRunner to inspect the pinned runtime's download manifest. After separately preparing and verifying the model, run `MoonshineDeviceTest#offlineInferenceWithExplicitlyStagedFixture` with `modelDirectory` and `pcmFixture` arguments. The fixture must be at most ten seconds of 16 kHz mono signed 16-bit PCM; optional `expectedPhrase` checks its transcription. Disable device networking to verify installed inference. The result records load/inference times and final PSS, not a thermal or peak-memory qualification.
